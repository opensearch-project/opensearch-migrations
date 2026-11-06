# RFS 3.4.0 vs adaptive flow control on full-capacity targets

Status: complete. Both migrations finished on 2026-11-03, and both target
indices were subsequently validated at exactly 24,000,000,000 documents
across all 400 primary shards.

## Conclusion

RFS 3.4.0 completed this well-provisioned-target run faster, while the
adaptive image exposed the target to far fewer failed bulk attempts:

- RFS 3.4.0 completed in 6h02m24s; adaptive completed in 6h30m24s, exactly
  28 minutes later;
- 3.4.0 averaged 1.104M accepted target documents/s versus adaptive's
  1.025M/s, a 7.7% advantage relative to adaptive;
- at the instant 3.4.0 completed, adaptive had migrated approximately
  23.185B documents, or 96.60% of the snapshot;
- both targets finished with exactly 24B documents, 400/400 completed shards,
  green health, all 24 data nodes and 3 dedicated masters, and no failed
  shards;
- neither run emitted a failed-document object, exhausted its retry budget,
  logged an actual JVM out-of-memory error, lost a target node, or produced a
  target write-thread-pool or indexing-pressure rejection counter; but
- 3.4.0 logged 49,141 retry attempts receiving `Too Many Requests` and 9,259
  receiving HTTP 502. Adaptive logged 1,603 and 95 respectively: 30.7 times
  fewer 429 attempts and 97.5 times fewer 502 attempts.

The result is therefore not simply “both clients were unthrottled.” The large
targets absorbed both migrations without correctness loss, but still returned
transient pushback. Fixed-60 RFS 3.4.0 continued offering enough work to
finish 7.7% faster, at the cost of much more rejected and replayed traffic.
Adaptive converted that feedback into lower concurrency and a measurable
throughput cost. In this run, the safety margin did not change the final
correctness outcome because the old client recovered from every transient
response.

## Matched setup

The runs used the same snapshot and EKS environment but separate, identically
configured Amazon OpenSearch Service domains. They ran concurrently, so
neither client wrote to the other's target.

| Dimension | RFS 3.4.0 | Adaptive image |
|---|---|---|
| Target domain | `eop09-full-340-v2` | `eop09-full-adaptive-v2` |
| Target-writing window | 05:55:18–11:57:42 UTC | 05:55:20–12:25:44 UTC |
| Image | Public RFS 3.4.0 release | Runtime from commit `0709a1b5d` |
| Image digest | `sha256:f2743156ed55f1d1b83a20b3b377f6ac052bdbe918c0edeff0ec18ba0593c923` | `sha256:39eadc3609cb30a8f3a5d80938542357498962f8d8c5b21f576e09918b2418a9` |
| Snapshot/index | `large-snapshot` / `pilot_index`, 24B documents | Same |
| Primary shards | 400 | 400 |
| RFS workers | 24 | 24 |
| Worker resources | 3300m CPU, 7000 MiB memory, 200 GiB ephemeral storage | Same |
| Maximum connections per worker | Fixed 60 | Adaptive allowance, ceiling 60 |
| Bulk limits | 10 MiB / 20,000 documents | Same |
| Initial work-item lease | 2 hours | Same |
| Maximum shard size | 80 GiB | Same |
| Target data nodes | 24 x `r6g.8xlarge.search` | Same |
| Dedicated masters | 3 x `r6g.large.search` | Same |
| Availability Zones | 3 | 3 |
| Storage per data node | 1024 GiB gp3, 40K IOPS, 1187 MiB/s | Same |
| Target replicas | 0 | 0 |
| Failed-document stream | Enabled, separate S3 prefix | Enabled, separate S3 prefix |

The adaptive configuration also included its supported 310-second bulk
response timeout, one-day maximum lease, and terminal-failure threshold. Those
newer fields were removed from the 3.4.0 payload because its parser does not
recognize them. No test-code change was made for this one-off comparison.

## Completion results

| Measure | RFS 3.4.0, fixed 60 | Adaptive 0709, max 60 | Difference |
|---|---:|---:|---:|
| Completion time | 6h02m24s | 6h30m24s | 3.4.0 28m faster |
| Average accepted target-document rate | 1,103,753/s | 1,024,590/s | 3.4.0 +79,163/s (+7.7%) |
| Documents when 3.4.0 finished | 24,000,000,000 | approximately 23,184,996,785 | 3.4.0 +815,003,215 |
| Final target documents | 24,000,000,000 | 24,000,000,000 | Equal |
| Completed primary shards | 400/400 | 400/400 | Equal |
| P95 / maximum hottest-node write queue | 66.2 / 83 | 66.0 / 87 | Similar |
| P95 / maximum hottest-node CPU | 98% / 99% | 97% / 98% | Similar |
| P95 / maximum hottest-node JVM heap | 88.4% / 94% | 82% / 92% | Adaptive lower |
| Minimum free storage on the fullest data node | 720.3 GiB | 718.7 GiB | Both ample |
| Client retry-attempt warnings: 429 | 49,141 | 1,603 | 3.4.0 30.7x higher |
| Client retry-attempt warnings: 502 | 9,259 | 95 | 3.4.0 97.5x higher |
| Target write-rejection counter | 0 | 0 | Equal |
| Target indexing-pressure rejection counter | 0 | 0 | Equal |
| Retry-exhaustion signals | 0 | 0 | Equal |
| Actual JVM OOM signals | 0 | 0 | Equal |
| Failed-document S3 objects | 0 | 0 | Equal |
| Ready workers before 95% completion | 24 | 24 | Equal |

