# RFS succeeded / failed document counts (deduped for retries)

Issue #3380: report, for a backfill, how many documents were indexed successfully and how many failed, with
each document counted once no matter how many times it's retried.

## Before this change
- `pipelineDocsMigrated` counts docs **sent**, so non-retryable failures count as migrated. It's only
  recorded when a shard succeeds, so leases that time out are never counted.
- There was no failed-doc metric. Failures only went to the S3 failed-document stream and to logs.

## Where users see the counts

| Surface | Shows | Source |
|---|---|---|
| `workflow status`, `workflow status --resource-view`, `workflow manage` | `docs succeeded N, docs failed M` | SnapshotMigration `status.documentBackfill.summary.docsSucceeded/docsFailed`, written by the RFS monitor |
| `console failed-document-stream count --by failureType` (also `failureClass`, `targetIndex`; supports `--json`) | Failed docs per type | S3 failed-document stream (requires `failedDocumentStreamS3Bucket`) |
| CloudWatch dashboard `MA-<stage>-<region>-ReindexFromSnapshot` (EKS) | Succeeded / failed over time, failed by type | OTel `pipelineDocsSucceeded`, `pipelineDocsFailed{failureType}` |
| Prometheus (non-AWS deployments) | Same metrics | otel-collector Prometheus exporter |
| Worker logs | `Partition migration stats: … succeeded=N, failed=M` per work item | `DocumentMigrationBootstrap` |

`console backfill status --deep-check` also returns `docs_succeeded` / `docs_failed`, but in the workflow
deployment the `backfill` command group is disabled in the console pod, so only the RFS monitor runs it.

## Classifying docs
Each entry in the bulk response `items[]` is classified by `BulkResponseParser.classifyParsed`:

| Item | Counted as |
|---|---|
| Has a `result`, or an allowlisted error | **succeeded** |
| Error type in `BulkDocErrorTypes.NON_RETRYABLE` (9 fixed types, e.g. `mapper_parsing_exception`, `version_conflict_engine_exception` unless allowlisted) | **failed**, by `failureType` |
| Any other error, or an entry that can't be parsed | not counted; retried |

Only `NON_RETRYABLE` types can create a `failureType` key, so the metric dimension and the per-batch map are
bounded at 9 values (plus a defensive `unknown`).

## Dedupe rules
1. **Bulk retries:** `OpenSearchClient.compactPendingDocs` counts an item at the moment it removes it from the
   retry list. Later attempts don't include it, so it's counted once.
2. **Retries running out:** these docs are *not* counted as failed. The batch errors, its cursor doesn't
   advance, and the next lease re-sends it. They appear in the failed-document stream as
   `failureClass=RETRYABLE_EXHAUSTED` and increment `pipelineErrors`.
3. **Lease retries:** metrics are recorded only for a batch whose `ProgressCursor` is emitted (after its
   failed-doc-stream flush). Successors resume from that cursor.
4. **Persisted totals:** the counts are written in the same painless update that sets `completedAt`, which
   only the lease holder can make. Each work item covers only its own doc range, so summing across parent and
   successor items is exact.

## Data flow
```
_bulk response → BulkResponseParser → OpenSearchClient (BulkItemOutcomes, per request across retries)
  → OpenSearchDocumentSink (BatchResult) → DocumentMigrationPipeline (ProgressCursor, ordered)
  → DocumentMigrationBootstrap (after S3 flush)
      ├─ OTel pipelineDocsSucceeded / pipelineDocsFailed{failureType} → CloudWatch / Prometheus
      └─ WorkItemCursor{checkpoint, cumulative WorkItemDocCounts}
           → completeWorkItem / createSuccessorWorkItemsAndMarkComplete (normal end, lease timeout, SIGTERM)
           → .migrations_working_state docsSucceeded / docsFailed
           → console backfill status --deep-check (RFS monitor) → SnapshotMigration status → workflow status
```

## Implementation
| Area | Change |
|---|---|
| `OpenSearchClient` + new `BulkItemOutcomes` | `sendBulkRequest(Raw)` overloads take a `BulkItemOutcomes`. Successes are counted on a clean response and in `compactPendingDocs`; non-retryable failures are counted by type in `compactPendingDocs`. The raw path counts `pendingRawDocs` before lazy conversion. Old signatures are unchanged. |
| `BatchResult` / `ProgressCursor` | Add `docsSucceeded`, `docsFailed`, `failedByType`. The old constructors default to all-succeeded. `docsInBatch` still drives the resume offset. |
| `OpenSearchDocumentSink` | Builds `BatchResult` from the outcomes after the bulk Mono completes (`Mono.fromCallable`). |
| `IDocumentMigrationContexts` / `DocumentMigrationContexts` | New counters `pipelineDocsSucceeded` and `pipelineDocsFailed` (attribute `failureType`). `pipelineDocsMigrated` is unchanged. |
| `DocumentMigrationBootstrap` | Records the metrics per committed batch and accumulates `WorkItemDocCounts` onto `WorkItemCursor`. Adds succeeded/failed to the "Partition migration stats" log line. |
| `WorkItemDocCounts` (new), `WorkItemCursor` | Cumulative counts travel with the checkpoint in one immutable object. |
| `IWorkCoordinator` / `OpenSearchWorkCoordinator` / `ScopedWorkCoordinator` | `default` completion overloads take `WorkItemDocCounts`; the painless completion script sets `docsSucceeded`/`docsFailed` alongside `completedAt` when the params are present. |
| Working-state mappings (ES 6.8 / OS 2.11) | `docsSucceeded` / `docsFailed` mapped as `long` (new indices). |
| `RfsMigrateDocuments` | Clean-shutdown and lease-timeout successor paths pass the cursor's counts. |
| `SCRIPT_VERSION` | **Not bumped**: the params are optional, so old and new workers can share a working-state index. |
| `backfill_rfs.py` / `BackfillOverallStatus` | A `sum` aggregation over completed work items (excluding `shard_setup`) gives `docs_succeeded` / `docs_failed`, in text and `--json`. |
| `rfsMonitorCronJob.sh`, `applyRfsMonitorCronJob.sh` | The monitor copies `docsSucceeded` / `docsFailed` into `status.documentBackfill.summary` (`null` when unknown). No CRD change: the status schema preserves unknown fields. |
| `workflow/tree_utils.py`, `workflow/resource_tree.py` | `workflow status` / `manage` render the counts with exact integer formatting; hidden when `null`. |
| `middleware/failed_document_stream.py`, `cli.py` | `count_by()` and `failed-document-stream count --by`; `count` honors `--json` (`{"count": N}`). |
| Dashboards | See below. |
| `metric_operations.py` | `pipelineDocsSucceeded` added to the integ-test metric candidates. |

