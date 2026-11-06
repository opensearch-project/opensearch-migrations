# RFS high-concurrency safety comparison at 30 minutes

Status: living comparison. The first RFS 3.4.0 fixed-60 run is finalized
below. A more tightly constrained repeat is active and has been added at its
30-minute checkpoint without stopping the test.

## Question

When RFS 3.4.0 is given the same 60-connection and 20,000-document ceilings as
the new adaptive implementation, does ordinary retry backoff settle it near a
safe target rate, or does fixed concurrency cause persistent overload, slower
ingestion, or unrecoverable errors?

## Matched setup

All runs used the same externally managed snapshot, 400 primary shards,
24 worker processes, 10 MiB bulk byte ceiling, two-hour initial lease, zero
target replicas, and the same constrained 12-data-node target. Each worker had
the same CPU and memory allocation.

| Run | Image and target-concurrency policy | Bulk document ceiling |
|---|---|---:|
| New adaptive | Current image; adaptive allowance with maximum 60 per worker | 20,000 |
| RFS 3.4.0 fixed 10 | Released image; fixed maximum 10 per worker | 2,147,483,647 |
| RFS 3.4.0 fixed 60 | Released image; fixed maximum 60 per worker | 20,000 |

The fixed-60 run therefore matches the new run's offered-load ceilings while
removing the adaptive limiter. The fixed-10 run is the demonstrated safe
reference point for this target.

## Results at 1,780 seconds

Target document counts for samples bracketing 1,780 seconds were linearly
interpolated to the common timestamp. Queue, pressure, CPU, and worker values
use samples at or before that timestamp.

| Measure | New adaptive, max 60 | RFS 3.4.0, fixed 10 | RFS 3.4.0, fixed 60 |
|---|---:|---:|---:|
| Target documents | 788,366,697 | 881,881,766 | 842,773,765 |
| Average target-document rate | 442,903 docs/s | 495,439 docs/s | 473,468 docs/s |
| Maximum target write queue | 20 | 4 | 104 |
| Median / p95 target write queue | 6 / 18 | 3 / 4 | 94 / 103 |
| Indexing-pressure rejection delta | 0 | 0 | 15,373 |
| Maximum hottest-node CPU | 99% | 99% | 99% |
| Median / p95 hottest-node heap | 67% / 76% | 74% / 80% | 77% / 83% |
| Minimum ready workers | 24 | 24 | 24 |
| Worker restarts | 0 | 0 | 0 |
| New adaptive-limit cuts | 4,365 | Not emitted | Not present |

Compared with the new adaptive run, fixed-60 RFS 3.4.0 migrated 54,407,068
more documents, or 6.9%, but accumulated 15,373 target indexing-pressure
rejections and reached a write queue 5.2 times as high.

Compared with fixed-10 RFS 3.4.0, fixed-60 migrated 39,108,001 fewer
documents, or 4.4% less, while changing the target from zero pressure
rejections and a maximum queue of 4 to 15,373 rejections and a maximum queue
of 104. On this target, increasing the old client's connection ceiling made
both throughput and safety worse.

## Safety interpretation

At 30 minutes, the new adaptive implementation is demonstrating a real safety
tradeoff at the same configured ceiling:

- it is approximately 6.9% slower than fixed-60 RFS 3.4.0;
- it has kept the target's indexing-pressure rejection delta at zero;
- its maximum queue is 20 instead of 104; and
- all workers remain available in both runs.

RFS 3.4.0 retries each failed batch up to 15 times with exponential backoff
starting at two seconds and capped at 60 seconds. That backoff delays the
individual failed batch, but it does not reduce the fleet's fixed connection
ceiling or stop unrelated first attempts from filling available connections.
The observed result matches that distinction: the migration remains live and
makes progress, but the fleet has not reduced the overload it applies to the
target.

Fixed-10 RFS 3.4.0 remains the best result at this checkpoint because its
static limit happens to fit this target: it is 11.9% faster than the adaptive
run, 4.6% faster than fixed-60, and has no target rejection signal. That does
not make fixed concurrency generally safer; it shows that a correctly chosen
static limit can outperform a controller that is configured with an
unnecessarily high probing ceiling.

## What is not known yet

