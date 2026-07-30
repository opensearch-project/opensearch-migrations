# Pluggable Document Sources for RFS

Status: proposal, for review. Written in response to
[this comment](https://github.com/opensearch-project/opensearch-migrations/issues/3065#issuecomment-5095960846)
on #3065 and the request for precise interfaces in
[this follow-up](https://github.com/opensearch-project/opensearch-migrations/issues/3065#issuecomment-5122410952).
If we move forward with this design, the work should be tracked under a new issue rather than #3065,
whose scope is narrower.

Related: [#3065](https://github.com/opensearch-project/opensearch-migrations/issues/3065) (redrive),
[#3063](https://github.com/opensearch-project/opensearch-migrations/issues/3063),
[#3064](https://github.com/opensearch-project/opensearch-migrations/issues/3064),
[#3066](https://github.com/opensearch-project/opensearch-migrations/issues/3066).

## The idea

RFS is already a general-purpose document pump: a source-agnostic pipeline that reads documents, transforms them, and writes them to a target with work coordination, leases, retries, and resumable progress. What it lacks is a way to *plug in* a new source. The reading abstraction exists and is clean; the wiring that decides which source to build is a hard-coded branch on source type, and each branch reaches directly into CLI arguments.

That's the thing to fix. Redrive of failed documents shouldn't be a new feature — it should be a new *source*.

```
BEFORE
  main() --+-- if flavor == SOLR --> buildSolrSourceFactory ----+
           |                                                    +--> prepareAndMigrate
           +-- else ---------------> buildEsSourceFactory ------+

AFTER
  main() ----> spec ----> registry ----> provider.create() -----+--> prepareAndMigrate
                                         (discovered, not wired)
```

```
       PROVIDERS                      CORE  (already source-blind)
  +--------------------+
  | es-snapshot        |
  | solr-backup        |    +----------+    +----------+    +------+
  | failed-doc-stream  |--->| Document |--->| pipeline |--->| sink |---> OpenSearch
  | (future: platform) |    |  Source  |    +----------+    +------+
  +--------------------+    +----------+     batch,          transform,     |
           ^                                 resume,         bulk write     |
           |                                 coordinate                     |
    registry picks one                                                      |
    by spec.kind                                                       failures
           ^                                                                |
           |             +---------------------+                            |
           +-------------|  S3 failure stream  |<---------------------------+
                         +---------------------+
             redrive: yesterday's failures are just another source
```

## The interfaces

The contract is three interfaces: enumeration, ordering, streaming. Separating them lets each be
stated, reused and tested independently; composing them keeps existing call sites unchanged. Today's
`DocumentSource` carries a fourth method, `readCollectionMetadata`, which is not part of the contract —
see the note under "Composition".

### 1. Enumeration — how many partitions there are, and what each is called

The count is the list size; the names come from `Partition.name()`. `name()` must be stable for a
given partition of a given frozen source — it is identity, not display text — because it is the key a
future move to name-based work items would use (see "Work-item identity").

```java
public interface PartitionEnumerator {
    /** All collection names available from this source. Deterministic. */
    List<String> listCollections();

    /**
     * All partitions for a collection, in canonical order (see PartitionOrdering).
     * Deterministic: the same frozen source yields the same list in the same order,
     * in any process, on any host.
     */
    List<Partition> listPartitions(String collectionName);
}
```

### 2. Ordering — the canonical total order

Ordering is currently *emergent* — an accident of the `List` order that `listPartitions` happens to
return. It is load-bearing (see "Why ordering is correctness-critical") so it should be named.

```java
public interface PartitionOrdering {
    /**
     * The canonical order of one collection's partitions. Must be a strict total
     * order within that collection; partitions of different collections are never
     * compared, since ordinals are assigned per collection.
     *
     * listPartitions() must already return partitions in this order — the framework
     * does not re-sort. The comparator exists so the ordering is explicit, reviewable,
     * and assertable in tests.
     */
    Comparator<Partition> canonicalOrder();
}
```

A source downcasts to its own partition type here, which is safe since it only orders partitions it
produced. A `sortKey()` on `Partition` would avoid the cast but fix the key type. Low stakes; easy to
switch.

### 3. Streaming — documents for a partition

Unchanged from today.

```java
public interface DocumentStream {
    /**
     * Stream documents for a partition, starting from the given offset.
     * Returns a cold Flux — subscription triggers the read. Replayable: repeated
     * subscription yields the same sequence.
     */
    Flux<Document> readDocuments(Partition partition, long startingDocOffset);
}
```

The offset's units are source-defined, but it must obey one law, because the pipeline advances it
additively as `startingDocOffset + docsInBatch`:

> After reading *n* emitted documents from offset *s*, resuming from *s + n* must not omit any document
> not yet emitted. Repetition is allowed.

Byte offsets and opaque tokens are therefore not permissible encodings. Both existing readers comply:
delta mode counts emitted documents (`.skip`); regular mode passes a Lucene doc index, and since
deleted docs are not emitted, *s + n* under-counts and re-reads. Safe wherever writes carry an id — see
the null-id exception under "Reading within a partition". The two modes disagreeing is a latent trap
worth resolving separately.

### Composition

```java
public interface DocumentSource
        extends PartitionEnumerator, PartitionOrdering, DocumentStream, AutoCloseable {

    /** Not part of the contract; retained through Phase 1, removed after. */
    CollectionMetadata readCollectionMetadata(String collectionName);

    @Override
    default void close() throws Exception { }
}
```

Existing implementations and call sites keep working; only `canonicalOrder()` is new.

`readCollectionMetadata` is excluded because no production code calls it. It belongs to an
uncoordinated "describe then create" path, and index creation is metadata migration's job. With all
redrives on the coordinated path, that path has no remaining user. It stays on `DocumentSource`
through Phase 1 and is removed as a follow-up.

## Why ordering is correctness-critical

Work items are created by partition *position* (`ShardWorkPreparer` iterates `listPartitions` and
assigns ordinals) and later resolved by position (`DocumentMigrationBootstrap.resolvePartition` does
`partitions.get(shardIdx)`). Those are two independent `listPartitions` calls, potentially in
different processes on different hosts, minutes or hours apart. The ordinal is the only thing carried
between them.

The requirement is therefore *agreement*, not any particular order. A consistently different order is
only a relabeling; coverage stays complete.

The damage comes from *disagreement*: between the process that created the work items and the ones
resolving them, between two workers, or across time as the partition set mutates or a new build
changes the order while old work items still encode the old one. Then some partitions are read twice
and others never at all — nothing fails, and the run reports success with documents missing.
Determinism plus immutability is how workers agree without coordinating.

Note this concerns *reading*. It says nothing about where documents land on the target — the bulk API
routes by `_id` (or by the routing value carried in `Document.hints`), and the target's shard count
need not match the source's. The guarantee being bought is complete coverage: every partition is read
in full and none is missed. Retries and lease handoffs mean some partitions and documents are read more
than once — at-least-once, not exactly-once.

### Canonical order must reproduce today's order

This is the sharpest compatibility hazard in the refactor. `Partition` currently extends `Comparable`
with a default `compareTo` that compares `name()` lexicographically. `EsShardPartition.name()` is
`snapshot/index/shardNumber`, so that default orders an 11-shard index as `0, 1, 10, 11, 2, ...`
while the source actually returns `0, 1, 2, ..., 10, 11`.

Nothing sorts `Partition` objects today, so it is dormant — and making ordering explicit is what would
wake it up. The risk is not "sorted differently"; it is sorted differently *from the work items already
written to the coordination index*. A build sorting by the inherited comparator would remap ordinals on
any index with 11 or more shards, and is invisible in any test with fewer.

Therefore:

| Source | Canonical order | Rationale |
|---|---|---|
| ES snapshot | numeric shard number | matches `IntStream.range(0, shardCount)` today |
| Solr backup | lexicographic | matches today's `comparingByKey()` / sorted `Path`s |
| New sources | any strict total order | no history to preserve |

Solr's lexicographic order yields `shard1, shard10, shard2`. Not a defect: nothing derives a Solr shard
from an ordinal — document reads go through `SolrShardPartition.shard`, the name — and changing it
would break in-flight Solr migrations for no gain.

The one real consequence is reporting. `DocumentMigrationBootstrap` logs `index={}, shard={}` from the
ordinal, so on a Solr collection with ten or more shards `shard=1` may be `shard10`. Phase 1 logs
`partition.name()` alongside it.

**Also:** stop `Partition` extending `Comparable<Partition>`. A throwing `compareTo` would only turn
accidental sorting into a runtime failure; removing the interface makes it a compile error. Safe today
— nothing holds partitions in a sorted collection, the snapshot caches are `HashMap`, and no
implementor overrides `compareTo`.

## Immutability

A deterministic comparator only guarantees the same order for the same *set*. A partition appearing or
disappearing mid-run shifts every ordinal after it — the disagreement case above, arriving through the
data rather than the code. Over a fixed set, any strict total order works, since uniqueness is all that
is required.

That freedom applies to **new** sources only. Existing ones must reproduce today's order while work
items are addressed by ordinal — hence the table above. Name-based work items would lift the
constraint.

The ES path already has an explicit freeze step: `CreateSnapshot`. The analogue for the failure stream
is closing the session. Sources over mutable storage must therefore be frozen *before* a run begins,
not merely hoped to be stable during it. Providers check this in `validate(spec, runtime)` against a
real artifact — for the failure stream, the completion manifest under "Session closure".

## Work-item identity: keeping ordinals

`WorkItem(indexName, Integer shardNumber, Long startingDocId)` and the
`base64url(indexName)__shardNumber__startingDocId` id format are **unchanged**. Existing coordination
indices keep working, no migration is required, and an in-flight migration can resume across the
upgrade.

The alternative — addressing partitions by name — is strictly more robust: ordering would stop being
correctness-critical, and partition sets could change between runs. It is deliberately deferred, for
two reasons. First, it changes the on-disk work-item id format, which turns a pure refactor into a
data migration. Second, it is cheap to do later: the source interfaces above are *identical* either
way, so the change is contained to the work-coordination layer (roughly four files), and coordination
indices are already namespaced per session, so a future format change can be scoped to newly created
indices rather than requiring dual-read.

**Trigger to revisit:** the first source whose partition set is not fixed at listing time. At that
point ordinals stop working and name-based identity is no longer optional. Until then, treat
`Partition.name()` as stable identity rather than a display string, so the later switch stays cheap.

## The plugging layer

**Where it lives.** Not in `RfsPipeline`: that module takes `:RfsCommon` only as `compileOnly`, under
an explicit "core IR types have ZERO dependency on RfsCommon" rule, so it cannot see
`VersionStrictness` or `Version`.

A new `:RfsSourceSpi` module on `RfsPipeline` + `RfsCommon` resolves both — `RfsCommon` declares
`api project(':transformation')`, so `Version` comes through, and `SnapshotReader` and `SolrReader`
already depend on both. `RootDocumentMigrationContext` stays out: it lives in `:RFS`, which declares
`api project(':RfsPipeline')`. No substitute is needed, since the only thing a provider uses is
`IRfsContexts.IDeltaStreamContext` — already in `RfsCommon`, and the type `LuceneSnapshotSource`
declares today. The SPI must not grow a shadow of the RFS tracing hierarchy.

```java
/** Declarative description of where documents come from. */
public record DocumentSourceSpec(
    String kind,                      // "es-snapshot" | "solr-backup" | "failed-document-stream"
    String uri,                       // file:// | s3:// | gs://
    Version version,                  // nullable for kinds not discriminated by version
    String name,                      // snapshot name / backup name / session id
    List<String> collectionAllowlist,
    Map<String, String> options       // kind-specific
) {}

/** Shared services a provider may need. Deliberately excludes anything target-shaped. */
public record SourceRuntime(
    Path scratchDir,
    Path workDir,
    String s3Region,
    URI endpoint,
    VersionStrictness versionStrictness,
    Supplier<IRfsContexts.IDeltaStreamContext> deltaStreamContextFactory,
    SourceRecordReporter recordReporter
) {}   // both non-null: supply no-op implementations, never null

/** Side channel for records a source read but could not convert. Must be thread-safe. */
public interface SourceRecordReporter extends AutoCloseable {
    Mono<Void> recordUnredrivable(UnredrivableRecord record);
    Mono<Void> flush();

    @Override
    default void close() throws Exception {}
}

/** Identity is objectKey + recordOrdinal; the key already embeds session=, index= and worker=. */
public record UnredrivableRecord(
    String collection, String partition, String objectKey, long recordOrdinal, String reason
) {}

public interface DocumentSourceProvider {
    String kind();

    /**
     * Reject a malformed or unusable source before construction. May do I/O — checking a
     * manifest or that the location exists is exactly its job. Cross-cutting invariants
     * that involve the sink do not belong here; see below.
     */
    default void validate(DocumentSourceSpec spec, SourceRuntime runtime) { }

    /** True if construction is expensive enough to skip when no work remains. */
    default boolean deferUntilWorkAvailable() { return false; }

    DocumentSource create(DocumentSourceSpec spec, SourceRuntime runtime) throws IOException;
}
```

Providers are discovered with `ServiceLoader`, so each reader module ships its own
`META-INF/services` entry and adding a source means adding a module rather than editing the core. The
application module applies no shade plugin, so service files merge safely. The repo already uses this
pattern for `IJsonTransformerProvider`.

The registry is a `Map<String, DocumentSourceProvider>` keyed by normalized `kind()` — trimmed,
lowercased with `Locale.ROOT`, and required to match `[a-z0-9-]+` — validated eagerly
at startup — source construction stays lazy. It rejects duplicate kinds, blank kinds, null providers
and initialization failures, naming the offending class; unknown-kind errors enumerate what is
registered.

Discovery needs an assembled-application test: a missing `META-INF/services` entry compiles and
unit-tests clean, then finds zero providers at runtime.

### Construction stays lazy

Source construction is deferred until inside the work coordinator's lifetime. The Solr path relies on
this to check whether the coordinator reports work already complete *before* any S3 setup, so a pod
restart after completion does not redo the bucket listing and metadata download. The registry
therefore replaces the `if/else` **inside** the existing source factory, not around it:

```java
MigrationSourceFactory sourceFactory = (wc, pm, cursor, cancelRef, timeProvider) -> {
    var provider = DocumentSourceRegistry.resolve(spec);
    if (provider.deferUntilWorkAvailable() && isCoordinatorWorkAlreadyDone(wc, context)) {
        throw new NoWorkLeftException("All work items already complete; skipping source setup.");
    }
    provider.validate(spec, runtime);
    var source = provider.create(spec, runtime);
    return prepareAndMigrate(source, wc, pm, targetClient, /* ... */);
};
```

`validate` runs exactly once, after the no-work check and before construction — it may do source I/O,
so running it earlier would defeat the optimization it sits behind.

The pre-check is opt-in per provider rather than universal: the ES path does not do it today, and
there the first run throws because the coordination index does not yet exist — behavior the Solr path
swallows.

## Conformance test

`RfsPipeline` already has `java-test-fixtures`. Ship an abstract `DocumentSourceContractTest` there;
every implementation extends it. It asserts:

1. `listPartitions` returns partitions already in `canonicalOrder()` — sorting is a no-op.
2. `canonicalOrder()` is a strict total order: no two distinct partitions compare equal.
3. Repeated `listPartitions` calls, and calls from a freshly constructed source, agree exactly.
4. `listCollections` is deterministic.
5. The additive law: read *n* documents from *s*, then resume from *s + n* — computed the way the
   pipeline computes it — and assert no unemitted document is omitted. Repeats allowed; exactness is
   not asserted, since the snapshot reader legitimately re-reads. Repeated subscription replays
   identically.
6. **A collection with at least 11 partitions**, since that is the only place numeric and
   lexicographic ordering diverge.
7. Re-reading a partition reports the same stable diagnostic identities, and a durable reporter
   deduplicating on them does not inflate its counts.

Item 6 is not optional. It is the specific regression that would otherwise ship silently.

## First new source: failed-document-stream redrive

The stream is already laid out as `session=<id>/index=<target>/worker=<id>/*.ndjson.gz`, which maps
onto the interfaces directly: collections are `index=` prefixes, partitions are `worker=` prefixes,
documents are records. Records deserialize back into `BulkOperationSpec` with the same
`ObjectMapper` — id, body, operation type, routing and `_type` all return typed, and unknown
properties are ignored, so it stays forward-compatible.

Because the stream stores the original pre-transform document rather than what was actually sent, the
existing sink re-applies whatever transform is configured at redrive time — so an operator who fixed
a broken transform gets the fix applied on redrive. Work coordination, retries, backoff, progress and
metrics all come along. Re-failures land back in the stream through the same path, so redriving a
redrive works with no extra machinery.

### What survives at the `Document` boundary

Deserializing a record into `BulkOperationSpec` proves the record round-trips, not that everything
survives the pipeline. `Document` is narrower, and `BulkOperationConverter` builds a fresh operation
from it, so anything not carried or regenerated is gone.

| Stored property | Redrive behavior |
|---|---|
| id, original body, index vs delete, `_type`, routing | carried in `Document` (id, source, operation, hints) |
| target index | carried as the collection name |
| `op_type`, `version`, `version_type`, `if_seq_no`, `if_primary_term`, `pipeline`, `require_alias` | regenerated by the transform |
| transformed body | not carried — the transform runs again |

**No IR extension is needed.** Nothing in the source→sink path originates the operation metadata in row
three; it can only come from a transform emitting it, and redrive re-runs the transform. What must be
carried rather than re-derived is exactly what `Document` already holds: operation type (delta mode
emits deletes), `_type` (source-derived when `emitDocType` is on), and routing.

The consequence is deliberate: a transform changed between the original run and the redrive produces
different operation metadata, just as it produces a different body — the point of redriving after a
fix, not a fidelity loss.

The provider checks one precondition in `validate(spec, runtime)`: the session has a completion manifest
(below). A stream still being written can gain a `worker=` prefix sorting before existing ones, shifting
every ordinal.

Two more belong at the composition layer, since a source cannot see the sink's configuration:

- The failure-stream output location must differ from the input, or the run reads and writes the same
  place and never terminates. Compare normalized bucket, prefix and session, not session name alone.
- The run needs its own `--session-name` so work coordination does not collide with the original
  backfill.

Source behavior, not a precondition: the `index=unknown-index/` bucket — failures emitted before the
target index was known — is excluded from `listCollections()` and reported as unredrivable.

### Unredrivable records

`requestItem` is nullable, and older or malformed records may not convert to a `Document`. The dividing
line is corruption versus content:

- **Corruption fails loudly** — unreadable gzip or NDJSON, or an unsupported manifest schema version.
  The latter fails in `validate`, before any work starts.
- **Valid but unredrivable records are skipped, counted, and reported** with object key and record
  ordinal: null `requestItem`, an unparseable operation, an unknown operation-type discriminator, a
  delete with no id, or an index operation with no body — deletes legitimately have none. Unknown
  *fields* are ignored, for forward compatibility.
- Null-id index operations follow the policy under "Decisions taken here".

Records are categorized four ways: redriven, failed again at the target, skipped as unredrivable, and
unreadable. **Phase 3 emits these as durable, categorized diagnostics and metrics; aggregating them into
a run state is #3066's `CompletedWithErrors` work.** Until that lands the run does not report all four,
and the console feature in Phase 4 depends on it.

Reporting needs a channel — `Flux<Document>` cannot carry a skip event. `SourceRuntime` carries a
`SourceRecordReporter` rather than widening the stream to a result type, which would ripple through
batching, `BatchResult` and `ProgressCursor` and turn Phase 1 into a much larger change. It mirrors the
failure-stream sink deliberately: reactive writes plus an explicit `flush()` the bootstrap gates on, so
a work item completes only once its reports are durable. A `void` method could not offer that — it
would either block inside the read or swallow asynchronous upload failures.

Flush twice: before each cursor advance, and after the stream completes, before the work item does.
Per-batch alone is insufficient — a partition whose records are all unredrivable emits no `Document`,
so no batch and no cursor ever fire, and trailing reports land after the last batch. A failed terminal
flush leaves the work item for a successor. (The failure-stream flush has no such hole; its records come
from bulk writes, so no batches means no records.)

The bootstrap owns the reporter, not `DocumentSource`: terminal flush, then close after the attempt.
Flush failure is fatal; close failure after a successful flush is logged, since the reports are already
durable. The no-op returns `Mono.empty()` throughout.

Reports duplicate, because under-advancing offsets cause records to be re-read. Deduplicate on write by
`objectKey` plus `recordOrdinal`, or skipped counts inflate on every retry.

These are record categories, not work-item states; `CompletionStatus` stays as it is.

### Session closure: a completion manifest

Closure needs a mechanism, not an assertion. One immutable manifest per session: schema version,
session id, `latestObjectTimestamp` (nullable — an empty session has none), sorted collections, sorted
partitions per collection, ordered object keys per partition. It is the latest key's timestamp, not the
migration's completion time. No checksums — corruption is a separate problem.

**The provider enumerates from the manifest, not from live listings.** That freezes the partition set,
gives deterministic object order for offset resume, and drops any dependence on store listing
semantics.

Written by the worker that exits on `NoWorkLeftException`; there is no in-process completion moment,
since workers do one shard and exit. Several may race, so the serialization must be canonical — sorted
collections, partitions and keys, fixed property order, no map iteration, defined encoding of empty and
optional values, and no value drawn from `now()`. Publish with `If-None-Match: *`; losers read back and
compare a digest of the canonical form, not raw bytes.

A console `seal` command covers aborted backfills, whose failures would otherwise be permanently
unredrivable. That is where operator judgement belongs — producing a manifest, not replacing one.

**A seal is permanent.** The manifest controls enumeration and ordering; a live listing is used only to
validate integrity, excluding the manifest object itself. A missing or extra object means the session
was not closed when sealed, and the run fails. There is no re-seal — a conditional publish cannot
replace an existing key, and rewriting one would silently remap ordinals. Correction means copying the
objects into a new source session and sealing that. A fresh `--session-name` is not sufficient; it
changes the coordination namespace, not the source manifest.

### Reading within a partition

A `worker=` prefix holds several rotated objects:

- **The manifest defines object order**; the reader never sorts. The writer produces that order
  lexicographically by key at seal time, which is a valid total order since keys are unique. `seq` is
  unpadded, so two rotations in one second land `-10` before `-2` — leave it; re-sorting
  chronologically would invalidate offsets already recorded against a sealed manifest.
- **Offset spans files** — a document ordinal over the concatenated object sequence, not per-file.
- **Duplicates are retained.** At-least-once flush can repeat a record; redrive writes are id-addressed
  upserts. No global dedup — it breaks offset semantics. Exception: records with a null id are not
  idempotent, since replaying one creates a second document.

## Sequencing

- **Phase 1** — introduce the interfaces, registry and conformance test; port the two existing sources.
  No functional or argument change (log output gains `partition.name()`). Independently reviewable and
  mergeable.
- **Phase 2** — expose `--source-kind` and the `--source-*` argument group, with inference from
  today's arguments as the default.
- **Phase 3** — add the failed-document-stream source, plus the manifest writer on the
  `NoWorkLeftException` path, the console `seal` command, and the durable `SourceRecordReporter`.
- **Phase 4** — wire up the console command and rewrite the acceptance criteria on #3065.
- **Follow-up, any time after Phase 1** — remove the uncoordinated path, leaving `migratePartition` as
  the pipeline's only entry point: `readCollectionMetadata`, `createCollection`,
  `migrateAll`/`migrateCollection`, and `partitionConcurrency` (dead once `migrateCollection` goes).
  Three end-to-end tests move to `migratePartition` with explicit index creation, and the duplicate Solr
  schema-to-mappings conversion retires — it survives via `SolrBackupIndexMetadataFactory`. Leave
  `CollectionMetadata` and `EsMetadataMigrationPipeline.migrateAll()` alone; both are still used.

Phase 4 must also correct #3065, whose premise predates two changes: the stored request is the
pre-transform document, so it is not replayable as sent, and there is no `dlq` command group — only
`failed-document-stream`.

## Open questions

- **Post-redrive reconciliation.** Source records are never deleted, so a redrive leaves the original
  session holding every failure plus a new session holding the ones that failed again — with nothing
  marking which are resolved. An operator who redrove everything still sees the original count. This
  hits #3063 (error summary), #3064 (counts in CR status) and #3066 (does a successful redrive clear
  `CompletedWithErrors`, and does a redrive with its own failures enter it?). Design it here, or as
  its own issue across those three?

## Decisions taken here

Recorded with rationale so they can be challenged rather than rediscovered.

- **All redrives use the coordinated path.** No size threshold, no lightweight in-process mode. One
  path means one behavior to test, document and support, and no threshold to tune or explain. The cost
  is accepted deliberately: recovering a handful of documents still means a coordination index, leases
  and a worker. This removes the last reason to keep the uncoordinated path — see the follow-up under
  "Sequencing".

- **Documents without ids — relax `Document.id` to nullable on the IR.** Omitting `_id` reproduces the
  original write; synthesizing fabricates identity. Valid for index ops only — a delete needs an id.
  This needs a setting distinct from `allowServerGeneratedIds`, which strips ids from *every*
  operation because serverless targets reject an explicit `_id`; reusing it for redrive would re-id the
  whole batch. Add a per-document rule — omit `_id` only where it is null — gated by its own flag.
  Policy: default skip-and-report; the flag is an explicit acknowledgement that a retry may duplicate,
  not routine config. Idempotence requires a stable id, and the manifest's frozen
  `(object key, record index)` yields one — available if a synthesized id is acceptable.

- **No partition-set token, for now.** It would need state alongside the coordination index — the
  format change deferred under "Work-item identity" — and it only works around ordinal addressing,
  which name-based work items would remove entirely. `validate()` plus the conformance test cover the
  known cases.

- **Placement of the failure-record types is an implementation detail.** `:RFS` already declares
  `api project(':RfsPipeline')`, so a reader module depending on `:RFS` creates no cycle. It would be
  the first to do so; if that asymmetry grates, move the two types down to `RfsCommon`. (Placement of
  the SPI itself is *not* a detail — see "The plugging layer.")
