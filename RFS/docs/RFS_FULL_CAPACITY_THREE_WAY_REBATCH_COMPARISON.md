# RFS full-capacity whole-snapshot comparison

Status: complete through the same-target RFS 3.4.0 rerun on 2026-11-05. This
report compares every retained run that migrated the entire
24-billion-document, 400-primary-shard snapshot on the 24-node full-capacity
target shape.

The filename is retained so existing links continue to work. The earlier
three-run checkpoint evidence is preserved in the final section, but incomplete
runs are no longer mixed into the primary comparison.

All timestamps shown beside commit IDs are Git committer timestamps in UTC.

## Conclusion

Five runs have whole-snapshot completion evidence:

| Run | Completion | Accepted docs/s |
|---|---:|---:|
| RFS 3.4.0 original | **6h02m24s** | **1,103,753/s** |
| Adaptive 0709 | 6h30m24s | 1,024,590/s |
| Instrumented 87ac | 7h39m42s | 870,133/s |
| Bounded streaming | 7h44m35s | 860,987/s |
| RFS 3.4.0 rerun | **6h54m33s** | **964,902/s** |

The direction is unambiguous across the complete migrations: no newer image
recovered the RFS 3.4.0 throughput.

The latest adaptive run took **1h42m11s (28.20%) longer than RFS 3.4.0** and averaged
**22.00% fewer accepted documents/s**. It took **1h14m11s (19.00%) longer
than adaptive 0709** and averaged **15.97% fewer documents/s**.

The latest adaptive change is much closer to the immediately preceding
complete run:
bounded streaming was only **4m53s (1.06%) longer than instrumented 87ac**.
It trailed that run by about one minute through 99% and lost another 3m47s in
the final shard tail. A single 1.06% difference is small enough to include
ordinary run-to-run AOS and shard-tail variation; it is not proof that the
four commits after `87ac259cb` (2026-11-03 20:42:42 UTC) caused a new material
regression. It is proof
that they did not recover the much larger loss already present by
`87ac259cb` (2026-11-03 20:42:42 UTC).

The same-target RFS 3.4.0 rerun narrows the comparison while preserving the
same direction. It was **50m02s faster** than bounded streaming and accepted
documents **12.07% faster**, but it was also **52m09s slower** than the first
RFS 3.4.0 run. The old image therefore has material environment/run
variability, yet it still outperformed the latest adaptive image when both
were run alone against the same reused target domain.

All five migrations completed 400/400 primary shards without a failed-document
record, retry exhaustion, an actual JVM out-of-memory error, a lost target
node, or a target write rejection. The performance loss therefore cannot be
explained as work discarded through terminal failures.

## Runs included

Short headers and values keep the five-run matrix narrow enough to scan.

| Dimension | 3.4 original | Adaptive 0709 | Instrumented | Bounded | 3.4 rerun |
|---|---|---|---|---|---|
| Migration label | 3.4 lane | Adaptive lane | `instrumented-v4` | `matcheddocs-v5` | `full340v6` |
| Window (UTC) | 11-03 05:55–11:58 | 11-03 05:55–12:26 | 11-03 20:58–11-04 04:38 | 11-04 16:05–23:50 | 11-05 02:01–08:56 |
| Image | RFS 3.4.0 | `0709a1b5d` | `87ac259cb` | `e90a33f5d` | RFS 3.4.0 |
| Snapshot | 24B / 400 shards | Same | Same | Same | Same |
| Workers | 24 | 24 | 24 | 24 | 24 |
| Worker resources | 3.3 CPU / 7 GiB | Same | Same | Same | Same |
| Connections | Fixed 60 | Adaptive ≤60 | Adaptive ≤60 | Adaptive ≤60 | Fixed 60 |
| Bulk limits | 10 MiB / 20K | Same | Same | 14 MiB / 11,320 | 10 MiB / 20K |
| Transform | None | None | None | None | None |
| Failed stream | On | On | On | On | On |
| Instrumentation | None | None | Metrics | Metrics | None |
| EKS load | Shared | Shared | Alone | Alone | Alone |
| Target | `340-v2` | `adaptive-v2` | `adaptive-v2` | `adaptive-v2` | `adaptive-v2` |

The full target names are `eop09-full-340-v2` and
`eop09-full-adaptive-v2`. Image timestamps remain:

- `0709a1b5d`: 2026-11-02 02:27:58 UTC;
- `87ac259cb`: 2026-11-03 20:42:42 UTC; and
- `e90a33f5d`: 2026-11-04 15:37:19 UTC.

The retained image digests are:

- RFS 3.4.0:
  `sha256:f2743156ed55f1d1b83a20b3b377f6ac052bdbe918c0edeff0ec18ba0593c923`;
- adaptive 0709:
  `sha256:39eadc3609cb30a8f3a5d80938542357498962f8d8c5b21f576e09918b2418a9`;