No retry-exhaustion messages, failed workers, restarts, write blocks, node
loss, or red target health were detected through this checkpoint. Thus the
fixed-60 run is already less safe, but it has not produced an unrecoverable
error.

The failed-document stream is disabled for this backfill, and RFS 3.4.0 does
not emit the newer terminal-failure metrics. Continued monitoring therefore
uses worker retry-exhaustion logs, worker and workflow state, target health,
and target rejection counters rather than treating a missing metric as zero.

The next useful checkpoints are:

1. 40 minutes, to see whether queue and rejection growth begin falling;
2. 90 minutes, to expose retry exhaustion or a durable steady state; and
3. after the run is eventually stopped, a drain period to distinguish accepted
   in-flight work from continued retries.

The active test will not be stopped or scaled without explicit user
confirmation.

## 60-minute update: the overloaded equilibrium produced a worker failure

The later behavior materially strengthened the 30-minute conclusion. At
58m19s, one fixed-60 RFS 3.4.0 worker failed shard 0 and exited with code 1.
Kubernetes restarted it one second later. This was not an OOM, eviction,
liveness failure, or node loss.

The worker log provides a direct causal chain:

1. A bulk reached attempt 16 (the original attempt plus 15 retries) with
   11,326 documents still pending.
2. Those remaining items were rejected with target
   `rejected_execution_exception` admission-control responses.
3. Reactor reported `Retries exhausted: 15/15`.
4. RFS reported a pipeline error for shard 0, propagated
   `RfsException: Partition migration failed`, and the process exited 1.

The coordinator did create an unleased successor work item at cursor
53,839,728 while completing the original work item. The failure therefore did
not leave shard 0 blocked behind its original two-hour lease. The remaining
shard work is available to be acquired again; the observed evidence does not
show permanent document loss. It does show work that must be retried, worker
restart overhead, and a delayed shard completion.

### Time-aligned results at 3,600 seconds

Document counts are interpolated to exactly 60 minutes. Queue and worker
summaries use the samples through that point.

| Measure | New adaptive, max 60 | RFS 3.4.0, fixed 10 | RFS 3.4.0, fixed 60 |
|---|---:|---:|---:|
| Target documents | 1,534,899,358 | 1,773,940,379 | 1,720,231,899 |
| Average target-document rate | 426,361 docs/s | 492,761 docs/s | 477,842 docs/s |
| Maximum target write queue | 21 | 4 | 143 |
| Median / p95 target write queue | 7 / 20 | 3 / 4 | 100 / 121 |
| Indexing-pressure rejection delta | 0 | 0 | 31,123 |
| Maximum hottest-node CPU | 99% | 100% | 99% |
| Minimum ready workers | 24 | 24 | 24 |
| Worker restarts | 0 | 0 | 1 |

Fixed-60 remained 12.1% ahead of the adaptive run in accepted target
documents at this checkpoint, but it was 3.0% behind the safe fixed-10 run.
It gained that lead over adaptive while accumulating 31,123 target-pressure
rejections and suffering the first application-level worker failure.

The rejection rate did not settle. Fixed-60 accumulated approximately 15,538
pressure rejections in the first 30 minutes and another 15,585 in the second
30 minutes, or about 520 per minute in each half. Its write queue reached 143,
while the adaptive and fixed-10 runs remained at maximums of 21 and 4
respectively.

After the restart, all 24 workers were ready again, the target remained green,
the target write-rejection counter remained zero, and migration continued.
That recovery is useful, but it does not make the clients equally safe: the
old fixed-60 client maintained overload until an individual batch exhausted
its retry budget and terminated a worker, whereas the adaptive run had no
target-pressure rejections or worker restarts in the matched first hour.

### 65-minute update: the failure repeated

At approximately 65 minutes, a second worker failed with the same causal
chain, this time while processing shard 3. Its final bulk had 11,325
documents remaining after attempt 16, Reactor reported
`Retries exhausted: 15/15`, and the worker exited 1 before Kubernetes
restarted it.

The coordinator again completed the original work item and created an
unleased successor, at cursor 57,927,670. The restarted worker acquired a
different shard while the successor remained available for a later worker.
There are therefore two failed shard-processing attempts and associated
retry/reprocessing work, but still no evidence that these documents have
become terminal manual-remediation failures.

