# Bounded requests and documents larger than a request

RFS can enforce a request body limit independently of the size of a supported
document. Ordinary operations are batched at operation boundaries. An optional
staging path assembles an oversized document inside OpenSearch, then indexes its
complete source through a small local `_reindex` request.

This is a hard body limit, not a promise of unlimited document size. The target's
document, mapping, script, memory, and parser limits still apply. A request also
needs enough room for its protocol envelope and action metadata.

## Existing behavior and the remaining gap

The starting point for this change is upstream `fb3fcde52`. The earlier exact-size
batching change, `bcc8e6eb0` / PR #3483, already encodes operations **after**
transformations and ID handling. `BulkNdjson.EncodedOperation` retains the actual
UTF-8 action/body bytes, including each newline, for batching and retries.
`OpenSearchClient.sendBulkRequests` executes split requests in order.

Its remaining exception was an individual oversized operation: `BatchSizePredicate`
put it in a singleton batch and the client sent it anyway. That exception made
the configured size a soft limit. RFS now either handles that singleton through
the explicitly enabled staging path or fails before sending it. The source
pipeline's batches still bound input buffering; only the final encoded operations
determine outgoing request boundaries.

The console's
[`migrate_document.py`](../../migrationConsole/cluster_tools/src/cluster_tools/tools/migrate_document.py)
demonstrates server-side assembly through scripted updates, but it is not a
strict size or general JSON solution:

* It splits Python strings by character count, not serialized UTF-8 bytes.
* It does not reserve space for the script, parameters, JSON escaping, or metadata.
* It handles top-level strings, not large arrays, nested structures, or field names.
* The initial document can remain oversized after all eligible strings are removed.
* Appending a retried chunk can duplicate data.
* The target sees partial documents; mappings and ingest pipelines can observe
  incomplete values. Version conditions and create/delete semantics need separate treatment.

## Configuration and compatibility

```text
--documents-size-per-bulk-request 10485760
--large-document-staging-index rfs-large-document-staging
```

The first option is now a hard bound even when the staging option is absent.
This intentionally changes the previous "send an oversized singleton" behavior.

The staging option is experimental and disabled by default. RFS creates or
validates the named concrete staging index. It must have:

* Root mappings disabled and complete `_source` storage.
* An `rfs_large_document_staging: 2` mapping metadata marker.
* No default or final ingest pipeline, no required routing, and request translog durability.

An existing unrelated index is rejected. The staging index remains after the
migration; successfully acknowledged upload documents are removed, while small
per-partition recovery journals remain. Keep the same dedicated staging index
through every worker restart and checkpoint replay. Use a dedicated
index with access controls appropriate for the migrated source contents.
The worker needs permission to create/read that index, write/update/delete its
documents, refresh it, run local reindex into the destination, and read task status.
Enabling staging serializes batches within each partition and partitions sharing
one sink, even if higher concurrency was requested. Independent RFS workers can
still process separate partitions.

| Operation or metadata | Behavior when oversized |
| --- | --- |
| Plain `index` with explicit `_id` | Supported through opt-in staging |
| Routing, destination index/alias, explicit pipeline, `require_alias` | Preserved |
| Default/final target ingest pipelines | Applied to the complete published document |
| `delete` | Remains a one-line bulk action; oversized metadata fails |
| `create`, arbitrary `update`/scripts | Not rewritten into index operations |
| External/internal version conditions, sequence number/primary term conditions | Rejected before staging |
| Server-generated IDs, legacy document types | Rejected before staging |
| Serverless and Elasticsearch targets | This implementation rejects enabling staging |

The implemented protocol is tested against OpenSearch 2.19.4 and 3.7.0. Tests do
not establish managed-service permission compatibility or production throughput.
Ingest pipelines must produce one indexed document; a dropped document is not
acknowledged as successful publication. Unlike ordinary bulk item handling,
staging/publication failures fail the batch even when a document exception
allowlist is configured.

## Request-size contract

The limit includes action metadata, document bytes, JSON escaping, and all bulk
newlines. HTTP headers, request lines, TLS framing, and response bodies are outside
this body-size setting.

The normal bulk path measures the final uncompressed bytes. When gzip is enabled,
it also measures the compressed representation. If compression would exceed the
limit, it sends the already fitting uncompressed representation. Otherwise the
transport receives the measured compressed bytes and does not compress them again.

Staging initialization, scripted updates, setup, and publication requests are
checked against the same limit. Staging uses uncompressed bodies. Update chunks
are filled according to the **serialized** JSON envelope, including the script
and changing sequence number. UTF-8 characters, JSON escapes, and surrogate pairs
are accounted for; strings are never split inside a surrogate pair. Metadata or a
script envelope that cannot fit causes failure, never an oversized fallback.

