# Failed Document Stream

During a Reindex-from-Snapshot (RFS) backfill, most document errors are retried automatically. Failures that
are terminal — a document error, or an unexplained failure that kept happening to one document while the target
handled others — are written to a failed document stream, which is durably persisted to S3. From it you can see
exactly which documents did not reach the target, and why. Documents that eventually succeed are never written
here, and neither are documents held up by the target itself (throttling, unavailable shards, blocked writes,
refused connections): those wait until the target recovers. How each failure is classified, and the contract for
these records, are in [Bulk Traffic Control and Failure Handling in RFS](../RFS/docs/BULK_TRAFFIC_CONTROL.md).
Each work item's checkpoint also commits how many of its operations failed
([committed metrics](committedMetrics.md)).

The stream is at-least-once. An RFS work item is not marked complete until its failure records are durably
flushed, so a crash causes a successor to re-emit the same failures. The console de-duplicates on read by
`(targetIndex, documentId)`, so counts reflect *distinct* failed documents. `targetIndex` is the index the failed
operation was written to: the transformed operation's own index when a transform set one, otherwise the source
index's target.

Records are stored as gzip-compressed NDJSON under a per-backfill prefix.

```
s3://<bucket>/<prefix>/session=<SnapshotMigration-UID>/*.ndjson.gz
```

## Inspecting from the Migration Console

Run these from a Migration Console shell. When more than one `SnapshotMigration` exists, add
`--migration <name>` to choose one.

```
# S3 location for the current session
console failed-document-stream location

# Count of distinct failed documents
console failed-document-stream count

# List failures (columns: timestamp, targetIndex, documentId, failureClass, failureType)
console failed-document-stream list --limit 100

# Full records as JSON, including the original request and OpenSearch response
console --json failed-document-stream list --limit 100
```

A deep status check also appends a failed-document summary. The last two lines are the failed document
stream. Everything above them is the usual backfill status.

```
console backfill status --deep-check
```

```
BackfillStatus.STOPPED
Pods - Running: 0, Pending: 0, Desired: 0
Backfill status: Completed
Start time: 2026-07-21 14:02:11
Finished time: 2026-07-21 15:38:47
Percent completed: 100.0%
Estimated time to completion: N/A
Total shards: 40
Completed shards: 40
In progress shards: 0
Waiting shards: 0
failed document stream location: s3://my-bucket/rfs-failed-document-stream/session=abc-123/
Failed documents present: yes
```

The first line reports the worker *deployment* state, so a finished backfill shows `STOPPED` there (no
workers running) while `Backfill status:` below it reports *shard* progress as `Completed`. The second line
is deployment-specific: the example is a Kubernetes deployment, whereas on ECS it reads
`Running=0 Pending=0 Desired=0 Terminating=0`.

Status reports only *whether* failures exist, not how many: counting reads every record, which is
unbounded work on a backfill that failed a large number of documents. Use `console failed-document-stream
count` when you want the number.

If the stream is not configured, both failed-document lines are omitted. If it is configured but cannot be
read (for example, missing S3 permissions), the command fails rather than reporting a status it cannot
verify — a stream that cannot be read must not be reported as having no failures.

In JSON mode (`console --json backfill status --deep-check`) it adds `failed_document_stream_location` and
`failed_documents_present`. The command emits a single line; it is pretty-printed here for readability.

```json
{
  "status": "Completed",
  "percentage_completed": 100.0,
  "eta_ms": null,
  "started": "2026-07-21T14:02:11",
  "finished": "2026-07-21T15:38:47",
  "shard_total": 40,
  "shard_complete": 40,
  "shard_in_progress": 0,
  "shard_waiting": 0,
  "failed_document_stream_location": "s3://my-bucket/rfs-failed-document-stream/session=abc-123/",
  "failed_documents_present": true
}
```

Each record includes the target index, document id, the OpenSearch `failureType` (e.g.
`mapper_parsing_exception`), and the operation as it was sent (`target`: its `effectiveIndex`, `documentId`,
`operationType`, bulk `action` metadata and `body`). When the failed operation can be linked to its source document
by id, the record also has `source` (`documentId` and the original `body`), so it carries both the document before
and after any transformation. A transform that changes ids, or makes several operations with one id, leaves
`source` out. Together they are enough to diagnose or re-submit a failure without returning to the source
cluster.

## When documents can't be written

Documents that can't be written are recorded and the backfill moves on: their batch completes and the shard's
progress advances past them. A batch's records are durable before its progress is saved; if they can't be written
to S3, the progress isn't saved and the work is retried. With the stream disabled
(`--disable-failed-document-stream`), the same documents are classified and counted the same way and the backfill
moves on, but nothing records them: metrics and logs are all that remain.