These were RFS worker-process exits and restarts, not target-node crashes.
Throughout both events, the target remained green with all 12 data nodes and
3 dedicated masters present. No target write rejection, write block, or node
loss was observed.

## 70- and 80-minute checkpoints

| Measure | 70 minutes | 80 minutes |
|---|---:|---:|
| Target documents | 2,022,985,648 | 2,320,335,267 |
| Average target-document rate | 481,663 docs/s | 483,403 docs/s |
| Completed shards at the bracketing sample | 21 | 22 |
| Target write queue | 106 | 104 |
| Indexing-pressure rejection delta | 33,385 | 35,964 |
| Ready workers | 24 | 24 |
| Cumulative RFS worker restarts | 2 | 2 |
| Target data / master nodes | 12 / 3 | 12 / 3 |
| Target health | green | green |
| Target write-rejection delta | 0 | 0 |

Pressure rejections increased by 2,262 during minutes 60-70, approximately
226 per minute, and by 2,579 during minutes 70-80, approximately 258 per
minute. This is lower than the approximately 520-per-minute first-hour rate,
but it is not yet evidence that the old client has safely settled. Both RFS
worker failures occurred during minutes 50-70, and replacement workers
temporarily stopped sending bulk traffic while restarting and downloading
new shards. The queue also remained above 100.

## Failed-document accounting

No documents are currently confirmed as terminal failures requiring manual
remediation. That statement is not based on a measured zero: this backfill
does not have a failed-document stream configured, and RFS 3.4.0 does not
emit the newer terminal-failure metric.

Each worker failure left approximately 11.3K documents pending in its final
bulk. The coordinator created a successor work item for the unfinished
portion of each shard, so those documents remain migration work rather than
being classified as permanently failed. A definitive zero-missing-document
result requires the migration to finish and its completion and target counts
to be validated.

## 90-minute checkpoint

The apparent reduction in pressure during minutes 60-80 did not continue.
Minutes 80-90 added approximately 4,181 pressure rejections, or 418 per
minute, and the target write queue returned to 128.

| Measure | New adaptive, max 60 | RFS 3.4.0, fixed 10 | RFS 3.4.0, fixed 60 |
|---|---:|---:|---:|
| Target documents | 2,354,355,462 | 2,704,753,026 | 2,609,877,825 |
| Average target-document rate | 435,992 docs/s | 500,880 docs/s | 483,311 docs/s |
| Maximum target write queue | 21 | 4 | 143 |
| Median / p95 target write queue | 7 / 20 | 3 / 4 | 103 / 129 |
| Indexing-pressure rejection delta | 0 | 0 | 40,145 |
| Maximum hottest-node CPU | 99% | 100% | 99% |
| Minimum ready workers | 24 | 24 | 24 |
| RFS worker restarts | 0 | 0 | 2 |

At 90 minutes, fixed-60 was 10.9% ahead of adaptive in accepted target
documents, but 3.5% behind fixed-10. The extra offered concurrency therefore
continued to make the old client less safe and slower than a static setting
that fit the target. It produced more than 40K target-pressure rejections and
two failed shard attempts without improving throughput over fixed-10.

A more severely constrained follow-up can make this behavior appear earlier,
but a durable terminal failed-document count will still be unavailable unless
that follow-up configures a failed-document stream or uses an image that emits
the newer terminal-failure metric.

## 100-minute checkpoint: third worker failure

A third RFS worker failed at approximately 98 minutes while processing shard
30. Its final bulk had 11,323 documents remaining after attempt 16, followed
by `Retries exhausted: 15/15`, an RFS process exit with code 1, and an
immediate Kubernetes restart. The coordinator created a successor at cursor
51,756,603, and the restarted worker acquired shard 59.

At exactly 100 minutes, the run had approximately 2,904,282,084 target
documents, 34 completed shards at the bracketing sample, all 24 workers
ready, three cumulative worker restarts, a target write queue of 103, and
44,700 cumulative pressure rejections. The target remained green with all
12 data nodes and 3 masters and zero target write rejections.

Minutes 90-100 added approximately 4,555 pressure rejections, or 456 per
minute. The old fixed-60 client is therefore still applying persistent
overload; its lower rejection rate during minutes 60-80 was temporary rather
than a durable settling behavior.

## 120-minute checkpoint: RFS Java heap exhaustion