## Assembly and publication

1. Apply the entire configured RFS transformation chain.
2. Encode the resulting operation after ID handling. Retain those bytes.
3. For an oversized supported index operation, conditionally claim the partition's
   journal with a new upload ID and owner token. The journal also retains the
   transformed action metadata and a SHA-256 digest of the encoded document.
   Create its staging document with a create-only indexing request. A delayed
   initialization retry therefore cannot reset chunks already written.
4. Convert the encoded JSON body into container, field-name, string, number, and
   scalar events. An update script assembles these into a stack of unfinished
   containers in the staging document.
5. Send updates sequentially. Every request supplies its sequence number.
   Retrying the most recently applied sequence is a no-op; an unexpected sequence fails.
6. Refresh staging and conditionally record `publishing` in the journal before
   submitting asynchronous local `_reindex`, filtered to the upload ID. Its
   script verifies completion, unwraps the document, restores numbers, and sets
   the original transformed ID. Destination routing and pipeline settings are
   passed explicitly. Application and transport retries are both disabled for
   submission. `X-Opaque-Id` carries the upload ID for task identification.
7. Durably record the returned task ID, poll until the task is complete, and
   require a result proving exactly one document was created or updated,
   with no timeout, failures, conflicts, deletes, or no-ops.
8. Durably record the terminal publication state. Attempt to delete the successful
   upload, then complete the logical source batch.

The event stack avoids embedding a full JSON path in each HTTP request. Large
field names, large strings, and long nested paths therefore do not become
indivisible request parameters. Arrays can be assembled across requests without
ever presenting a partial vector to target mappings.

Numbers require additional care. Repeated update operations parse stored `_source`
into Maps; eagerly storing a high-precision decimal as a JSON number can round it
on the next update. Staging retains numeric literals as strings and records their
paths outside the user document. The final reindex script converts them to
`BigInteger`/`BigDecimal`, preserving negative floating-point zero. Numeric values
are therefore preserved until the normal destination indexing/ingest path handles
them. JSON whitespace, exponent spelling, and object key order are not promised.
Duplicate object keys are rejected by the staging parser rather than silently
collapsed during assembly.

The original source document is retained for failure reporting when the
transformation adapter can preserve its association with the resulting operation.
Internal chunk count does not inflate target document counts: one staged index
operation counts as one target upsert. Per-document HTTP attempts, retries, and
uncompressed body bytes are counted; shared staging-index setup is not charged
repeatedly to each document.

## Ordering, failure, and recovery

Staging requires ordered sink writes. `DocumentMigrationPipeline` forces one batch
in flight per partition and one partition at a time per sink. This differs from
`flatMapSequential` alone: ordered result emission does not order concurrent writes.
Within each source batch, all split requests complete sequentially.

The post-transformation bulk batcher also cuts before a repeated destination
index/document ID. An earlier retryable index cannot be compacted out and retried
after a later delete of that ID has already succeeded in the same bulk request.
Deletes remain one-line actions. A cursor cannot advance while a staged operation
in its source batch remains unacknowledged.

Assembly occurs entirely in staging. The target receives a full indexing
replacement, so an assembly failure or mapping rejection leaves the previous
target document intact. A successful replay replaces an existing partial target
document and removes its obsolete fields. This does not make the entire source
batch transactional or promise exactly-once execution of ingest processors.

Before reading any documents from a resumed partition, RFS reads its durable
journal. Its ID uses the collection and partition identity, including the snapshot
and shard for a Lucene source, rather than the checkpoint offset.

| Prior state | Recovery before any replayed target writes |
| --- | --- |
| No journal | Start normally |
| Partly or fully assembled, publication not claimed | Conditionally abandon the old owner; replay uses a new upload initialized from scratch |
| Publication has a durable task ID | Claim recovery ownership, wait for that task to terminate, record its result, then replay the source from its checkpoint |
| Publication claimed but no durable task ID | Stop; task submission may have committed or may still be in flight |
| Task status missing/unavailable | Stop; do not infer that the task failed or that retrying is safe |
| Terminal state durably recorded | Replay from the checkpoint; do not skip a source operation merely because an earlier attempt published it |

All journal transitions use sequence-number/primary-term conditions. A lost
journal-write acknowledgement is accepted only if a real-time read proves that
the exact owner/generation/state was stored. A stale uploader cannot claim
publication after a successor fences its journal. A successor also changes the
owner before recovering an existing task, so a predecessor cannot acknowledge
its delayed completion against the successor's journal.

Chunks carry sequence numbers. An identical retry of the most recently applied
chunk is a no-op, and an out-of-order sequence fails. Fresh replay uses a distinct
upload document: late chunks from an abandoned attempt cannot alter its replacement.
Replaying begins only after any known earlier publication has terminated, which
prevents that task from resurrecting a document after a replayed delete.

