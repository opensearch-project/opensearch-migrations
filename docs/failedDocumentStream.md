# Failed Document Stream

During a Reindex-from-Snapshot (RFS) backfill, most document errors are retried automatically. Failures that
are terminal — non-retryable, or retried until the limit was exhausted — are written to a durable
failed document stream in S3 so you can see exactly which documents did not reach the target, and why.
Documents that eventually succeed are never written here.

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

A deep status check also appends a failed-document summary.

```
console backfill status --deep-check
```

In JSON mode (`console --json backfill status --deep-check`) it adds `failed_document_stream_location` and
`failed_document_count` (the latter is `null` if currently unavailable).

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

The failed document stream is enabled by default. When you omit `failedDocumentStreamS3Bucket`, it falls
back to the deployment's default S3 bucket (from `migrations-default-s3-config`). AWS deployments always
provision that default bucket, so the stream is effectively always on there. It is disabled only when no
bucket resolves at all, which happens on a non-AWS deployment that provisions no default bucket and where
no `failedDocumentStreamS3Bucket` was set. In that case the console commands report that it is not
configured. The destination is set under a migration's `documentBackfillConfig`, where all fields are
optional.

| Option                              | Default                        | Description                                                    |
|-------------------------------------|--------------------------------|----------------------------------------------------------------|
| `failedDocumentStreamS3Bucket`      | deployment default bucket      | Bucket for records; set to use a separate one.                 |
| `failedDocumentStreamS3Prefix`      | `rfs-failed-document-stream/`  | Key prefix; each run writes under `<prefix>/session=<uid>/`.   |
| `failedDocumentStreamS3Region`      | resolved by config processor   | Region for the bucket.                                         |
| `failedDocumentStreamS3Endpoint`    | resolved by config processor   | Endpoint override (e.g. LocalStack).                           |
| `failedDocumentStreamMaxBufferBytes`| `67108864` (64 MiB)            | Max in-memory bytes per index before rotating to a new object. |
