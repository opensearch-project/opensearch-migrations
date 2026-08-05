# Pluggable Document Sources for RFS

Status: proposal, revised after review. Related:
[#3065](https://github.com/opensearch-project/opensearch-migrations/issues/3065) (redrive),
[#3063](https://github.com/opensearch-project/opensearch-migrations/issues/3063),
[#3064](https://github.com/opensearch-project/opensearch-migrations/issues/3064),
[#3066](https://github.com/opensearch-project/opensearch-migrations/issues/3066).
Track the work under a new issue; #3065's scope is narrower.

Two positions from the first draft were reversed in review: partitions are now addressed by **name**
rather than ordinal, and `readDocuments` resumes from an **opaque cursor** rather than a document
count. Both reversals delete material.

## The idea

RFS is already a general-purpose document pump — it reads documents, transforms them, and writes them
to a target with work coordination, leases, retries and resumable progress. What it lacks is a way to
*plug in* a new source. The reading abstraction is clean; the wiring that picks a source is a
hard-coded branch reaching straight into CLI arguments.

Redrive of failed documents shouldn't be a new feature. It should be a new *source*.

```
BEFORE
  main() --+-- if flavor == SOLR --> buildSolrSourceFactory ----+
           |                                                    +--> prepareAndMigrate
           +-- else ---------------> buildEsSourceFactory ------+

AFTER
  main() ----> spec ----> registry ----> provider.create() -----+--> prepareAndMigrate
                                         (discovered, not wired)
```

## Terminology

| Term | Meaning |
|---|---|
| **Collection** | ES/Solr index or collection; an `index=` prefix in the failure stream. |
| **Partition** | Unit of parallel work in a collection — a shard, or a `worker=` prefix. One work item covers one partition. |
| **Cursor** | Opaque, source-defined token identifying a resume position within a partition. |
| **Seal** | Closing a failure-stream session to further writes by publishing an immutable manifest of its contents. Only a sealed session can be a source. |
| **Unreadable** | Record that cannot be parsed — corrupt gzip or NDJSON. Fails the run loudly. |

## The interfaces

```java
public interface PartitionEnumerator {
    /** All collection names available from this source. Deterministic. */
    List<String> listCollections();

    /** Partitions in a collection. Names unique within the collection; order not significant. */
    List<Partition> listPartitions(String collectionName);

    /** Resolve by the name recorded in a work item. Empty means gone — an error, not an empty read. */
    Optional<Partition> findPartition(String collectionName, String partitionName);
}

public interface DocumentStream {
    /**
     * Documents for a partition, resuming after the given cursor (null starts at the beginning).
     * Cold Flux — subscription triggers the read, and replays identically from the same cursor.
     */
    Flux<PositionedDocument> readDocuments(Partition partition, String startingCursor);
}

/** A document paired with the cursor that resumes immediately after it. */
public record PositionedDocument(Document document, String cursorAfter) {}

public interface DocumentSource extends PartitionEnumerator, DocumentStream, AutoCloseable {
    /** Not part of the contract; retained through Phase 1, removed after. */
    CollectionMetadata readCollectionMetadata(String collectionName);

    @Override
    default void close() throws Exception { }
}
```

`readCollectionMetadata` is excluded from the contract because no production code calls it — it serves
an uncoordinated "describe then create" path that has no user once all redrives are coordinated.

**Implementation contract.** A source may be subscribed concurrently for different partitions and must
be safe under that. `readDocuments` may block on I/O; the pipeline subscribes on `boundedElastic`. The
returned `Flux` must honor backpressure.

There is no read-side rate control. A worker migrates one partition (`partitionConcurrency` is 1 in
`DocumentMigrationBootstrap`, and goes away with `migrateCollection`), so cross-partition parallelism
means more workers. `batchConcurrency` bounds bulk writes in flight, not reads. A source that needs
throttling — a live-cluster bulk-getter, say — implements it internally and exposes the knobs on its
own spec.

## Partition identity: names, not ordinals

Today `ShardWorkPreparer` assigns positions with `IntStream.range(0, shardCount)` and
`DocumentMigrationBootstrap` resolves them with `partitions.get(shardIdx)` against a *fresh*
`listPartitions` call, in a different process, potentially hours later. The ordinal is a positional
reference into a list that must be re-derived in the same sequence — a requirement nothing states and
nothing tests.

`WorkItem.shardNumber` has four consumers outside tests, so the change is contained. It does break the
on-disk id format, which is acceptable **only** on the premise that no in-flight migration spans
builds. That premise is load-bearing; if it is wrong, this decision has to be revisited.

What it buys: `listPartitions` order stops mattering, so the ordering contract, the per-source
ordering table and the 11-partition conformance test all disappear. Solr's `shard1, shard10, shard2`
enumeration becomes cosmetic. Logs print real names. A vanished partition fails loudly instead of
shifting every later ordinal. `Partition` no longer needs `Comparable` — its lexicographic `compareTo`
is dead code today, and removing the interface makes future accidental sorts a compile error.

### Work-item id format

```
base64url(collectionName) . base64url(partitionName) . base64url(cursor)
```

All three segments are encoded, not just the first: two of them are now arbitrary strings rather than
integers, so `parseInt`/`parseLong` go away. This makes UTF-8 a requirement on cursors: raw bytes
would break it.

The delimiter is `.`, not `__`. The first draft argued `__` was safe because base64url of valid UTF-8
can never *contain* `__`. True, but not sufficient: base64url output can begin or end with a single
`_`, so `"a_" + "__" + "_b"` and `"a" + "__" + "__b"` both render as `a____b` and no parser can tell
them apart. `.` is outside the base64url alphabet (`A-Z a-z 0-9 - _`) entirely, so no segment value
can produce it and the split is unambiguous by construction.

## The resume contract

An offset presumes the source can cheaply address the *n*th document — true for a Lucene segment,
false for a paginated API or anything open-ended. An opaque cursor lets each source encode what it
needs.

> Resuming from the cursor emitted with document `d` must yield every document the source would have
> emitted after `d`, and must not re-emit `d` or anything before it.

Duplicate *delivery* is still expected: a lease that expires after documents were sent but before the
cursor was recorded will re-send them. That is the watermark lagging, not the source re-reading — the
first draft conflated the two.

The two snapshot reader modes disagree about the offset's units: delta mode skips that many *emitted*
documents, regular mode treats it as a Lucene doc index, which also counts deleted ones. The pipeline
sends an emitted count to both, so a resumed regular read restarts too early and re-sends the
difference. A cursor leaves no unit to guess at — with the source minting its own cursor, this stops
being possible to get wrong, and Phase 1 tags each mode's cursor (`lucene:` vs `delta:`) so a cursor
recorded in one mode is rejected in the other rather than silently misread.

**Cost.** This does not stay inside the SPI. `DocumentMigrationPipeline` currently derives the next
position arithmetically (`cumulativeOffset += result.docsInBatch()`), which an opaque cursor makes
impossible — the source must supply it. `BatchResult`, `ProgressCursor` and `WorkItemCursor` all have
to carry an opaque value. Larger than the name change, and it touches the batching path.

Cursors also leave room for a later `getBisectionPoint(start, end)` so one partition can be split
across processes. Not proposed here, but not foreclosed.

## The plugging layer

**Where it lives.** Not `RfsPipeline` — it takes `:RfsCommon` as `compileOnly` under a "core IR types
have ZERO dependency on RfsCommon" rule, so it cannot see `Version` or `VersionStrictness`. A new
`:RfsSourceSpi` on `RfsPipeline` + `RfsCommon` resolves both. `RootDocumentMigrationContext` stays
out; providers need only `IRfsContexts.IDeltaStreamContext`, already in `RfsCommon`. The SPI must not
grow a shadow of the RFS tracing hierarchy.

```java
/** Only `kind` is shared. Each provider defines its own spec type. */
public interface DocumentSourceSpec { String kind(); }

public record EsSnapshotSpec(String repoUri, String snapshotName, Version version,
                             List<String> indexAllowlist) implements DocumentSourceSpec { }
public record FailedDocumentStreamSpec(String sessionUri, String sessionId) implements DocumentSourceSpec { }

/** Framework services a provider is handed. User config lives in the spec, not here. */
public record SourceRuntime(
    Path scratchDir, Path workDir,
    Supplier<IRfsContexts.IDeltaStreamContext> deltaStreamContextFactory
) {}

public interface DocumentSourceProvider<S extends DocumentSourceSpec> {
    String kind();

    /** Parse this provider's own config. Kind selects the provider; the provider reads the rest. */
    S parseSpec(JsonNode config);

    /** Reject a malformed source before construction. May do I/O. */
    default void validate(S spec, SourceRuntime runtime) { }

    /** True if construction is expensive enough to skip when no work remains. */
    default boolean deferUntilWorkAvailable() { return false; }

    DocumentSource create(S spec, SourceRuntime runtime) throws IOException;

    /** Parse, validate, create. Keeps S from leaking to callers holding a wildcard. */
    default DocumentSource open(JsonNode config, SourceRuntime runtime) throws IOException {
        S spec = parseSpec(config);
        validate(spec, runtime);
        return create(spec, runtime);
    }
}
```

An allowlist is an index list for ES, a key prefix for raw S3, a session id for the failure stream —
not one concept, so it does not belong in a shared record. `parseSpec` sits on the provider rather
than using Jackson's `@JsonSubTypes` (the `BulkOperationSpec` pattern), since that needs subtypes
registered at compile time and would undo discovery.

S3 region, endpoint and version strictness were on `SourceRuntime` in the first draft. They are user
config, so they moved to the specs. It stays a sketch until the three providers land.

Shared reader code already has a home: `:SearchSnapshotExtractor` holds `LuceneReader`,
`DocumentReaderEngine` and the shadowed Lucene 6/7/9 jars, and both existing readers depend on it.

**Discovery** uses [Java's service-provider mechanism](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/ServiceLoader.html),
as the repo already does for `IJsonTransformerProvider`. Every provider ships in the distribution, so
this is decoupling rather than deployment — it still earns its place for users who build their own
images. The registry maps normalized `kind()` to provider, built and checked once at startup — a duplicate or
blank kind fails there, and the error names the provider class — while source construction stays lazy. Discovery needs an
assembled-application test: a missing `META-INF/services` entry unit-tests clean and finds zero
providers at runtime.

**Construction stays lazy.** The registry replaces the `if/else` *inside* the existing source factory,
so the Solr path can still check whether work is already complete before any S3 setup:

```java
MigrationSourceFactory sourceFactory = (wc, pm, cursor, cancelRef, timeProvider) -> {
    var provider = DocumentSourceRegistry.resolve(kind);
    if (provider.deferUntilWorkAvailable() && isCoordinatorWorkAlreadyDone(wc, context)) {
        throw new NoWorkLeftException("All work items already complete; skipping source setup.");
    }
    return prepareAndMigrate(provider.open(config, runtime), wc, pm, targetClient, /* ... */);
};
```

Three levels: `listCollections` reads snapshot metadata, `listPartitions` reads shard metadata (file
lists and sizes), and `readDocuments` unpacks the shard files. Only the last is expensive, and it runs
only once a work item is held — the shard-size limit rejects an oversized partition from level-2
metadata before any unpack. The pre-check exists so a restarted pod with no work left skips even the
cheap levels.

## Conformance test

An abstract `DocumentSourceContractTest` in `RfsPipeline`'s test fixtures, extended by every
implementation:

1. Partition names are unique within a collection.
2. Repeated `listPartitions` calls, and calls from a fresh source, return the same *set*. Order is not
   asserted.
3. `findPartition` resolves every returned name and rejects any other.
4. `listCollections` is deterministic.
5. Resuming from the cursor emitted with `d` omits nothing after `d` and re-emits nothing at or
   before it; resuming from the last cursor yields nothing; the same cursor replays identically.

It earned its keep immediately: rule 5 failed on the snapshot source because `SnapshotShardUnpacker`
threw rather than no-op'ing when the shard was already unpacked, so a second read of one partition in
one process could not happen. Production never did that — one worker, one shard, one read — so
nothing else would have caught it. Unpacking is now idempotent.

## First new source: failed-document-stream redrive

The stream is already laid out as `session=<id>/index=<target>/worker=<id>/*.ndjson.gz`, which maps
straight onto the interfaces: collections are `index=` prefixes, partitions are `worker=` prefixes,
documents are records.

Conversion is itself a transform, chained ahead of the user's:

```
NDJSON record --[record -> bulk operation]--> operation --[user's transform]--> sink
```

The source does not interpret the record. It emits a `Document` whose `source` bytes are the whole
record, so the sink's existing convert-then-transform path hands the full record to transform 1,
which rewrites it into a real operation — `_id`, operation type, routing, `_type`, body. Setting
`operation_type` is enough to switch subtype, since `BulkOperationSpec` binds it on deserialization.
Nothing narrows on the way, so no IR change is needed.

Two consequences. **Transform 1 is mandatory for this source** — without it the sink writes whole
records as documents with server-assigned ids. And `applyTransformation` correlates `originalSource`
by document id, which is unset until transform 1 runs, so a re-failure during redrive stores the
post-transform-1 body rather than the source-index document.

The stream stores the pre-transform document, so the user's transform re-runs at redrive time and an
operator who fixed a broken transform gets the fix applied. Coordination, retries, progress and
metrics come along; re-failures land back in the stream, so redriving a redrive needs no new
machinery.

### Bad records

The sink guarantees every record it writes converts back to a `Document`. A reader that finds
otherwise has hit corruption or a schema break, not a data category — it fails the run loudly with
the object key and record ordinal, exactly as an unreadable gzip or NDJSON body does. Unknown
*fields* are still ignored, for forward compatibility.

Outcomes are therefore two: redriven, or failed again at the target. Phase 3 emits both as
diagnostics and metrics; aggregating them into a run state is #3066's `CompletedWithErrors` work, and
Phase 4's console feature depends on it. `CompletionStatus` is unchanged.

### Sealing

A session still being written can gain a `worker=` prefix or more records after enumeration. Name-based
identity makes that survivable — a new partition simply has no work item — but "silently skipped" is
not good enough for a feature whose promise is completeness. So `validate` rejects an unsealed session.

Sealing publishes one immutable manifest per session: schema version, session id, collections,
partitions, and ordered object keys per partition. The provider enumerates from the manifest, never
from a live listing, which also fixes object order for cursor resume.

It is written by the worker that exits on `NoWorkLeftException`, since workers do one shard and exit
and there is no in-process completion moment. Several may race, so serialization must be canonical —
sorted keys, fixed property order, no map iteration, nothing drawn from `now()`. Publish with
`If-None-Match: *`; losers read back and compare a digest. A console `seal` command covers aborted
backfills.

**A seal is permanent.** A live listing validates integrity only; a missing or extra object means the
session was not closed when sealed, and the run fails. Correction means copying objects into a new
session and sealing that.

### Reading within a partition

The manifest defines object order and the reader never sorts. `seq` is unpadded, so two rotations in
one second land `-10` before `-2` — leave it; re-sorting would invalidate cursors already recorded
against a sealed manifest. A cursor encodes object key plus record ordinal, so it spans files.
Duplicates are retained, since redrive writes are id-addressed upserts; global dedup would break
cursor semantics. Records with a null id are the exception — replaying one creates a second document.

### Redriving into a changed target

If the original migration demultiplexed several source indices into one target, two documents can map
to the same `_id`, and a redrive months later will overwrite whatever now holds it — possibly a
different source index's document, or a newer version. The redrive reports success while destroying
data. RFS cannot detect this, so it needs a prominent confirmation at invocation naming the indices
being written and stating that existing documents at those ids will be replaced.

## Caller responsibilities

Outside this design, because a source cannot see the sink's configuration:

- The failure-stream output location must differ from the input, or the run reads and writes the same
  place and never terminates. Compare normalized bucket, prefix and session.
- The run needs its own `--session-name` so coordination does not collide with the original backfill.
  This changes the coordination namespace only, not which source session is read.

## Sequencing

- **Phase 1** — interfaces, registry, conformance test; port the two existing sources; switch
  work-item identity to names and progress to cursors. No argument change. If that is too much for one
  reviewable unit, the name change stands alone and can land first. Also break the
  `SnapshotReader → SolrReader` dependency (`S3Repo` imports `SolrBackupLayout`); provider modules
  must not depend on each other.
- **Phase 2** — expose `--source-kind` and the `--source-*` group, inferring from today's arguments.
- **Phase 3** — the failed-document-stream source, the record-to-operation transform, the manifest
  writer and the console `seal` command.
- **Phase 4** — wire up the console command and rewrite #3065's acceptance criteria. #3065's premise
  predates two changes: the stored request is the pre-transform document, and there is no `dlq`
  command group, only `failed-document-stream`.
- **Follow-up, any time after Phase 1** — remove the uncoordinated path, leaving `migratePartition`
  as the only pipeline entry point (`readCollectionMetadata`, `createCollection`,
  `migrateAll`/`migrateCollection` and `partitionConcurrency` go with it).

## Decisions

- **All redrives use the coordinated path.** No size threshold, no in-process mode. One path means one
  behavior to test and support. The cost is accepted: recovering a handful of documents still means a
  coordination index, leases and a worker. A later optimization — reusing the coordinating node when a
  run ends in `CompletedWithErrors` — would take that from minutes to seconds.
- **Documents without ids — relax `Document.id` to nullable.** Omitting `_id` reproduces the original
  write; synthesizing fabricates identity. Index ops only. Needs its own flag, distinct from
  `allowServerGeneratedIds`, which strips ids from *every* operation. Default is skip-and-report; the
  flag acknowledges that a retry may duplicate.
- **Post-redrive reconciliation lives in the SnapshotMigration CR status.** Failed-document counts are
  appended as the first run proceeds; each reconciliation run amends the list with how many were
  remedied. Touches #3063, #3064 (status shape) and #3066 (state transitions).
- **No partition-set token.** It only ever worked around ordinal addressing.
## Open

- "RFS" reads as *reindex-from-snapshot*, but a failure stream is not a snapshot. The mismatch
  predates this design and becomes conspicuous with it. Renaming is out of scope; noted so it is
  picked up deliberately.
