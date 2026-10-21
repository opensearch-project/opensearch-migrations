# Validate backfills with committed metrics

Use the snapshot RFS migrated as the validation baseline when the live source
keeps ingesting. Each coordinator work-item document stores `committed_metrics`.
The existing Migration Console archive preserves the complete document,
including these metrics, in its existing
`backfill_working_state/working_state_backup_<timestamp>.json` file. A later
reporting or S3 publication job can consume that archive.

## Commit semantics

The watermark covers consecutive completed source batches after failed-document
records flush. If a one-document batch finishes while the next three-document
batch is pending, only the first document contributes. Later batches that finish
early remain outside the watermark.

At a lease handoff or shard completion, RFS writes the watermark and its metrics
in the same coordinator update. Completion cannot succeed without persisting
the metrics. Successors resume at `nextPosition`, so committed ranges
`[fromPosition, nextPosition)` do not overlap. Recovery preserves frozen metrics;
abandoned attempts do not add another committed range.

## Metric scope and aggregation

| Group | Published fields | Meaning and aggregation |
| --- | --- | --- |
| `metrics.source` | `upserts`, `deletes`, `bytes` | Root-document operations and body bytes before transformations. Sum across committed ranges. |
| `metrics.target` | `upserts`, `deletes`, `bytes` | Output operations and body bytes after transformations, counted once before retries. Sum across committed ranges. |
| `metrics.requests` | `batches`, `total`, `retries`, `bytes`, `durationMillis` | Logical batches and their HTTP request costs. Sum across committed ranges. |
| `metrics.workItem` | `durationMillis` | Acquired attempt's elapsed time through preparation of its first checkpoint/completion commit. Sum for committed worker time. |
| `metrics.snapshot` | `liveDocuments`, `nestedDocuments`, `tombstonedDocuments` | Whole-shard Lucene counts. Present only on the final work item; take once per shard, then sum by index. |

Source/target deletes contribute zero body bytes. Request bytes are uncompressed
NDJSON, including action metadata and retries. `requests.total` includes retries;
`retries` counts requests after each logical batch's first request. Fully
filtered batches still count as batches but send no HTTP request.

A transformation that doubles four source documents records `source.upserts=4`
and `target.upserts=8`; one that filters half records 4 and 2. These count
operations, not successful target inserts: IDs may be replaced and terminal
document failures may be handled through the failed-document stream. The target
metrics describe output originating from this source work item, even if a
transformation routes it to another index. Request costs exclude abandoned
work-item attempts. Retries are not error counts because a bulk request can
partially succeed.

Both durations use monotonic nanoseconds internally and publish milliseconds.
Request duration sums HTTP attempts, excluding retry delays; concurrent requests
can overlap, so their summed duration can exceed work-item duration. Work-item
duration includes shard preparation, processing, retry delays and failure-stream
flushes. It freezes before the first commit request so retries preserve identical
metrics, and excludes coordinator commit latency and subsequent recovery.

For lease analysis, group completed records by snapshot/index/shard and inspect
the sum and maximum work-item duration, the number of items, and their completion
flags. Summed duration includes repeated preparation in committed attempts, but
excludes abandoned attempts and gaps between workers; it is not the shard's full
wall-clock migration span. Checkpointed durations are limited by existing leases,
so they do not by themselves predict the lease needed to finish a shard.

Divide summed source/target body bytes by the corresponding upserts for average
document size. Divide summed request bytes/duration by summed total requests for
request averages. A zero denominator gives `null`. Never sum averages or
percentiles. A later maximum-duration metric should use maximum aggregation;
configuration values such as lease duration or concurrency remain context for
each item or run. A cumulative shard/run total must be selected once at that
scope, rather than added for every work item.

`metrics.snapshot` is `null` on intermediate checkpoints or when unavailable.
Known zero counts are reported as `0`. `liveDocuments` includes live nested
children; `nestedDocuments` counts those children, so their difference is the
live root count. `tombstonedDocuments` counts retained non-live Lucene slots,
including hard and soft deletions.
This is a snapshot baseline, independent of the worker's resume position.
An unavailable count on any shard makes the index baseline unknown.
Per-item counters describe emitted root-document operations, not physical Lucene
slots. Lucene deleted documents can include replaced versions and disappear on
merge; they are not interchangeable with delta delete operations.

## Work-item examples

These are the `committed_metrics` values inside coordinator `_source` documents,
alongside existing lease/completion fields. The document ID remains the
serialized work-item string. The metrics payload uses `schemaVersion: 2`; this
is separate from the coordinator checkpoint `scriptVersion: "3.1"`.

Full snapshot: five root documents with nine nested children give a whole-shard
live Lucene count of 14, plus two tombstoned slots.

```json
{
  "schemaVersion": 2,
  "workItemId": "b3JkZXJz__0__0",
  "snapshotName": "full-001",
  "indexName": "orders",
  "shardNumber": 0,
  "fromPosition": 0,
  "nextPosition": 16,
  "shardComplete": true,
  "previousSnapshotName": null,
  "deltaMode": null,
  "metrics": {
    "source": {"upserts": 5, "deletes": 0, "bytes": 500},
    "target": {"upserts": 5, "deletes": 0, "bytes": 500},
    "requests": {"batches": 2, "total": 3, "retries": 1, "bytes": 1500, "durationMillis": 90},
    "workItem": {"durationMillis": 120000},
    "snapshot": {"liveDocuments": 14, "nestedDocuments": 9, "tombstonedDocuments": 2}
  }
}
```

Delta snapshot: three source upserts and one source delete give a root-document
change of 2 when both operations are enabled and all source documents are
readable. The current snapshot contains 16 live Lucene documents. The previous
snapshot name identifies the diff; a previous document count is unnecessary for
validating the current snapshot baseline.

```json
{
  "schemaVersion": 2,
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
    "target": {"upserts": 3, "deletes": 1, "bytes": 300},
    "requests": {"batches": 2, "total": 3, "retries": 1, "bytes": 900, "durationMillis": 90},
    "workItem": {"durationMillis": 90000},
    "snapshot": {"liveDocuments": 16, "nestedDocuments": 9, "tombstonedDocuments": 4}
  }
}
```

## Target validation and extension

Sum the final `metrics.snapshot.liveDocuments` once per source shard to
obtain the snapshot's expected `_cat/indices docs.count`, including nested
children. Finish the migration, review failures, and refresh the target before
comparing. A full backfill assumes an initially empty target; a delta assumes
the target represented the previous snapshot and both updates and deletes were
applied. Preserve IDs, index routing, nested mappings and cardinality, and
account for independent target writes. Matching counts alone do not verify
content. Source upserts minus deletes describes root-document change only in
`UPDATES_AND_DELETES` mode with readable source documents.

For future metrics, define their scope and aggregation rule first. Collect
additive operation/request metrics before the ordered watermark advances, and
freeze work-item observations when preparing the commit. Cover transformation,
retry and resume behavior in tests. Version incompatible payload changes.
Existing coordinator archives provide the data for future aggregation.

Checkpoint semantics use coordinator script version `3.1`. Start a fresh
coordinator index when upgrading older workers; all workers in a run must use
the same version.