| `failureClass`        | `failureType`                       | When                                                                                       |
|-----------------------|-------------------------------------|--------------------------------------------------------------------------------------------|
| `NON_RETRYABLE`       | an OpenSearch item error type       | The bulk item failed with an error that can't succeed on retry (e.g. `mapper_parsing_exception`): its type, or a type in its `caused_by` chain, is a known deterministic error. A value over the target's xcontent limits (`stream_constraints_exception`) is reported only as a cause, e.g. of a `parse_exception` from an ingest pipeline; `failureType` is the outer type. |
| `NON_RETRYABLE`       | `http_400`, `http_413`              | The target rejected a one-document bulk request outright. Larger requests rejected this way are split in half, repeatedly, to find the document responsible. |
| `NON_RETRYABLE`       | `document_too_large`                | The document body after transformation (or the original body when no transform is configured) is bigger than `maxDocumentSizeBytes`, so it was never sent. Action metadata and NDJSON separators are not counted. `responseItem` has its body `size`, the `limit`, and `measured: requestBody`. |
| `RETRYABLE_EXHAUSTED` | an OpenSearch item error type       | The item failed with an error that is neither a known document error nor about the target, 4 counted times. |
| `RETRYABLE_EXHAUSTED` | `http_<status>` (e.g. `http_500`)   | A one-document request got that status 4 counted times in a row. Larger requests are split in half to find the document. |
| `RETRYABLE_EXHAUSTED` | `no_response`                       | A one-document request timed out 6 counted times in a row. Larger requests are split in half to find the document. |

A failure is *counted* only when the worker's other recent traffic shows the target handling work (at most
`maxFailurePercentToFailDocuments` of its other requests failing, or, with no other requests, the target answering a
probe), so an outage or an overloaded target doesn't send documents to the stream. Throttles (`429`, rejected
execution, circuit breakers) and an unavailable target (unavailable shards, blocked writes, `503`, refused or reset
connections) are never recorded: those documents wait. For request-level failures, `responseItem` holds the status
and the start of the response body, or the transport exception.

## Deleting records

`backfill reset` archives the working state and, by default, preserves the failed document stream. To
also delete the records for the current session (irreversible), add `--include-failed-document-stream`.
Add `--yes` to skip the confirmation prompt.

```
console backfill reset --include-failed-document-stream
```

## Configuration

Every backfill must choose: set `failedDocumentStreamS3Bucket` to record documents that can't be written, or
set `disableFailedDocumentStream: true` to run without a stream. A `documentBackfillConfig` with neither (or
both) fails validation. There is no default bucket — a migration only writes failures to a bucket its own
config names. Without a stream, a failed migration leaves no inventory of which documents did not land.

> **Upgrading:** configs written before this requirement have neither field and are now rejected. Add one of
> them to every `documentBackfillConfig`. To keep the previous behavior, add
> `disableFailedDocumentStream: true`.

The destination is set under a migration's `documentBackfillConfig`:

```yaml
documentBackfillConfig:
  failedDocumentStreamS3Bucket: my-failed-documents-bucket
```

or, to opt out:

```yaml
documentBackfillConfig:
  disableFailedDocumentStream: true
```

Both are gated: changing either on a running migration needs `workflow approve change`, because switching
the stream mid-run leaves an inconsistent failure record.

| Option                              | Default                        | Description                                                    |
|-------------------------------------|--------------------------------|----------------------------------------------------------------|
| `failedDocumentStreamS3Bucket`      | none                           | Bucket for records. This or `disableFailedDocumentStream` is required. |
| `disableFailedDocumentStream`       | none                           | `true` runs without a stream. This or the bucket is required.  |
| `failedDocumentStreamS3Prefix`      | `rfs-failed-document-stream/`  | Key prefix. Each run writes under `<prefix>/session=<uid>/`.   |
| `failedDocumentStreamS3Region`      | resolved by config processor   | Region for the bucket. Ignored without a bucket.               |
| `failedDocumentStreamS3Endpoint`    | resolved by config processor   | Endpoint override (e.g. LocalStack). Ignored without a bucket. |
| `failedDocumentStreamMaxBufferBytes`| `67108864` (64 MiB)            | Max in-memory bytes per index before rotating to a new object. |

Running RFS directly, pass `--failed-document-stream-s3-bucket <bucket>` or `--disable-failed-document-stream`;
it refuses to start with neither or both.

The region and endpoint are still resolved for you when the bucket is set: the config processor takes your
value if given, else the snapshot repo's, else the deployment default, and records the result in run
history rather than leaving RFS to discover it at runtime.
