# RFS mainline vs adaptive flow control: parallel constrained-target comparison

Status: checkpointed at 2026-11-02 13:39:05 UTC. Both migrations were still
running when this report was written and were not stopped or modified.

## Conclusion

At the matched 8h35m14s checkpoint, the adaptive image is both faster and
safer than mainline under the same intentionally aggressive workload:

- adaptive has migrated 2.419B documents versus mainline's 2.099B, a 15.2%
  lead;
- adaptive has produced 969 target indexing-pressure rejections versus
  approximately 441.6K on mainline, about 456 times fewer;
- adaptive's median target write queue is 21 versus 132 on mainline;
- adaptive reports zero documents given up and zero terminal document
  failures in the current worker generation;
- all 24 workers and all target nodes remain available in both environments;
  both targets are green and neither has recorded a write-thread-pool
  rejection; and
- all 24 adaptive workers' latest exits are clean lease-expiry checkpoints.
  Mainline's latest retained termination states include 11 exit-1 failures
  and 3 heap-OOM exits in addition to 10 clean checkpoints.

This is no longer a case where the adaptive code is accepting a small
throughput penalty for safety. Over this matched interval it has accepted
about 10.3K more documents per second while applying much less rejected work
to the target.

## Matched setup

The runs execute in separate EKS environments and write to separate AOS
domains so they can proceed concurrently. The target domains have the same
shape.

| Dimension | Mainline run | Adaptive run |
|---|---|---|
| EKS context | `migration-eks-eop09` | `migration-eks-eop10` |
| Target | `eop09-target-throttle-v2` | `eop09-target-adaptive` |
| RFS image commit | `37ac6e2e2c90668755995e025fc84c64833d1980` | `0709a1b5db10979a6f15e404fb994895d678beb5` |
| RFS image digest | `sha256:c8f8a4ad8de837905e3ae3e4e8a9a805691e3334ccfd6f1f2134a08c89cedf3c` | `sha256:39eadc3609cb30a8f3a5d80938542357498962f8d8c5b21f576e09918b2418a9` |
| Snapshot | `large-snapshot`, `pilot_index`, approximately 24B documents | Same |
| Primary shards | 400 | 400 |
| RFS workers | 24 | 24 |
| Maximum connections per worker | Fixed 60 | Adaptive allowance, ceiling 60 |
| Bulk limits | 10 MiB / 20,000 documents | Same |
| Initial work-item lease | 2 hours | Same |
| Maximum shard size | 80 GiB | Same |
| Target data nodes | 6 x `r6g.xlarge.search` | Same |
| Dedicated masters | 3 x `r6g.large.search` | Same |
| Storage per data node | 2048 GiB gp3, 20K IOPS, 593 MiB/s | Same |
| Target replicas | 0 | 0 |
| Failed-document stream | Disabled | Disabled |

The mainline data window starts at 02:55:36 UTC and ends at the interpolated
11:30:50 UTC checkpoint. The adaptive window starts at 05:03:51 UTC and ends
at 13:39:05 UTC. Both cover exactly 30,914 seconds of target-writing time.

Because these are separate but identically shaped domains, the comparison
avoids the two clients contending with each other. It does not eliminate
ordinary AOS placement and host variability.

## Matched-time results

| Measure at 8h35m14s | Mainline, fixed 60 | Adaptive 0709, max 60 | Difference |
|---|---:|---:|---:|
| Target documents | 2,098,690,590 | 2,418,501,828 | Adaptive +319,811,238 (+15.2%) |
| Share of nominal 24B snapshot | 8.74% | 10.08% | Adaptive +1.33 percentage points |
| Average target-document rate | 67,888/s | 78,233/s | Adaptive +10,345/s (+15.2%) |
| Median target write queue | 132 | 21 | Adaptive 84.1% lower |
| P95 target write queue | 156.6 | 100.1 | Adaptive 36.1% lower |
| Maximum target write queue | 180 | 140 | Adaptive 22.2% lower |
| Indexing-pressure rejection delta | ~441,632 | 969 | Adaptive about 456x lower |
| Write-thread-pool rejection delta | 0 | 0 | Equal |
| Maximum hottest-node CPU | 100% | 100% | Equal |
| P95 hottest-node JVM heap | 89.6% | 81.0% | Adaptive 8.6 points lower |
| Maximum hottest-node JVM heap | 94% | 84% | Adaptive 10 points lower |
| RFS container restarts | 109 | 96 | Cause differs materially |
| Minimum ready workers | 24 | 24 | Equal |
| Minimum free storage on any target node | 1.87 TiB | 1.86 TiB | Both ample |

Target document counts come from the target index rather than client attempt
counters. This avoids counting a replayed document as additional migration
progress.

The mainline count endpoint timed out under load more often than the adaptive
one: 78.3% versus 99.1% of observer samples returned a count. The cumulative
plot leaves gaps where the count was unavailable; checkpoint document values
are interpolated between valid target counts.