At exactly 120 minutes, the run had approximately 3,496,735,423 target
documents, 42 completed shards, all 24 workers ready, a target write queue of
112, and 53,440 cumulative pressure rejections. The target remained green
with all 12 data nodes and 3 masters and zero target write rejections.

Approximately 22 seconds later, a fourth RFS worker exited. This was not
another retry-budget exhaustion: the JVM terminated with
`OutOfMemoryError: Java heap space` while processing shard 43. Its logs showed
many concurrently retrying, admission-rejected bulks and a final heartbeat
with 22 of 60 batch slots active.

The worker container had a 7,000 MiB memory limit. Its launcher set
`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=80.0 -XX:+ExitOnOutOfMemoryError`,
giving the JVM a maximum heap of 5,872,025,600 bytes: 5,600 MiB, approximately
5.47 GiB. An earlier diagnostic incorrectly reported the JVM's default 25%
heap because it launched a separate Java process without the wrapper's
environment; that was not the running RFS process. The worker exhausted its
intended 80% heap while fixed concurrency retained large request and response
objects during admission-control retries.

Unlike the prior three retry-exhaustion exits, this abrupt heap failure did
not complete the shard-43 work item or create a successor checkpoint. The
coordinator still shows its original lease held until
2026-11-02T01:58:22Z. That shard cannot be acquired by another worker until
the lease expires, creating approximately an hour of avoidable recovery
delay.

This was an RFS worker Java-process failure, not a target-node failure. The
target stayed green and retained its full node count throughout the event.
It is stronger evidence that old fixed-60 operation under sustained
admission-control pressure is not only noisy but can exhaust client memory
and delay work recovery.

## 140-minute checkpoint: second Java heap exhaustion

At exactly 140 minutes, the run had approximately 4,089,923,975 target
documents, all 24 workers ready, a target write queue of 118, and 60,241
cumulative pressure rejections. Four worker restarts had occurred by that
instant. The target remained green with all nodes present and zero target
write rejections.

Approximately 80 seconds later, a fifth RFS worker exited with
`OutOfMemoryError: Java heap space` while processing shard 48. Its last
heartbeat showed 57 of 60 batch slots active. As with the shard-43 heap
failure, the abrupt exit created no successor checkpoint. The original
shard-48 work item remains leased until 2026-11-02T02:18:53Z.

The client-side failure pattern is now:

- three retry-exhaustion exits that created successor work items;
- two Java heap-exhaustion exits that did not create successors and strand
  their shards until lease expiry; and
- no target-node crash, target write block, or target write rejection.

This distinction matters operationally: the target remains available and
continues accepting work, while excessive fixed client concurrency causes
the migration workers themselves to fail and delays recovery of affected
shards.

## Final checkpoint and user-directed stop

The run was intentionally stopped at 2026-11-02T01:47:14Z so the same test
could be moved to a more tightly constrained target. Its measured data window
was 2026-11-01T22:58:30Z through 2026-11-02T01:47:14Z: 2 hours, 48 minutes,
and 44 seconds.

The target contained 4,940,241,354 documents at the stop. Subtracting the
1,148,350 documents present when the measurement window began gives
4,939,093,004 documents migrated during the window, an average of
approximately 487,860 documents per second. The SnapshotMigration reported
67 of 400 shards complete, or 16.75% by shard count.

The final target-side totals were:

- 67,583 indexing-pressure rejections;
- zero write-thread-pool rejections;
- green cluster health with all 12 data nodes and 3 dedicated masters;
- no write block or target-node loss; and
- approximately 890.5 GiB free on the fullest data node.

The client finished with five worker restarts: three retry-exhaustion exits
that created successor work items and two Java heap-exhaustion exits that
temporarily stranded their shards until lease expiry. All 24 worker replicas
were ready immediately before the user-directed stop.

The migration did not complete; it was stopped specifically to repeat the
same old-3.4.0, 24-worker, fixed-60, 10 MiB/20,000-document workload against
a six-node `r6g.xlarge.search` target with 32 GiB per data node.

## Archived evidence and target cleanup

