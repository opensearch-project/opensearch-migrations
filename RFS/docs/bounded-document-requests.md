# Bounded requests and oversized documents

RFS encodes and batches operations **after all transformations and ID handling**.
The existing bulk byte setting remains a batching target: a larger individual
operation is sent in a singleton request by default. An independent document
ceiling and experimental direct splitting are opt-in.

## Configuration

```text
--documents-size-per-bulk-request 10485760
--split-large-documents
```

`--documents-size-per-bulk-request` defaults to 10 MiB. With splitting disabled,
an oversized singleton retains its original operation semantics and compression
behavior. `--max-document-size-bytes` is a separate positive limit on one encoded
operation, including action metadata and newlines. Its default is `Long.MAX_VALUE`
(`9223372036854775807`). It applies before splitting and after transformations;
splitting never bypasses an explicit document ceiling. A batch of individually
allowed operations can exceed this per-document ceiling.

For example, a 10 MiB batching target and a 50 MiB document ceiling allow a 25 MiB
operation. By default it is sent alone. With `--split-large-documents`, supported
index operations use individual request bodies no larger than 10 MiB. Operations
above 50 MiB fail in either configuration. Setting both limits to the same value
rejects oversized operations without enabling splitting.

## Direct index and ordered updates

The splitter uses the destination document itself. It creates no staging index,
recovery journal, reindex task, or separate payload document.

1. Validate a plain `index` operation with an explicit ID, then resolve its target
   and pin the operation to one concrete index. Preserve explicit or alias routing.
2. Add or validate the reserved `_rfs_large_document` object mapping with
   `enabled: false`. Read the destination's concurrency metadata.
3. Send an initial **indexing replacement** containing a fresh attempt token and
   empty assembly state. Use `if_seq_no` and `if_primary_term` for an existing
   document, or `op_type=create` for an absent document. This removes obsolete
   fields from any previous complete or partial source.
4. Lazily generate a bounded part. Apply it with a scripted update conditional on
   the last acknowledged sequence number and primary term. Only after that part
   succeeds may the splitter generate the next part.
5. Send a final conditional update that verifies assembly is complete, restores
   numeric values, and replaces `_source` with the complete document. The temporary
   field is removed from the source. Its disabled mapping remains on the index.
6. Acknowledge the logical operation only after the final update succeeds. The
   source batch checkpoint still waits for every operation in that batch.

The temporary field is necessary for values that cannot be indexed piecemeal,
such as a fixed-dimension vector, an unfinished field name, or a numeric value.
It holds the assembly stack, text fragments, and numeric literals without exposing
these intermediate representations to user mappings. The completed document is
mapped by the final update. An oversized document containing the reserved top-level field is
rejected rather than silently changing that field's mapping semantics.

**Partial destination documents are visible.** During assembly, the target source
contains the temporary field rather than the previous document. Refreshing the
index can expose that intermediate document to searches. A failed final mapping
check leaves the temporary state in place. Whole-document replay replaces it and
starts over; it does not append to an old attempt. This protocol does not provide
atomic visibility across multiple requests or preserve the old source on failure.

## Supported semantics

Restrictions below apply to oversized operations when splitting is enabled.
Fitting operations continue through the normal bulk path.

| Operation or target feature | Direct splitting |
| --- | --- |
| Plain OpenSearch `index` with explicit ID | Supported |
| Explicit routing, write alias, `require_alias` | Preserved; requests use the resolved concrete index |
| `create`, delete, arbitrary update/script, generated ID | Oversized operation rejected |
| External/internal version or sequence conditions, legacy types | Rejected rather than rewritten |
| Explicit, default, or final ingest pipelines | Rejected; partial updates cannot preserve complete-document pipeline semantics |
| Disabled, filtered, or derived `_source` | Rejected; assembly requires complete stored source |
| Async translog durability | Rejected |
| Reserved assembly field with incompatible mapping | Rejected before document writes |
| Elasticsearch and OpenSearch Serverless targets | Enabling this experimental implementation is rejected |

The target index must already exist. The worker needs index metadata, mapping
update, document get/index, and scripted update permissions. No reindex or task
permissions are needed. Target mappings, settings, aliases, and index identity
must remain stable during a migration. Destination ingest processing differs
from RFS transformations: RFS transformations still run fully before splitting.

## Ordering, lost responses, and checkpoint replay

Connection reuse alone does not order server-side effects after a lost response.
RFS therefore combines sequential subscription with conditional writes:

* Splitting forces one batch in flight and one partition at a time per sink.
  Merely emitting concurrent batch results in order would not order their writes.
* The post-transformation batcher cuts before a repeated document ID, conservatively
  across index names as well. Different aliases may point to the same index.
  Retry compaction cannot then retry an earlier index after a later delete of
  that ID has already succeeded in the same request.
* Each part uses the previous acknowledged sequence number and primary term.
  Retrying the same condition cannot apply an append twice. A real-time get of
  only the attempt token and progress counter resolves a lost index/part response.
* An initial replacement is not blindly retried. Its lost response is accepted
  only if readback proves that this attempt initialized the document.
* Replay generates a fresh attempt and starts with an indexing replacement.
  A delayed part or completion from the abandoned attempt cannot modify that
  replacement because its concurrency condition is stale. An initial replacement
  conditional on a previously existing document is fenced in the same way.
* Final completion removes the progress token. A lost or malformed final response
  fails the batch and holds its checkpoint. Replay indexes the whole document
  again, whether the previous completion committed or left partial state.
* Mapping, encoding, unsupported-operation, and exhausted request failures keep
  the source batch unacknowledged. Exception allowlisting cannot discard one
  failed part and mark an incomplete document successful.