There is an unavoidable ambiguity with existing APIs between sending `_reindex`
and durably recording its task ID. An upload token is sent as `X-Opaque-Id` and
appears in task headers, but an absent task in a running-task listing does not prove
that no task was submitted or completed. RFS therefore **fails closed** in this
window. Identify the exact tagged task, then conditionally attach its task ID to
the retained journal while preserving its other fields. A fresh worker can then
perform the normal task-completion recovery. Do not clear the journal to bypass
this check. If the task cannot be identified, further operator reconciliation is
required; automatic recovery of that case needs target-side commit tokens.

Use this path for an exclusive backfill with a single active worker lease per
source partition, deterministic transformations, and independent destination
identities across partitions. The journal fences staged publication attempts;
it does not add target-side fencing to ordinary bulk operations or live writers.
Ingest processors must preserve document identity for the bulk dependency check
to apply. Transformations that collapse identities across workers, concurrent
applications/CDC, lease violations, changes to the staging index on restart, and
loss/deletion of acknowledged journal storage are outside this ordering contract.
The rejected version/sequence conditions cannot be replaced by client chunking.

Failures and cancellation retain unfinished uploads and journals. Successful
cleanup failure is logged without republishing. Keep journals and task records
until workers and publications have stopped and recovery is complete, then remove
retained uploads, completed task records, and the dedicated staging index.

## Failed-document records

A terminal oversized-operation failure emits **one logical document record**,
not one record per attempted chunk. Existing `requestItem`/`responseItem` fields
remain available. Additional fields preserve:

* `transformedRequestBody`: the exact full action/source NDJSON, including numeric
  spelling, routing, and the post-transformation destination and ID.
* `originalDocument`: pre-transformation JSON, using the original raw bytes when
  retained and otherwise the adapter's source object; it may be
  absent for synthesized operations or transformations that lose source association.
* `stagedFailure`: journal/upload/task identifiers, logical stage, chunk sequence
  when relevant, request method/path/body, response status/body, error message, and whether
  publication may have committed.

The dedicated `FailedRequestsLogger` carries the same full transformed request,
original source, and a JSON representation of the staged failure. A publication
mapping error retains the complete task failure response, including the failing
field, plus the publication envelope. A status-poll failure is identified as such;
it is not misreported as a failed document fragment.

An aborted staged batch will not reach the bootstrap's normal per-cursor failed
document flush. The client therefore awaits buffering **and durable flush** before
propagating this error. Flush failure also aborts the batch. A work-item replay
can emit another record for the same logical failure; delivery is at least once.
Chunk bodies are diagnostic, not independent replay instructions. Recover the
whole operation through the journal/checkpoint path, without applying an RFS
transformation twice. The full failure record itself is not restricted to the
OpenSearch request-body limit.

Staged failures keep the source batch unacknowledged, including non-retryable
mapping failures. Correct the cause before replay. They do not use ordinary bulk
compaction's skip-and-checkpoint behavior or an exception allowlist to discard an
incomplete logical operation.

## Limits and the long-term design

The staging path uses existing APIs and supports documents larger than both the
client cap and the server's HTTP content limit. It trades performance and
operational simplicity for that compatibility:

* Each update reads and rewrites the growing staging `_source`. With many small
  chunks, cumulative work can approach quadratic growth in document size.
  The 25 MiB scale test with 1 MiB requests hit the real parent circuit breaker
  on the default 1 GiB test node; its passing scale fixture uses a 2 GiB heap and keeps
  circuit breakers enabled. Small HTTP requests do not imply small heap usage.
* Numeric path records and temporary container state add storage and heap cost.
* Updates cap events per request to limit script work. Final numeric restoration
  still has work proportional to numeric path count/depth and can hit Painless
  loop limits.
* RFS still holds the encoded document in memory. Its configured JSON parser
  limits, the target's parser/depth limits, Lucene limits, mappings, and cluster
  memory remain real constraints. The raw encoder uses the same RFS parser
  limits as the transformed path.
* Local reindex and task-status permissions, script support, cleanup, and resolution of
  uncertain publication outcomes are prerequisites.

A scalable general solution is a target-side multipart indexing API, not
arbitrary splitting of existing bulk lines. Such an API should accept bounded
chunks into durable/spooled storage under an upload ID, verify offsets/lengths and
a content digest, then commit through the normal indexing path exactly once for
that upload token. Commit must preserve the original action, routing, explicit
ID, create/version/sequence conditions, and pipeline semantics. It should expose
queryable upload/commit state and expiration so a lost acknowledgement is
recoverable without issuing an ambiguous second publication. Transformation runs
before upload, while target ingest processing runs once at commit.