Target documents, health, node counts, queues, CPU, heap and storage come from
the direct observer. Completion and shard counts come from the
`SnapshotMigration` resources. Final document counts were independently
queried after refresh and returned 400 successful shards with no failures.

The target-side CloudWatch counters did not record the client-visible 429 and
502 responses. The report therefore treats the explicit client warning as the
evidence for those retryable attempts rather than incorrectly calling the
runs pressure-free.

## Progress milestones

The 3.4.0 lead accumulated through most of the migration rather than appearing
only in the final shard tail.

| Snapshot progress | RFS 3.4.0 elapsed | Adaptive elapsed | Adaptive delay |
|---|---:|---:|---:|
| 25% | 1h33m54s | 1h40m47s | 6m53s |
| 50% | 2h58m26s | 3h12m32s | 14m06s |
| 75% | 4h23m11s | 4h44m00s | 20m49s |
| 90% | 5h13m52s | 5h37m43s | 23m51s |
| 95% | 5h33m50s | 5h56m22s | 22m33s |
| 99% | 5h53m04s | 6h18m10s | 25m06s |
| 100% | 6h02m24s | 6h30m24s | 28m00s |

Milestones are linearly interpolated between the direct observer's
approximately 80–90-second target-count samples. The exact 100% timestamps
come from the migration status.

## Retry and safety interpretation

This target shape had enough headroom to prevent the constrained-run failure
mode:

- there was no `Retries exhausted` message;
- no worker reported `Partition migration failed`;
- no actual `java.lang.OutOfMemoryError` was logged;
- no failed-document file was created;
- no target node disappeared or entered a write block; and
- both final target counts exactly equal the source snapshot count.

Nevertheless, the fixed-60 client generated substantial retry work. The
49,141 and 9,259 counts above include only the 3.4.0 messages that identify a
specific failed bulk attempt. Another 58,400 3.4.0 warnings said the
plain-text response could not be parsed for item partitioning; those were
excluded to avoid double-counting the same responses.

Adaptive recognized the status directly, reduced its per-process allowance,
and occasionally split a 502 batch. Its much lower retry count demonstrates
that the controller was active even though the AOS rejection counters
remained zero. Late-run flow summaries show individual workers operating at
allowances in roughly the 3–16 range despite the configured ceiling of 60.

This produces a clearer tradeoff than the constrained tests:

- on the constrained targets, adaptive reduced failure and often improved
  accepted throughput by avoiding wasted overload;
- on these 24-node targets, the old client recovered from all pushback and
  achieved higher accepted throughput;
- adaptive remained quieter and used less heap, but its safety behavior cost
  approximately 28 minutes without an old-client terminal failure to offset
  that cost in this run.

## Completion-time worker churn

The observer recorded restart-counter maxima of 127 for 3.4.0 and 157 for
adaptive before their worker deployments were removed. This is not a count of
lost shards or failed documents.

- 3.4.0's first observed restart occurred at approximately 5h44m32s and
  97.47% target progress.
- Adaptive's first occurred at approximately 6h04m35s and 97.15% progress.
- Kubernetes then recorded `BackOff restarting failed container` events while
  fewer shards remained.
- Retained logs include “No work left to acquire” / “No work item found”
  terminal paths, but do not preserve a precise cause for every restart.
- Neither fleet showed restart activity before 95%, retry exhaustion, OOM, a
  failed-document object, or a missing final document.

The restart storm therefore appears to be a separate completion/drain
lifecycle defect, not evidence that either target was overwhelmed. It should
still be fixed: workers that have no work remaining should not crash-loop
while the coordinator is completing the migration.

## Workflow status caveat

Both Argo workflows are labeled `Failed`, but their only failed workflow node
is the initial `initializeRunMetadata` bookkeeping step, which referenced a
missing optional `MigrationRun` resource. That early error did not block the
metadata or document migrations.

The authoritative migration resources both report:

- phase `Completed`;
- document backfill `Completed`;
- 400 of 400 shards migrated; and
- 100% completion.

The final target counts independently confirm those completion statuses.

## Limitations

- This compares complete images, not only the adaptive controller. The
  adaptive image also contains deferred allocation of bulk buffers until a
  request slot is available.
- Both clients ran in the same EKS environment. They wrote to separate target
  domains, but EKS Auto Mode moved/replaced worker pods across several compute
  nodes during the run. All 24 workers remained ready through 95%, yet source
  compute placement was not perfectly static.
- The retry counts are failed bulk-attempt warning records, not unique
  documents or target-side counter values. A request can appear again after a
  retry.
- The direct target observer sampled each lane approximately every 80–90
  seconds and can miss shorter queue, CPU or heap spikes.
- The initially observed adaptive count just below 24B was a refresh-timing
  artifact. A subsequent direct `_count` returned exactly 24B across all 400
  shards.
- Separate but identically configured AOS domains avoid client contention,
  but ordinary host-placement and service variability remain possible.

## Dashboards

The isolated CloudWatch dashboards for the run are:

- `MA-eop09-full-340-v2-ReindexFromSnapshot`
- `MA-eop09-full-adaptive-v2-ReindexFromSnapshot`

For a common visual window, use 2026-11-03 05:55–12:27 UTC. The adaptive
dashboard includes the new controller metrics; the 3.4.0 dashboard correctly
leaves those newer series absent.