This is an exclusive backfill contract: one active owner per source partition,
deterministic transformations, and destination identities that do not collide
across independent workers or live writers. It does not provide exactly-once
indexing or target-side fencing for the ordinary bulk path.

There is also a specific missing-document limit: a create-only initialization
prevents resetting a **present** document, but cannot fence a delayed initial
create after another writer has created and then deleted that ID. If initialization
has an unknown outcome and readback cannot confirm its token, stop/quiesce that
attempt before allowing a successor to delete or recreate the ID. Arbitrarily
overlapping workers in that window require a durable target-side generation
protocol; connection ordering and a source checkpoint alone cannot supply it.
The tests do not establish safety for that overlap or for violated worker leases.

## Streaming and exact byte bounds

`OperationSplitter.split(encodedOperation, requestLimit)` returns a cold
`Flux<Part>`. The writer consumes it with zero prefetch: one generated part remains
live while its request and any retries complete. Cancellation or failure stops
further generation. Retried requests reuse their measured byte array.

The JSON cursor scans already validated UTF-8 bytes and copies escaped ranges
into request envelopes. It does not materialize an entire string or field name,
construct a document tree, collect all parts, or repeatedly serialize candidate
chunks. Additional live client memory is proportional to request size and nesting
depth. UTF-8 code points, JSON escapes, and escaped surrogate pairs stay intact.
The request envelope, script, parameters, and changing part number count toward
the limit. At most 512 events are emitted per part to bound script work.

Large strings, field names, nested arrays/objects, nulls, and vectors use the same
event stream. Duplicate keys are rejected during assembly. Numbers remain lexical
strings with recorded paths until final completion; repeated source parsing cannot
round them during assembly. The final script restores `BigInteger`/`BigDecimal`
and negative floating-point zero. JSON whitespace, exponent spelling, and key
order are not preserved.

Both byte settings count uncompressed encoded bytes, including metadata and
newlines. HTTP headers, request lines, TLS framing, and response bodies are outside
the body limit. Split requests are uncompressed. Ordinary fitting bulk requests
also check their compressed size; if gzip overhead would exceed the target, the
fitting uncompressed body is sent instead. Oversized singletons retain their
existing compression behavior when splitting is disabled.

The streaming boundary is **operation expansion**, not the entire existing RFS
pipeline. Source `Document` objects and encoded operations already retain source
bytes; JSON/Map transformations may materialize documents. Failure record and
logger APIs also materialize full diagnostic values. Those contracts would need
streams or spooled references to make source-to-DLQ processing fully streamed.

## Failure records and recovery

A terminal split failure produces one logical failed-document record, with:

* `transformedRequestBody`: the exact full transformed action/source NDJSON.
* `originalDocument`: original source JSON when the transformation adapter retains
  the association, including raw numeric spelling where raw bytes are available.
* `splitFailure`: attempt ID, stage, part sequence, request method/path/body,
  response status/body, error message, and whether final completion may have committed.

The dedicated `FailedRequestsLogger` carries the same full document and part
information. A final mapping rejection retains the engine response identifying
its failing field. Encoding failures have no transmitted request for that part.
The failed-part body is diagnostic, not an independent replay instruction.

RFS awaits both record acceptance and **durable flush** before propagating a split
failure: an aborted batch will never reach the normal per-checkpoint flush.
Persistence failure also aborts the batch. Delivery remains at least once, so
replaying a failing operation can produce another record. Repair the cause and
replay the complete source operation from its unacknowledged checkpoint; do not
apply an RFS transformation twice or retry an append in isolation.

## Verification and longer-term limits

The focused unit tests cover exact byte boundaries, transformation growth and
shrinkage, one-to-many transformations, the default singleton exception,
independent document ceilings, operation rejection, gzip expansion, streaming
demand, cancellation, and retained request bytes during retry. Allocation tests
require generating the first 8 KiB part of a 16 MiB string or field name without
allocating a copy of the whole value.

`RfsBoundedRequestsEndToEndTest` uses a real Lucene snapshot, all three RFS
transformation paths, and OpenSearch 2.19.4 and 3.7.0 behind an independent proxy
that limits both transmitted and decoded request bodies. The target also sets
its HTTP limit to 8 KiB. It tests full stored source equality, routing and aliases,
large fields/names/nested values/vectors, exact numeric values, visible partial
state, held checkpoints, malformed final responses, mixed index/delete ordering,
and a 25 MiB document through 1 MiB requests.

`RfsSplitRecoveryEndToEndTest` uses the production bootstrap, a persisted cursor,
fresh clients/sources/sinks on restart, and real gzip failure objects uploaded by
the AWS SDK to LocalStack S3. It tests a lost initial response, a later part failure,
DLQ durability, delayed conditional index/part/completion requests, lost final
completion, repair after a real later-field mapping failure, encoding failures,
duplicate keys, and mixed successful/permanent/retryable bulk item statuses.
The fixtures assert that no staging-index, reindex, or task requests occur.

Bounded HTTP requests do not mean unlimited document size or bounded server memory.
Every update reads and rewrites the growing source, so many small parts can incur
quadratic cumulative work. The 25 MiB scale fixture uses a 2 GiB target heap with
circuit breakers enabled. Parser, mapping, Lucene, script, and cluster memory limits
still apply. Numeric restoration and deeply nested paths can hit script loop limits.

A scalable general solution would need a target multipart indexing API or plugin
with durable chunk offsets, a generation token, and a queryable idempotent commit.
That would allow spooled assembly and one normal indexing/ingest pass, preserve
version/create semantics, and fence attempts even across deletion. The direct
client implementation cannot manufacture those guarantees from existing update
requests. HTTP transfer chunking remains one request; compression does not guarantee
that arbitrary document contents fit a request limit.