## Dashboards
The "Reindex-From-Snapshot Workers" section of the RFS CloudWatch dashboard gets a new row at y=34. The CPU
and Memory widgets below it move down to y=42. The change is identical in all three copies:
- `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/files/cloudwatch-dashboards/reindex-from-snapshot-dashboard.json` (Helm, EKS)
- `deployment/cdk/opensearch-service-migration/lib/components/reindex-from-snapshot-dashboard.json` (CDK, ECS)
- `deployment/k8s/dashboards/reindex-from-snapshot-dashboard.json` (no deploy path consumes this copy)

| Widget | Metrics (Sum, 60s, `qualifier=MA_QUALIFIER`, `OTelLib=documentMigration`) |
|---|---|
| **RFS Documents Succeeded / Failed** (left, 12×8) | `pipelineDocsSucceeded` (green), plus `SUM(SEARCH('{OpenSearchMigrations,OTelLib,qualifier,failureType} MetricName="pipelineDocsFailed" …'))` (red) |
| **RFS Failed Documents by Failure Type** (right, 12×8) | The same `SEARCH` without `SUM`: one line per `failureType`, labelled `${PROP('Dim.failureType')}` |

`pipelineDocsFailed` carries a `failureType` dimension, so a `SEARCH` expression is needed to aggregate across
failure types. Using `SEARCH` also means new failure types show up without editing the dashboard.

## Known gaps
1. **Persisted totals cover completed work items only**, so they lag a running backfill by up to one work item
   per worker.
2. **OTel metrics during a worker SIGTERM:** the OTel SDK shutdown hook and the clean-shutdown hook run
   concurrently, and the clean-shutdown path doesn't cancel the pipeline before reading the cursor. A batch in
   flight can be committed without its metric being exported, or exported and then re-sent by the successor.
   The dashboard can be off by up to `maxConnections` batches per SIGTERM (seen on EKS: −10 docs). The
   persisted totals are unaffected. A fix exists but was deferred.
3. **OTel metrics on a hard crash** (`kill -9`, OOM): batches committed in the killed lease are re-sent by the
   next lease, so the dashboard can over-count. The persisted totals are unaffected.
4. **Per-type counts aren't persisted** in the working state; they're only in the metrics and the
   failed-document stream.
5. **Server-generated IDs:** the same missing cancel on SIGTERM can re-send an in-flight batch, which creates
   duplicate documents when the target assigns IDs. Not reproduced in a test.

## Validation
On EKS (stage with ES 7.10 source and OS 2.19 target, in-cluster test clusters):

| Run | Data / faults | Result |
|---|---|---|
| Mixed | 30,000 docs: 26,000 ok, 2,500 `mapper_parsing`, 1,500 `strict_dynamic_mapping` | Worker logs, CloudWatch and target count exact |
| Robust | 154,300 docs over 6 shards, 5 failure types incl. `illegal_argument` and `routing_missing`; 3 workers | Console, failed-doc stream and CloudWatch exact |
| Chaos | 334,300 docs; 63s write block (bulk retries), 3 lease successors, worker force-delete | Console and CloudWatch exact |
| Crash after commit | Worker force-deleted 40s into a 301k-doc shard | Console exact; CloudWatch −10 (gap 2) |
| Console | Backfill-only run from an existing snapshot | `failed-document-stream count --by failureType` returned the expected breakdown |

## Tests
- **Java unit:** `OpenSearchClientTest` (all succeed, retries, non-retryable by type, allowlisted, retries
  exhausted, raw path), `OpenSearchDocumentSinkTest`, `DocumentMigrationBootstrapDocCountsTest` (per-batch
  metrics and cursor counts; a failed batch records nothing).
- **Java testcontainers:** `WorkCoordinatorTest.testCompletionPersistsDocCounts` across ES 5.6 to OS 3.7.
- **Python:** `test_backfill.py::TestDocCounts`, `test_backfill_rfs_queries.py` (container-backed),
  `test_backfill_doc_counts_format.py`, `test_failed_document_stream.py` (`count_by`), `test_cli.py`,
  `test_metric_operations.py`.