That requires target support (a native feature or compatible plugin). A client
cannot create such transactional semantics merely by changing the size of HTTP
chunks. Transfer-Encoding chunking still represents one HTTP request; gzip cannot
guarantee a fitting size for every document.

## Verification

`OpenSearchDocumentSinkBatchingTest`, `JsonDocumentChunksTest`, and
`StagedDocumentWriterTest` cover exact byte boundaries, the final newline, metadata,
UTF-8/escaping, surrogate pairs, transformation growth/shrinkage, one-to-many
transforms, conditional-operation rejection, gzip expansion, staging ownership,
and source checkpoint behavior.

`RfsBoundedRequestsEndToEndTest` creates a real OpenSearch snapshot and reads its
Lucene documents through RFS. It runs against real 2.19.4 and 3.7.0 targets with
`http.max_content_length=8192b`, behind an independent proxy that records and
rejects oversized compressed or uncompressed bodies. It first proves that a direct
large document receives HTTP 413.

The tests validate raw, native, and JSON transformation paths, exact stored
documents and routing, strings and field names larger than a request, nested
objects/arrays/nulls, a 4096-dimensional vector larger than a request, aliases,
explicit/default/final pipelines, mixed index/delete ordering, cleanup, publication
failure, and checkpoints held until publication. The proxy deliberately loses an
update response **after OpenSearch applies the chunk**, then verifies that the
retry does not duplicate content. Exact numeric assertions use arbitrary-precision
parsing so a rounded-double comparison cannot conceal loss of source data.

A separate scale case expands a two-byte source document to a 25 MiB string after
transformation, migrates it through a 1 MiB HTTP cap, and compares the entire stored
string including Unicode at both ends.

`RfsStagedRecoveryEndToEndTest` uses the production bootstrap, persists its emitted
checkpoint to disk, and constructs new clients, sources, document sinks, and S3
failure sinks to resume from that checkpoint. Both target versions run behind the
size-enforcing proxy. The failed-document stream uses the real AWS SDK uploader
against LocalStack S3; assertions retrieve and decompress the stored objects.
The test covers:

* A lost initialization ACK, several committed chunks, then a rejected later
  field/chunk; the old target stays intact and restart fully replaces it.
* A delayed stale worker's publication CAS, rejected by the real cluster after
  its successor completes a later delete.
* A real running reindex task held by unavailable replicas while the first worker
  loses task-status access; recovery waits before reading or replaying any source
  operations, then finishes in order after replicas are released.
* Submission that actually commits but loses its response; exactly one task is
  submitted, blind restart is blocked, and attaching the independently identified
  task ID allows checkpoint replay to complete.
* A real mapping failure in a later field, with full-document and failed-stage
  fidelity in both S3 and the legacy logger.
* A duplicate key detected during later chunk encoding, after earlier updates
  committed, with the complete raw document and an explicit client-side error
  retained in S3.
* Mixed successful, non-retryable, and retryable bulk items, followed by a dependent
  delete. Acknowledged unrelated documents are not retried.
* A withheld S3 upload completion, proving failure reporting waits for durability.

The checkpoint fixture exercises bootstrap cursor publication and restart offsets;
it does not simulate the work coordinator's lease-election algorithm or a full
cluster storage disaster.

```bash
./gradlew :RFS:test \
  --tests '*OpenSearchDocumentSinkBatchingTest' \
  --tests '*JsonDocumentChunksTest' \
  --tests '*StagedDocumentWriterTest'

./gradlew :DocumentsFromSnapshotMigration:isolatedTest \
  --tests '*RfsBoundedRequestsEndToEndTest' \
  --tests '*RfsStagedRecoveryEndToEndTest'
```

Validation on 2026-11-04: **287 selected tests passed**, with no failures or skips: 181 RFS unit tests, one RFS LocalStack integration test, 24 pipeline tests, nine HTTP tests, 67 migration argument/bootstrap tests, and five end-to-end invocations. The recovery suite also passed a subsequent run on both target versions with explicit destination-index and document-ID renaming before the injected failure. Java formatter checks passed for all four changed modules, as did `git diff --check`. These are targeted regression suites, not a run of every repository test.

## Primary references

* [OpenSearch Bulk API](https://docs.opensearch.org/latest/api-reference/document-apis/bulk/)
* [OpenSearch Update Document API](https://docs.opensearch.org/latest/api-reference/document-apis/update-document/)
* [OpenSearch Reindex API](https://docs.opensearch.org/latest/api-reference/document-apis/reindex/)
* [OpenSearch Get Task API](https://docs.opensearch.org/latest/api-reference/tasks/get-tasks/)
* [OpenSearch HTTP settings](https://docs.opensearch.org/latest/install-and-configure/configuring-opensearch/network-settings/)
