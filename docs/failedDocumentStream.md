# Failed Document Stream

During a Reindex-from-Snapshot (RFS) backfill, most document errors are retried automatically. Failures that
are terminal — non-retryable, or retried until the limit was exhausted — are written to a failed document
stream, which is durably persisted to S3. From it you can see exactly which documents did not reach the
target, and why. Documents that eventually succeed are never written here.

The stream is at-least-once. An RFS work item is not marked complete until its failure records are durably
flushed, so a crash causes a successor to re-emit the same failures. The console de-duplicates on read by
`(targetIndex, documentId)`, so counts reflect *distinct* failed documents.

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
`mapper_parsing_exception`), and the original document (`requestItem`) — enough to diagnose or re-submit a
failure without returning to the source cluster.

## When documents can't be written

With the stream configured, documents that can't be written are recorded and the backfill moves on: their
batch completes and the shard's progress advances past them. Without the stream, there is nowhere to keep
them, so the work item fails and a successor retries it from the last checkpoint.

| `failureClass`        | `failureType`                       | When                                                                                       |
|-----------------------|-------------------------------------|--------------------------------------------------------------------------------------------|
| `NON_RETRYABLE`       | an OpenSearch item error type       | The bulk item failed with an error that can't succeed on retry (e.g. `mapper_parsing_exception`). |
| `NON_RETRYABLE`       | `http_400`, `http_413`              | The target rejected a one-document bulk request outright. Larger requests rejected this way are split in half, repeatedly, to find the document responsible. |
| `NON_RETRYABLE`       | `document_too_large`                | The document body after transformation (or the original body when no transform is configured) is bigger than `maxDocumentSizeBytes`, so it was never sent. Action metadata and NDJSON separators are not counted. `responseItem` has its body `size`, the `limit`, and `measured: requestBody`. |
| `RETRYABLE_EXHAUSTED` | an OpenSearch item error type       | The bulk item failed on every retry.                                                       |
| `RETRYABLE_EXHAUSTED` | `http_<status>` (e.g. `http_429`)   | The whole request failed with that status on every retry.                                  |
| `RETRYABLE_EXHAUSTED` | `no_response`                       | No response arrived (timeout, connection reset). A larger request that gets no response twice in a row is split in half, repeatedly; a single document gives up after four. |

For request-level failures, `responseItem` holds the status and the start of the response body, or the
transport exception. A request-level failure is only recorded when the target accepted some other bulk request
during the same batch (an earlier part of it, or a concurrent batch). If the target accepted nothing, it is
treated as unavailable and the work item fails instead, so an outage doesn't send every in-flight document to
the stream.

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