- instrumented 87ac:
  `sha256:b873ce97fe595fa8ddfed3fe2bada9fe1472684e76d23a2badca625ced6dc99f`;
  and
- bounded streaming:
  `sha256:40288c6ac3fa6c37d1a4d32fb1239e7da193136d704d74781fd0d55cab35457b`.

The instrumented image is tied to `87ac259cb` (2026-11-03 20:42:42 UTC) by the
branch tip and image build sequence: the deployed image was created at
20:43:51 UTC, and its workers started at 20:57 UTC. The retained worker logs
report the image ID shown above.

The commits between the two most recent adaptive runs were:

- `0d343b5a6` (2026-11-04 02:49:54 UTC): carry exact bulk sizing through the
  pipeline;
- `208c419c6` (2026-11-04 05:19:19 UTC): parallelize document preparation by
  write phase;
- `443226434` (2026-11-04 09:08:30 UTC): pool bulk assembly buffers; and
- `e90a33f5d` (2026-11-04 15:37:19 UTC): stream preparation through bounded
  chunks.

## Same-target RFS 3.4.0 rerun

This rerun used the adaptive target after clearing the prior test index. Its
compact layout is intentional so the report remains readable without very
wide tables.

| Run detail | Value |
|---|---|
| Migration label | `full340v6` |
| Target-writing window | 2026-11-05 02:01:12–08:55:45 UTC |
| Image | Public RFS 3.4.0 release |
| Snapshot/index | `large-snapshot` / `pilot_index` |
| Snapshot size | 24,000,000,000 documents; 400 primary shards |
| RFS fleet | 24 workers; 3300m CPU and 7000 MiB memory each |
| Request settings | Fixed 60 connections; 10 MiB / 20,000 documents |
| Target | `eop09-full-adaptive-v2`; same shape as the other runs |
| Tracing/profiling | None |

The full `SnapshotMigration` name is
`large-es7x-target-full-adaptive-v2-large-full340v6`. The public image digest
is `sha256:f2743156ed55f1d1b83a20b3b377f6ac052bdbe918c0edeff0ec18ba0593c923`.

| Result | Value |
|---|---:|
| Completion time | **6h54m33s** |
| Average accepted rate | **964,902 documents/s** |
| Completed shards | **400/400** |
| Final direct target count | **24,000,000,000** |
| Failed-document count | **0** |
| Target write rejections | **0** |
| Cluster index-write blocks | **0** |
| Final health / nodes | Green; 24 data + 3 master |

| Reference run | Duration difference | Rate difference |
|---|---:|---:|
| Original RFS 3.4.0 | +52m09s (+14.39%) | -12.58% |
| Adaptive 0709 | +24m09s (+6.19%) | -5.83% |
| Instrumented 87ac | -45m09s (-9.82%) | +10.89% |
| Bounded streaming | -50m02s (-10.77%) | +12.07% |

Duration percentages are relative to the reference run. Rate percentages are
the rerun's accepted-document rate relative to that reference.

The direct count was refreshed after completion and returned exactly
24 billion documents across 400 successful shards. The failed-document stream
was queried for this specific `SnapshotMigration` and returned zero. The
target's cumulative write-thread-pool rejection count also remained zero.

## Common target shape

Each lane used the same target capacity:

| Target setting | Value |
|---|---|
| Data nodes | 24 x `r6g.8xlarge.search` |
| Dedicated masters | 3 x `r6g.large.search` |
| Availability Zones | 3 |
| Storage per data node | 1024 GiB gp3 |
| Provisioned storage performance | 40,000 IOPS, 1187 MiB/s per data node |
| Replica count | 0 |
| Engine | OpenSearch 2.19 |

RFS 3.4.0 and adaptive 0709 ran concurrently in the same EKS environment but
wrote to separate, identically configured AOS domains. Instrumented 87ac,
bounded streaming, and the RFS 3.4.0 rerun each ran alone and reused the
adaptive domain after `pilot_index` was deleted. This avoids target contention
for the later runs but does not eliminate AOS host-placement or EKS
run-to-run variation.

## Full completion and correctness