## Worker exits and preservation of work

The adaptive restart pattern is regular and synchronized with its two-hour
work-item leases. Every adaptive worker's latest retained termination has
exit code 2. A sampled previous-worker log shows the expected sequence:
lease expired, progress cursor recorded, successor work item created, then
clean shutdown. This preserves the unfinished shard range for the replacement
process.

Mainline's latest retained termination state across the 24 worker pods is:

| Latest exit code | Pods | Interpretation |
|---|---:|---|
| 1 | 11 | Application failure; retained examples show 429 retry exhaustion and partition migration failure |
| 2 | 10 | Expected lease/checkpoint shutdown |
| 3 | 3 | All three retained previous logs contain heap-OOM evidence |

Earlier live monitoring captured at least six mainline
`Retries exhausted: 15/15` exits caused by target 429 responses. Four of the
currently retained exit-1 histories still expose that exact retry-exhaustion
text in their sampled log tails. The remaining exit-1 pods cannot be assigned
a precise lifetime cause from Kubernetes alone.

Kubernetes retains only the immediately previous container log for each pod,
so this table is a current last-exit distribution, not a complete historical
failure count. The cumulative restart total includes successful lease
rotations, retry-exhaustion exits, and OOM exits.

The observed retry-exhaustion failures have created successor work items from
the recorded cursor. They cause reprocessing and delay, but the available
evidence does not show those shard tails being discarded. A definitive
no-missing-documents result still requires completion and final source/target
count validation.

## What the adaptive controller is doing

An adaptive Prometheus snapshot immediately after the matched checkpoint
shows:

| Adaptive fleet measure | Value |
|---|---:|
| Average allowance per worker | 22.5 of 60 |
| Minimum / maximum allowance | 1.0 / 60 |
| Requests in flight across fleet | 530 |
| Processes waiting for a request slot | 791 |
| Workers at 0-24% of configured ceiling | 6 |
| Workers at 25-49% | 14 |
| Workers at 50-74% | 3 |
| Workers at 75-99% | 0 |
| Workers at 100% | 1 |
| Five-minute migrated-document rate | 82.3K/s |
| Five-minute migrated-document bytes | 72.0 MiB/s |
| Five-minute re-sent-document rate | 2.3K/s |
| Documents given up | 0 |
| Terminal document failures | 0 |

The current worker generation has recorded 869 allowance cuts: 661 from
ordinary throttled-429 feedback and 208 from admission-control rejection, with
none from timeout. It has also recorded 1,151 throttle retries and 88
transient retries.

Those controller counters reset when a worker process rotates, so they must
not be presented as whole-run totals. Their useful message is the current
fleet distribution: most workers are operating below half of the configured
60-request ceiling, while one can use the full ceiling. That is the
per-process heterogeneity the dashboard needs to expose. A single fleet
average would hide it.

## Interpretation

Admission control is giving both clients a useful server-side pressure
signal. It is protecting the AOS nodes by rejecting work before a target-node
failure, but it does not regulate the clients by itself.

Mainline keeps a fixed concurrency ceiling and retries rejected work. This
allows accepted traffic to continue, but it also sustains a high target queue,
creates a continuing stream of rejected/replayed work, and eventually
exhausts individual retry budgets. The OOM exits add a second client-side
safety problem under the same large-request/high-concurrency configuration.

The adaptive image converts the same feedback into a lower per-process
allowance. It still probes upward and receives some 429/admission feedback,
but it does not keep the whole fleet at the configured ceiling. In this run
that behavior has reduced wasted pressure enough to improve accepted
throughput, not merely safety.

The result also answers why a configured maximum of 60 does not imply a limit
of 60 in the dashboard. Sixty is a ceiling. At this checkpoint the sustainable
allowance is distributed across the fleet, with most workers between 15 and
30 requests and one worker at 60. The target is already fully busy; forcing
every worker toward 60 would increase retries and memory use rather than
accepted throughput.

## Important limitations

- Neither migration is complete. Adaptive has migrated about 10.1% and
  mainline about 8.7% of the nominal 24B documents at the matched checkpoint.
- This compares the complete mainline and adaptive images, not only the
  controller algorithm. Commit `0709a1b5d` also includes deferred allocation
  of bulk buffers until a request slot is available, which can contribute to
  the lower OOM risk.
- The failed-document stream is disabled in both configurations. The adaptive
  metric reports zero terminal failures in the current worker generation, but
  the final correctness check remains migration completion plus count
  validation.
- The target-side observer samples approximately every 90-110 seconds and can
  miss short spikes.
- The mainline target-count endpoint has intermittent timeouts under load.
  Target health, queues, rejection counters, worker state, and the migration
  itself continued during those count gaps.

## Current live status after the checkpoint

At approximately 13:40 UTC both targets remained green with 6 data nodes and
3 dedicated masters, both fleets had 24/24 ready workers, and both migrations
were continuing. No test was stopped to prepare this report.
