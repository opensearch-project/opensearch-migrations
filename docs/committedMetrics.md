# Validate backfills with committed metrics

Use the snapshot RFS migrated as the validation baseline when the live source
keeps ingesting. RFS stores `committed_metrics` in each coordinator work-item
document. Migration Console archives those documents and writes a companion
report with index totals. Workers need no metrics storage configuration.

## What commits

The watermark covers consecutive completed source batches, after their
failed-document records have flushed. If a one-document batch finishes while
the next three-document batch is pending, only the first document contributes.
Later batches that finish early remain outside the watermark.

At a lease handoff or shard completion, RFS writes the watermark and its metrics
in the same coordinator update. Completion cannot succeed without persisting
the metrics. Successors resume at `nextPosition`, so committed ranges
`[fromPosition, nextPosition)` do not overlap. Recovery preserves frozen metrics;
abandoned attempts do not add another committed range.

| Field | Meaning |
| --- | --- |
| `metrics.source.upserts` | Source root-document indexing operations handled before transformations |
| `metrics.source.deletes` | Source root-document delete operations handled |
| `metrics.source.bytes` | Source body bytes; deletes contribute zero |
| `metrics.requests.batches` | Completed logical sink batches, including fully filtered batches |
| `metrics.requests.total` | HTTP bulk requests for those batches, including retries |
| `metrics.requests.retries` | Additional HTTP requests after each batch's first request |
| `metrics.requests.bytes` | Uncompressed NDJSON bytes across those HTTP requests |
| `metrics.requests.durationMillis` | Summed HTTP request durations, excluding retry delays |

Timing uses monotonic nanoseconds within a batch, then converts its total to
milliseconds before adding it to work-item totals. The conversion discards less
than one millisecond per batch. Milliseconds leave ample room for aggregation.

These are committed processing metrics, not successful target insert counts.
Transformations can filter or split documents, indexing can replace IDs, and
terminal document failures can be handled through the failed-document stream.
Retries are not an error count: a bulk response can partially succeed. Request
costs exclude abandoned work-item attempts, so they do not measure all traffic.

## Work-item examples

The following objects are the `committed_metrics` field inside coordinator
`_source` documents. Existing lease and completion fields remain alongside it.
The coordinator document ID is still the serialized work-item string.
`metrics.snapshot` holds whole-shard counts only on the final work item; it is
`null` on intermediate checkpoints. Source and request totals cover each
work item's committed range.

A full backfill with five root documents and nine nested children:

```json
{
  "schemaVersion": 1,
  "workItemId": "b3JkZXJz__0__0",
  "snapshotName": "full-001",
  "indexName": "orders",
  "shardNumber": 0,
  "fromPosition": 0,
  "nextPosition": 14,
  "shardComplete": true,
  "previousSnapshotName": null,
  "deltaMode": null,
  "metrics": {
    "source": {"upserts": 5, "deletes": 0, "bytes": 500},
    "requests": {"batches": 2, "total": 3, "retries": 1, "bytes": 1500, "durationMillis": 90},
    "snapshot": {"liveLuceneDocCount": 14, "previousLiveLuceneDocCount": null}
  }
}
```

A subsequent delta with three additions and one removal in the source diff:

```json
{
  "schemaVersion": 1,
  "workItemId": "b3JkZXJz__0__0",
  "snapshotName": "delta-002",
  "indexName": "orders",
  "shardNumber": 0,
  "fromPosition": 0,
  "nextPosition": 4,
  "shardComplete": true,
  "previousSnapshotName": "full-001",
  "deltaMode": "UPDATES_AND_DELETES",
  "metrics": {
    "source": {"upserts": 3, "deletes": 1, "bytes": 300},
    "requests": {"batches": 2, "total": 3, "retries": 1, "bytes": 900, "durationMillis": 90},
    "snapshot": {"liveLuceneDocCount": 16, "previousLiveLuceneDocCount": 14}
  }
}
```

## Archive and aggregate

When Migration Console archives a stopped run, it saves the full coordinator
index and a metrics report before deleting the index:

```text
backfill_working_state/
  working_state_backup_20261021120000.json
  working_state_backup_20261021120000.metrics.json
```

These files use the existing archive directory: `SHARED_LOGS_DIR_PATH` when
configured, otherwise the local working directory. The raw archive is an array
of batches, each mapping work-item IDs to their complete `_source` documents.
An archival/storage job can upload both artifacts to S3. This feature adds no
worker S3 writes or automatic S3 uploader.

The report has `schemaVersion`, `complete`, and `indices`. Each index entry
identifies the current/previous snapshot and delta mode and the shard count.
Its `metrics` object contains:

- Summed `source` and `requests` fields, and `snapshot` counts summed once
  from each shard's final work item.
- `averageSourceDocumentBytes`, `averageRequestBytes`, and
  `averageRequestDurationMillis`, derived from summed numerators and denominators.
- `snapshotLiveLuceneDelta` (current minus previous), and
  `sourceRootDocumentDelta` (upserts minus deletes in `UPDATES_AND_DELETES` mode).

For the delta example, these averages are 100 bytes, 300 bytes, and 30 ms;
both count deltas are 2. Empty denominators and unavailable counts remain `null`.
An empty shard has count `0`. Unknown counts never become zero.

Incomplete or mixed-version archives get `complete: false` and a `reason`
instead of totals. Legacy archives without committed metrics remain unchanged.
Writing either artifact must succeed before the coordinator index is deleted.
An incomplete migration can still be archived for inspection or reset.

To regenerate a report from one run's complete archive, with an independent
check on the number of migrated source shards:

```sh
python -m console_link.models.committed_metrics working_state_backup_20261021120000.json --expected-shards 2
```

The aggregator rejects incomplete work items, conflicting duplicate records,
gaps, overlaps, and missing shard completion. Supply every coordinator document
from one run; `--expected-shards` additionally detects wholly missing shards.
Search responses and NDJSON hits are accepted, but all search pages are required.

## Interpret counts

Sum `metrics.snapshot.liveLuceneDocCount` once per source shard for the snapshot's expected
`_cat/indices docs.count`, including nested children. Delta records also retain
the previous snapshot count. Their difference includes changes to nested
children; source upserts minus deletes counts root-document changes when both
operations are enabled and all source documents are readable. An updated or
moved document contributes both a removal and an addition.

For target validation, finish the migration, review failures, and refresh the
target. A full backfill assumes an initially empty target; a delta assumes the
target represents the previous snapshot and both updates and deletes were
applied. Preserve IDs, nested mappings, and document cardinality through
transformations, and account for independent target writes or deletes.
Matching counts alone do not establish content equality.

## Extend the metrics

Add additive counters to the metrics model and collect them before the ordered
watermark advances. Include zero defaults, aggregation, and retry/resume tests.
Derive averages from sums rather than summing averages. Version incompatible
schema changes. Archival/storage integrations can consume the coordinator
payload without adding worker-side exporters.

Checkpoint semantics use coordinator script version `3.1`. Start a fresh
coordinator index when upgrading from older workers; all workers in a run must
use the same version.