Before the old target was deleted, its 2026-11-01T22:50:00Z through
2026-11-02T01:49:00Z CloudWatch window was exported to
`/tmp/eop-kind/old-340-high-60-v1-cloudwatch-20261101T225000Z-20261102T014900Z.json.gz`.
The validated archive contains 1,986 `AWS/ES` series, 1,016
`OpenSearchMigrations` series, and 1,902,270 one-minute statistic points. Its
SHA-256 is
`f82df28edb0a9803af1e5036ec9cf77eadcae86a3b66454218afa7da86996d23`.

The deployed dashboard definition for the same evidence window is preserved
at
`/tmp/eop-kind/old-340-high-60-v1-dashboard-20261101T225000Z-20261102T014900Z.json`
with SHA-256
`f5b5ebbbac8692ea0035fac80658cccc66f301a3034822b6fa9d33839f9d8f77`.
After both files passed validation, deletion of
`eop09-target-throttle-v1` was initiated. The replacement test uses
`eop09-target-throttle-v2`; that domain was not affected by the cleanup.

## Tighter-target repeat: 30-minute checkpoint

The same RFS 3.4.0 fixed-concurrency workload was repeated against a smaller
target to make the unsafe behavior appear sooner. Target writing began at
2026-11-02T01:53:46Z; the 30-minute checkpoint is
2026-11-02T02:23:46Z. The test remained active after this checkpoint.

The workload parameters were unchanged from the preceding fixed-60 run:

- RFS 3.4.0 image;
- 24 workers, with 7,000 MiB containers and 5,600 MiB maximum JVM heaps;
- fixed `maxConnections=60` per worker;
- 10 MiB configured document-content ceiling and 20,000-document ceiling
  per bulk request;
- actual bulk requests around 12.35 MiB after action metadata and NDJSON
  framing;
- the same 400-primary-shard snapshot and two-hour initial lease; and
- zero replicas on the target index.

The replacement target had six `r6g.xlarge.search` data nodes, each with
32 GiB RAM, approximately 16 GiB OpenSearch heap, 2,048 GiB gp3 storage,
20,000 provisioned IOPS, and 593 MiB/s provisioned storage throughput. It
also had three `r6g.large.search` dedicated masters.

Counts bracketing 30 minutes were linearly interpolated from 29m16s and
31m09s. Target state uses the surrounding observations.

| Measure | Tighter-target value at 30 minutes |
|---|---:|
| Target documents | approximately 127,862,913 |
| Documents migrated after the initial observation | approximately 127,851,965 |
| Average accepted target-document rate | approximately 71,029 docs/s |
| Indexing-pressure rejection delta | approximately 31,391 |
| Average indexing-pressure rejection rate | approximately 1,046/min |
| Maximum observed target write queue | 103 |
| Target write-thread-pool rejection delta | 0 |
| Maximum hottest-node CPU | 100% |
| Maximum hottest-node heap | 90% |
| Completed shards | 0 of 400 |
| Ready workers at surrounding samples | 24 of 24 |
| Cumulative worker restarts | 3 |
| Target health and node count | green; 6 data and 3 master nodes |

The three worker failures occurred much earlier than on the larger target:

1. At 12m27s, a worker exited with code 3 after
   `OutOfMemoryError: Java heap space`.
2. At 17m51s, a worker exhausted all 15 retries, reported
   `RfsException: Partition migration failed`, and exited with code 1.
3. At 25m43s, another worker followed the same 15-retry-exhaustion path and
   exited with code 1.

Kubernetes restarted all three workers. These were RFS process failures, not
target-node crashes: the target stayed green with all nodes present, no write
block, and no write-thread-pool rejection. Admission-control/indexing-pressure
429s were the limiting feedback.

This target therefore produced the requested severe crimping within the first
30 minutes. The old client did not merely settle to the target's accepted
rate: it kept enough concurrent and retrying bulk state to exhaust a worker's
5.47 GiB heap and exhausted two other workers' retry budgets.

## Tighter-target repeat: failure burst after 30 minutes

Five more workers failed between 2026-11-02T02:27:35Z and
2026-11-02T02:28:51Z, 33m49s through 35m05s after target writing began:

- three workers exited with code 1 after exhausting all 15 retries on HTTP
  429 responses; and
- two workers exited with code 3 after `OutOfMemoryError: Java heap space`.

That brought the cumulative total to eight worker restarts: five caused by
retry exhaustion and three caused by Java heap exhaustion. The rejected
operations explicitly reported target indexing-pressure admission control,
including both per-shard and per-node coordinating/primary byte limits.

