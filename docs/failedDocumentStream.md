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
Failed document count: 4
```

The first line reports the worker *deployment* state, so a finished backfill shows `STOPPED` there (no
workers running) while `Backfill status:` below it reports *shard* progress as `Completed`. The second line
is deployment-specific: the example is a Kubernetes deployment, whereas on ECS it reads
`Running=0 Pending=0 Desired=0 Terminating=0`.

If the stream is not configured, both failed-document lines are omitted. If the count cannot be read (for
example, missing S3 permissions), it renders as `Failed document count: unavailable`.

In JSON mode (`console --json backfill status --deep-check`) it adds `failed_document_stream_location` and
`failed_document_count` (the latter is `null` if currently unavailable). The command emits a single line.
It is pretty-printed here for readability.

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
  "failed_document_count": 4
}
```

Each record includes the target index, document id, the OpenSearch `failureType` (e.g.
`mapper_parsing_exception`), and the original document (`requestItem`) — enough to diagnose or re-submit a
failure without returning to the source cluster.

## Deleting records

`backfill reset` archives the working state and, by default, preserves the failed document stream. To
also delete the records for the current session (irreversible), add `--include-failed-document-stream`.
Add `--yes` to skip the confirmation prompt.

```
console backfill reset --include-failed-document-stream
```

## Configuration

The failed document stream is enabled by default. Set `failedDocumentStreamEnabled: false` to turn it off.
Disabling records nothing at all, so a failed migration leaves no inventory of which documents did not
land.

When you omit `failedDocumentStreamS3Bucket`, it falls back to the deployment's default S3 bucket (from
`migrations-default-s3-config`). The destination is set under a migration's `documentBackfillConfig`, where
all fields are optional.

| Option                              | Default                        | Description                                                    |
|-------------------------------------|--------------------------------|----------------------------------------------------------------|
| `failedDocumentStreamEnabled`       | `true`                         | Set to `false` to turn the stream off.                         |
| `failedDocumentStreamS3Bucket`      | deployment default bucket      | Bucket for records. Set to use a separate one.                 |
| `failedDocumentStreamS3Prefix`      | `rfs-failed-document-stream/`  | Key prefix. Each run writes under `<prefix>/session=<uid>/`.   |
| `failedDocumentStreamS3Region`      | resolved by config processor   | Region for the bucket.                                         |
| `failedDocumentStreamS3Endpoint`    | resolved by config processor   | Endpoint override (e.g. LocalStack).                           |
| `failedDocumentStreamMaxBufferBytes`| `67108864` (64 MiB)            | Max in-memory bytes per index before rotating to a new object. |
