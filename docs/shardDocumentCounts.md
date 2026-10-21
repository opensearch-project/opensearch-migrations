# Shard document counts

Document Backfill logs counts when a shard's source stream finishes:

```text
Partition migration stats: index=example, shard=0, shardSourceDocs=5,
shardLiveLuceneDocs=14, docsThisGeneration=3, docsPriorGenerations=2, ...
```

`shardLiveLuceneDocs` counts live Lucene documents, including nested children, in
the snapshot shard. For a full Elasticsearch/OpenSearch snapshot this is the
shard's contribution to `_cat/indices docs.count`. It is captured while the
reader is open, without a second document scan. Sources that cannot supply it,
including delta reads, report `unknown`. An empty shard reports `0`.

`shardSourceDocs` counts source documents processed across lease generations.
For a full snapshot with readable root documents, this is comparable to `_count`.
It counts documents before transformation and includes terminal target failures;
it does **not** prove that the target has the same number of documents.
Delta runs count changes, including deletions, rather than live documents.

`docsThisGeneration` and `docsPriorGenerations` split that source count at this
worker's starting checkpoint. The inherited count excludes the replayed source
document, so adding these two fields within a record is valid. Do not sum
`docsThisGeneration` across workers: their reads can overlap.

Counts remain `unknown` when resuming an older work item whose preceding count
was never saved. Restarting a full shard with these workers produces a known
total.

## S3 output

Setting `failedDocumentStreamS3Bucket` in `documentBackfillConfig` enables both
the failed document stream and shard-count output. Shard counts reuse its region
and endpoint. `shardDocCountS3Prefix` controls the count prefix and defaults to
`rfs-shard-doc-counts/`. The corresponding CLI arguments use kebab case, for
example `--shard-doc-count-s3-prefix`.

The session ID uses `--failed-document-stream-session-id`, then
`ARGO_WORKFLOW_UID`, then a worker-specific fallback. For CLI workers sharing
one migration, set the same session ID to group and deduplicate their records.

Records are gzip-compressed NDJSON under:

```text
<prefix>/session=<sessionId>/worker=<workerId>/shard-doc-counts-<timestamp>-<sequence>.ndjson.gz
```

Each record contains `sessionId`, `workerId`, `workItemId`, `indexName`,
`shardNumber`, `sourceDocCount`, `liveLuceneDocCount`, `docsThisGeneration`,
`docsPriorGenerations`, and `timestamp`. Unknown counts are JSON `null`; known
empty counts are `0`.

The worker flushes the record before completing its work item. Upload failure
leaves work eligible for retry. A worker can stop after upload but before
completion, so duplicate records are possible. Deduplicate within a migration
by `(sessionId, indexName, shardNumber)`; do not sum repeated records. All records
describe finished source reads, so there is no `shardComplete` flag.