The observation spanning this burst still showed approximately 71,576
accepted documents per second, green target health, all six data nodes and
three dedicated masters present, zero write-thread-pool rejections, and all
24 RFS replicas ready after Kubernetes restarted the failed processes. The
target therefore remained available; the fixed-concurrency RFS client was
the component repeatedly failing under the target's 429 feedback.

## Tighter-target repeat: final checkpoint and switch to main

After the main comparison image was available, the user authorized stopping
this old-3.4.0 run. Its final read-only capture was taken at
2026-11-02T02:43:04Z, 49m18s after target writing began.

| Measure | Final old-3.4.0 value |
|---|---:|
| Target documents | 212,153,125 |
| Documents migrated after the initial observation | 212,142,177 |
| Average accepted target-document rate | approximately 71,718 docs/s |
| Indexing-pressure rejection delta | 52,339 |
| Target write-thread-pool rejections | 0 |
| Completed shards | 0 of 400 |
| Ready workers | 24 of 24 |
| Cumulative worker restarts | 8 |
| Target health and node count | green; 6 data and 3 master nodes |
| Hottest-node CPU at final capture | 99% |
| Hottest-node heap at final capture | 83% |
| Largest target write queue at final capture | 61 |

The eight restarts remained five 15-retry-exhaustion exits and three Java
heap-exhaustion exits. After the capture, the SnapshotMigration was reset and
only its `pilot_index` target index was deleted. The target was verified green
with no unassigned shards before the next run.

## Upstream-main comparison setup

Upstream `main` commit `37ac6e2e2` was built as a version-only, multi-
architecture image. The immutable OCI index digest is
`sha256:c8f8a4ad8de837905e3ae3e4e8a9a805691e3334ccfd6f1f2134a08c89cedf3c`;
it contains both `linux/amd64` and `linux/arm64`. The branch image's `latest`
tag was verified unchanged.

The main comparison keeps the old run's material workload settings:

- 24 workers;
- fixed `maxConnections=60` per worker;
- 10 MiB configured document-content and 20,000-document bulk ceilings;
- two-hour initial leases and the same 80 GiB maximum-shard sizing; and
- the same six-node target, 400-primary-shard index, and zero replicas.

The first submission did not write documents. All workers exited during
argument parsing because the newer workflow generator supplied four JSON
fields that this main image does not recognize: `bulkResponseTimeout`,
`disableFailedDocumentStream`, `maxFailurePercentToFailDocuments`, and
`maxLeaseDuration`. For this one-off compatibility run, only those four
unsupported generated fields were removed from the live Deployment payload.
No workload-sizing field or test code was changed. Main still disabled the
failed-document stream through its native behavior because no failed-stream
bucket was configured.

The corrected Deployment reached 24 of 24 ready workers with zero restarts.
Every worker reported the expected main image digest. At
2026-11-02T02:53:07Z, before document writing began, `pilot_index` contained
zero documents, had 400 primary shards and zero replicas, and the target's
cumulative indexing-pressure rejection baseline was 52,940.

Target writing for the corrected main run began at
2026-11-02T02:55:36Z. The first observed count was 5,873 documents; two
seconds later the count was 10,660. At that point all 24 workers were ready
with zero restarts, the target was green, and no new indexing-pressure or
write-thread-pool rejection had been observed.

At the first approximately ten-minute checkpoint
(2026-11-02T03:06:57Z, 11m21s after the first observed documents), the main
run had:

- 46,276,342 target documents, or 46,270,469 after the first observation;
- an average accepted rate of approximately 67,944 documents per second;
- 9,964 new indexing-pressure rejections and zero write-thread-pool
  rejections;
- a largest current write queue of 131;
- 99% hottest-node CPU and 67% hottest-node heap;
- green target health with all six data nodes and three masters; and
- 24 of 24 workers ready with zero restarts.

The active shards demonstrated that the 60-connection setting was being used:
one worker repeatedly reported 59-60 of 60 active batches and another
reported 56 of 60 while other workers were still downloading or unpacking
their shards. The initially lower fleet rate was therefore an acceleration
and shard-readiness effect, not evidence that the connection ceiling was
unreachable.
