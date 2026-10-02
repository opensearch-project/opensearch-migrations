# RFS succeeded / failed document counts (deduped for retries)

## Before this change
- `pipelineDocsMigrated` counts docs **sent**, so non-retryable failures count as migrated. It's only
  recorded when a shard succeeds, so leases that time out are never counted.
- There was no failed-doc metric. Failures only went to the S3 failed-document stream and to logs.

## Classifying docs
Each entry in the bulk response `items[]` is classified by `BulkResponseParser.partitionItems`:

| Item | Counted as |
|---|---|
| Has a `result`, or an allowlisted error | **succeeded** |
| Error type in `BulkDocErrorTypes.NON_RETRYABLE` (`mapper_parsing_exception`, `version_conflict_engine_exception` unless allowlisted, …) | **failed**, by `failureType` |
| Any other error, or an entry that can't be parsed | not counted; retried |

## Dedupe rules
1. **Bulk retries:** `compactPendingDocs` removes succeeded and failed items from the retry list, so each
   item is counted once.
2. **Retries running out:** these docs are *not* counted as failed. The batch errors, its cursor doesn't
   advance, and the next lease re-sends it.
3. **Lease retries:** counts are recorded only when a batch's `ProgressCursor` is emitted. Successors resume
   from that cursor.
4. **Console totals:** the counts are written in the same painless update that sets `completedAt`, which
   only the lease holder can do. That makes them exactly once.
   - The OTel counters can still double count if a worker crashes hard or the failed-doc-stream flush fails.

## Implementation
| Area | Change |
|---|---|
| `OpenSearchClient` + new `BulkItemOutcomes` | `sendBulkRequest(Raw)` overloads fill per-item outcomes. The old signatures are unchanged. |
| `BatchResult` / `ProgressCursor` | Add `docsSucceeded`, `docsFailed`, `failedByType`. The old constructors default to all-succeeded. |
| `OpenSearchDocumentSink` | Builds `BatchResult` from the outcomes. |
| `DocumentReindexContext` | New counters `pipelineDocsSucceeded` and `pipelineDocsFailed{failureType}`. `pipelineDocsMigrated` is unchanged. |
| `DocumentMigrationBootstrap` | Records the metrics per committed batch and accumulates `WorkItemDocCounts` onto `WorkItemCursor`. |
| `IWorkCoordinator` / `OpenSearchWorkCoordinator` / `ScopedWorkCoordinator` | Completion overloads persist `docsSucceeded`/`docsFailed` on the work-item doc. |
| Working-state mappings (ES 6.8 / OS 2.11) | `docsSucceeded` / `docsFailed` mapped as `long`. |
| `RfsMigrateDocuments` | Clean-shutdown and lease-timeout successor paths pass the cursor's counts. |
| `SCRIPT_VERSION` | **Not bumped**, because the params are optional and mixed versions stay compatible. |
| Dashboards (Helm, CDK, k8s copies) | New row: "Succeeded / Failed" and "Failed by Failure Type". |
| `metric_operations.py` | `pipelineDocsSucceeded` added as a candidate. |
| `backfill_rfs.py` / `BackfillOverallStatus` | Sum aggregation over completed items gives `docs_succeeded` / `docs_failed`, in both text and `--json` output. |

## Dashboards
The "Reindex-From-Snapshot Workers" section of the RFS CloudWatch dashboard gets a new row at y=34. The CPU
and Memory widgets below it move down to y=42. The change is identical in all three copies:
- `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/files/cloudwatch-dashboards/reindex-from-snapshot-dashboard.json` (Helm)
- `deployment/cdk/opensearch-service-migration/lib/components/reindex-from-snapshot-dashboard.json` (CDK)
- `deployment/k8s/dashboards/reindex-from-snapshot-dashboard.json`

| Widget | Metrics (Sum, 60s, `qualifier=MA_QUALIFIER`, `OTelLib=documentMigration`) |
|---|---|
| **RFS Documents Succeeded / Failed** (left, 12×8) | `pipelineDocsSucceeded` (green), plus `SUM(SEARCH('{OpenSearchMigrations,OTelLib,qualifier,failureType} MetricName="pipelineDocsFailed" …'))` (red) |
| **RFS Failed Documents by Failure Type** (right, 12×8) | The same `SEARCH` without `SUM`: one line per `failureType`, labelled `${PROP('Dim.failureType')}` |

`pipelineDocsFailed` carries a `failureType` dimension, so a `SEARCH` expression is needed to aggregate across
failure types. Using `SEARCH` also means new failure types show up without editing the dashboard.

## Tests
- **Java unit tests:**
  - `OpenSearchClientTest`: outcomes for all succeeded, retries, non-retryable by type, allowlisted, retries running out, and the raw path
  - `OpenSearchDocumentSinkTest`
  - `DocumentMigrationBootstrapDocCountsTest`: metrics and cursor counts, and a failed batch records nothing
- **Java testcontainers:** `WorkCoordinatorTest.testCompletionPersistsDocCounts`.
- **Python:** `test_backfill.py::TestDocCounts`, `test_backfill_rfs_queries.py` (container-backed), `test_cli.py`, and `test_metric_operations.py`.

## Open questions
1. Should allowlisted errors count as success (current behavior), or get a separate "skipped" count?
2. `version_conflict_engine_exception` counts as failed unless allowlisted. A re-run without the allowlist
   will report every existing doc as failed.