| Measure | 3.4 original | Adaptive 0709 | Instrumented | Bounded | 3.4 rerun |
|---|---:|---:|---:|---:|---:|
| Completion | **6h02m24s** | 6h30m24s | 7h39m42s | 7h44m35s | **6h54m33s** |
| Seconds | 21,744 | 23,424 | 27,582 | 27,875 | 24,873 |
| Accepted docs/s | **1,103,753** | 1,024,590 | 870,133 | 860,987 | **964,902** |
| Duration vs original | Baseline | +7.73% | +26.85% | +28.20% | +14.39% |
| Rate vs original | Baseline | -7.17% | -21.17% | -22.00% | -12.58% |
| Shards complete | 400/400 | 400/400 | 400/400 | 400/400 | 400/400 |
| Accepted documents | 24B | 24B | 24B | 24B | 24B |
| Failed documents | 0 | 0 | 0 | 0 | 0 |
| Client HTTP 429 retries | 49,141 | 1,603 | 0 | 0 | Not retained |
| Client HTTP 502/5xx retries | 9,259 | 95 | 0 | 0 | Not retained |
| Target write rejections | 0 | 0 | 0 | 0 | 0 |
| Retry exhaustion | 0 | 0 | 0 | 0 | 0 |
| Actual JVM OOM | 0 | 0 | 0 | 0 | 0 |
| Health / nodes | Green, 24+3 | Green, 24+3 | Green, 24+3 | Green, 24+3 | Green, 24+3 |

For RFS 3.4.0 and adaptive 0709, refreshed direct target counts returned
exactly 24 billion documents across 400 successful primaries. Instrumented
87ac's target was subsequently cleared for another run, so the retained proof
is its completed 400/400 `SnapshotMigration`, an exactly 24-billion-operation
full-window AOS `IndexingRate` sum, and an empty failed-document prefix.
Bounded streaming and the RFS 3.4.0 rerun were refreshed and directly counted
at exactly 24 billion documents after completion, with empty failed-document
prefixes.

The outer Argo workflows for the later runs are labeled `Failed` only because
the known `initializeRunMetadata` bookkeeping step exited with code 1. Their
authoritative `SnapshotMigration` resources and document backfills report
`Completed`.

## Progress through the whole snapshot

The slowdown was present through the body of the migration, not solely in the
last shard:

| Progress | 3.4 original | Adaptive 0709 | Instrumented | Bounded | 3.4 rerun |
|---|---:|---:|---:|---:|---:|
| 25% | **1h33m54s** | 1h40m47s | 2h01m43s | 2h02m31s | 1h52m40s |
| 50% | **2h58m26s** | 3h12m32s | 3h52m42s | 3h54m11s | 3h31m52s |
| 75% | **4h23m11s** | 4h44m00s | 5h43m24s | 5h45m21s | 5h10m08s |
| 90% | **5h13m52s** | 5h37m43s | 6h49m32s | 6h51m48s | 6h08m33s |
| 95% | **5h33m50s** | 5h56m22s | 7h11m36s | 7h14m08s | 6h28m05s |
| 99% | **5h53m04s** | 6h18m10s | 7h31m07s | 7h32m13s | 6h44m09s |
| 100% | **6h02m24s** | 6h30m24s | 7h39m42s | 7h44m35s | 6h54m33s |

The first two columns use the direct target observer's approximately
80–90-second count samples. The three later columns use the one-minute AOS
`IndexingRate` series, linearly interpolated within the crossing minute.
Exact 100% times come from the migration resources.

Bounded streaming followed instrumented 87ac closely:

- 48 seconds behind at 25%;
- 1m29s behind at 50%;
- 1m57s behind at 75%;
- 2m16s behind at 90%;
- 2m32s behind at 95%;
- 1m06s behind at 99%; and
- 4m53s behind at completion.

That shape supports "no recovery" more strongly than "a large new
regression." The major step down is between adaptive 0709 and instrumented
87ac.

## Target pressure over each full run

| Measure | 3.4 original | Adaptive 0709 | Instrumented | Bounded | 3.4 rerun |
|---|---:|---:|---:|---:|---:|
| P95 / max write queue | 66.2 / 83 | 66.0 / 87 | **0 / 0** | **0 / 20** | **0 / 24** |
| P95 / max CPU | 98% / 99% | 97% / 98% | 65.5% / 89.1% | 70.7% / 96.0% | 88.2% / 96.8% |
| P95 / max JVM heap | 88.4% / 94% | **82% / 92%** | 83.6% / 94.6% | 82.8% / 93.1% | 83.4% / 93.7% |
| Minimum free storage | 720.3 GiB | 718.7 GiB | 710.7 GiB | 703.7 GiB | 703.1 GiB |
| Target write rejections | 0 | 0 | 0 | 0 | 0 |

The older pair kept the target substantially busier. Instrumented 87ac and
bounded streaming had a zero P95 write queue, materially lower P95 CPU, and no
client or target rejection. The later clients were not losing throughput
because the target was pushing back; they were offering less work to a target
that still had headroom.

CPU approaching 100% briefly is not treated as a failure. The safety signals
are rejection growth, persistent write queues, lost nodes, write blocks,
failed documents, retry exhaustion, and incomplete target counts. None of
those occurred in these five runs.

## What changed between the two latest adaptive runs

The newer metrics permit a direct request-path comparison for instrumented
87ac and bounded streaming:

| Client measure | Instrumented 87ac | Bounded streaming | Change |
|---|---:|---:|---:|
| Successful first attempts | 2,243,040 | 2,088,849 | -6.87% |
| Successful requests/s | 81.32/s | 74.94/s | -7.85% |
| Mean documents/request | 10,624 | 11,319 | +6.54% |
| Mean uncompressed NDJSON/request | 9.999 MiB | 10.652 MiB | +6.54% |
| Mean successful HTTP attempt duration | 3.316s | 3.777s | +13.90% |
| Mean raw-batch preparation duration | 22.17ms | 5.02ms | **-77.35%** |
| Mean requests in flight | 11.93 | 12.81 | +7.41% |
| Mean adaptive allowance | 28.97 | 31.34 | +8.19% |
| Mean slot wait per request | 2.57ms | 6.95ms | +4.38ms |

The bounded-streaming preparation work itself became much cheaper: the
instrumented preparation metric fell by 77%. That local optimization did not
raise end-to-end throughput because:

- requests were 6.54% larger but successful request rate fell 7.85%;
- the mean successful HTTP attempt took 13.90% longer;
- requests in flight remained far below the adaptive allowance; and
- slot wait remained only milliseconds beside multi-second HTTP latency.

The 11,320-document setting did what the test intended. Full requests averaged
11,319 documents, matching the observed RFS 3.4.0 document count. The 14 MiB
byte ceiling was not normally reached; the document ceiling produced an
average 10.652 MiB final request.

The request histograms cover slightly fewer operations than the target's
accepted count because of metric collection loss near process turnover. They
are reliable for the large relative differences above but should not replace
the target count for correctness.

## Interpretation

The completed runs support these conclusions:

- The original RFS 3.4.0 run remains the fastest whole-snapshot result.
- RFS 3.4.0 varied by 52m09s, or 14.39% of the original duration, between its
  two complete runs. Environment and run-to-run variance are therefore
  significant.
- On the same reused target, the RFS 3.4.0 rerun still finished 50m02s faster
  than bounded streaming and accepted documents 12.07% faster.
- Adaptive 0709 traded 7.17% throughput for 30.7 times fewer client-visible
  429 attempts and 97.5 times fewer 502 attempts, without changing final
  correctness on this target.
- By instrumented 87ac, accepted throughput had fallen another 15.08% from
  adaptive 0709 even though target CPU and write queues also fell sharply.
- The four commits through bounded streaming substantially reduced measured
  preparation cost but did not recover accepted throughput.
- The latest 1.05% deficit from instrumented 87ac is modest and concentrated
  partly in the final tail. The persistent concern is the roughly 16–22%
  deficit from the earlier complete images.
- Neither newer run was constrained by adaptive allowance, slot acquisition,
  target write queues, target rejections, failed documents, tracing, or
  profiling.

The next useful isolation point is between `0709a1b5d` (2026-11-02 02:27:58
UTC) and `87ac259cb` (2026-11-03 20:42:42 UTC), especially the
cross-source-batch repacking and raw-batch filling changes.
The full-run evidence says the later preparation implementation is faster at
the measured preparation stage, so another stage is limiting request
production or increasing target response time.

## Retained incomplete-run evidence

The earlier three-way checkpoint included a separate run at `b3fc745f7`
(2026-11-03 14:08:44 UTC). That migration was stopped before retained
whole-snapshot completion proof, so it is intentionally excluded from the
tables above.

Its 2h30m checkpoint remains useful historical evidence:

| Run | Documents at 2h30m | Snapshot progress | Average accepted rate |
|---|---:|---:|---:|
| RFS 3.4.0 | 10,017,464,941 | 41.739% | 1,113,052/s |
| Adaptive 0709 | 9,156,490,978 | 38.152% | 1,017,388/s |
| Rebatching `b3fc745f7` (2026-11-03 14:08:44 UTC) | 8,445,433,939 | 35.189% | 938,382/s |

The checkpoint table above is retained as the compact historical record for
that incomplete run. The dedicated original whole-snapshot report is also
preserved:

- [RFS 3.4.0 vs adaptive 0709 full-capacity report](RFS_340_VS_ADAPTIVE_FULL_CAPACITY_COMPARISON.md)

## Limitations

- This compares complete images rather than isolating one code change at a
  time.
- The first two runs shared EKS. The later adaptive runs and the RFS 3.4.0
  rerun ran alone.
- The later runs reused the same AOS domain after deleting the prior index.
- The instrumented 87ac target was cleared before another direct final count
  could be retained; its exact AOS indexing total, completed 400/400 status,
  and empty failed-document stream are the surviving completion proof.
- Target percentiles for the first two runs come from the direct observer.
  The later two use corresponding one-minute AOS CloudWatch series.
- HTTP retry counts are failed attempts, not unique requests or documents.
- Final-shard scheduling makes completion time noisier than the 25–99%
  milestones, particularly when only a few shards remain.
