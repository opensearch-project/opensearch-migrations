# RFS mainline with failed-document stream vs adaptive flow control

Status: extended to the full matched 33,248 seconds (9h14m08s) of
target-writing time. The mainline migration remains running. AWS credentials
were refreshed on 2026-11-03, allowing the monitoring gap to be reconstructed
from CloudWatch and the failed-document S3 prefix.

## Conclusion

Over the same first 9h14m08s against identically shaped constrained targets,
the adaptive image remained both faster and substantially safer:

- adaptive migrated 2,602,955,369 target documents versus mainline's
  approximately 2,338,669,822, an 11.3% lead;
- adaptive averaged 78.3K accepted target documents/s versus mainline's
  70.3K/s;
- mainline accumulated approximately 542,803 indexing-pressure rejections
  versus 1,480 for adaptive, approximately 367 times as many;
- mainline's median target write queue was 132 versus 22 for adaptive;
- 308,661 mainline failed-document records were present in S3 by the matched
  cutoff; and
- adaptive completed four clean 24-worker lease-checkpoint waves, totaling 96
  restarts, with no retry-exhaustion signal or observed terminal failure.

Neither target recorded a write-thread-pool rejection. Adaptive reached the
same 100% hottest-node CPU level while keeping pressure rejection, queue and
heap levels lower. The important distinction is therefore client behavior
under admission-control and timeout feedback, not whether CPU reached 100%.

## Matched setup

The runs used separate EKS environments and separate AOS domains. The target
domains had the same shape, and both runs used the same snapshot and offered
load ceilings.

| Dimension | Mainline/FDS run | Adaptive run |
|---|---|---|
| Target-writing window | 2026-11-02 14:15:32–23:29:40 UTC | 2026-11-02 05:03:51–14:17:59 UTC |
| Compared duration | 33,248 seconds | 33,248 seconds |
| Target | `eop09-target-throttle-v2` | `eop09-target-adaptive` |
| RFS image commit | `37ac6e2e2c90668755995e025fc84c64833d1980` | `0709a1b5db10979a6f15e404fb994895d678beb5` |
| Snapshot | `large-snapshot`, `pilot_index`, approximately 24B documents | Same |
| Primary shards | 400 | 400 |
| RFS workers | 24 | 24 |
| Maximum connections per worker | Fixed 60 | Adaptive allowance, ceiling 60 |
| Bulk limits | 10 MiB / 20,000 documents | Same |
| Worker resources | 3300m CPU, 7000 MiB memory | Same |
| Initial work-item lease | 2 hours | Same |
| Maximum shard size | 80 GiB | Same |
| Target data nodes | 6 x `r6g.xlarge.search` | Same |
| Dedicated masters | 3 x `r6g.large.search` | Same |
| Storage per data node | 2048 GiB gp3, 20K IOPS, 593 MiB/s | Same |
| Target replicas | 0 | 0 |
| Failed-document stream | Enabled | Disabled |

The adaptive endpoint is its final direct observer sample. Mainline target
documents at exactly 23:29:40 UTC are interpolated between the surrounding
one-minute CloudWatch `SearchableDocuments` samples. Mainline target
queue/CPU/heap statistics and pressure counters use all six node-level
CloudWatch series. The failed-document count includes the 35 S3 objects whose
last-modified time was at or before the matched cutoff.

## Matched-time results

| Measure at 9h14m08s | Mainline, fixed 60 | Adaptive 0709, max 60 | Difference |
|---|---:|---:|---:|
| Target documents | 2,338,669,822 | 2,602,955,369 | Adaptive +264,285,547 (+11.3%) |
| Share of nominal 24B snapshot | 9.74% | 10.85% | Adaptive +1.10 percentage points |
| Average target-document rate | 70,340/s | 78,289/s | Adaptive +7,949/s (+11.3%) |
| Median target write queue | 132 | 22 | Adaptive 83.3% lower |
| P95 target write queue | 156 | 107.8 | Adaptive 30.9% lower |
| Maximum target write queue | 193 | 140 | Adaptive 27.5% lower |
| Indexing-pressure rejection delta | approximately 542,803 | 1,480 | Mainline approximately 367x higher |
| Write-thread-pool rejection delta | 0 | 0 | Equal |
| Maximum hottest-node CPU | 99.9% | 100% | Effectively equal |
| P95 hottest-node JVM heap | 89.9% | 80.9% | Adaptive 9.0 points lower |
| Maximum hottest-node JVM heap | 94.2% | 84% | Adaptive 10.2 points lower |
| Failed-document records available by cutoff | 308,661 | Not enabled | Not directly comparable |
| Cumulative worker restarts | Not reconstructable at exact cutoff | 96 | See restart-history caveat |

Mainline's exact restart count at 9h14m08s cannot be recovered from the target
metrics. It had already reached 29 restarts by 2h31m07s, before local AWS
authentication expired, and had reached 202 by approximately 15 hours. The
report does not interpolate a restart value across that gap.

## Mainline failure timeline

The failed-document stream makes emitted mainline failures directly
measurable. The first three compressed NDJSON files contained:

| Approximate elapsed time | Failed documents | Immediate cause |
|---|---:|---|
| 37m41s | 10,621 | Target admission-control 429s continued through `Retries exhausted: 15/15` |
| 47m58s | 10,665 | Target admission-control 429s continued through `Retries exhausted: 15/15` |
| 69m30s | 10,684 | Read timeout continued through `Retries exhausted: 15/15` |
| **Total** | **31,970** | Failure records emitted for later reconciliation |

Separately, two mainline workers exited with `java.lang.OutOfMemoryError: Java
heap space`, at approximately 29m09s and 50m54s. Their logs show 60 of 60
active batches while repeated 429 responses retained large request and
response state. Those OOM exits did not themselves create failed-document
files.

The pattern continued rather than settling:

| Checkpoint | Failed-document records | Cumulative restarts | Pressure rejections |
|---|---:|---:|---:|
| 68m50s | 31,970 | 4, with a fifth completing immediately afterward | 66,741 |
| Approximately 2h | 85,266 | 25, including the 12-worker lease wave | 115,012 |
| 2h31m07s | 127,780 | 29 | 147,322 |
| 9h14m08s | 308,661 | Exact value unavailable during auth gap | Approximately 542,803 |

Mainline added approximately 476,062 pressure rejections between 68m50s and
9h14m08s, about 981 per minute. That is effectively unchanged from the
approximately 970-per-minute rate during the first 68m50s. Admission pressure
did not diminish as the run continued.

The S3 prefix contained another 276,691 failed-document records after the
initial checkpoint. The exact mix of later 429 retry exhaustion, timeout
exhaustion and heap OOM cannot be reconstructed from target counters alone,
but the S3 total establishes that the unsafe pattern continued.

## What the adaptive run did differently

The adaptive run also received admission-control feedback, but converted that
feedback into a reduced per-process request allowance. During the matched
window:

- its target recorded only 1,480 indexing-pressure rejections;
- its observer recorded no retry-exhaustion signal;
- all 24 workers remained ready, including through four clean lease rotations;
- its median write queue was 22 rather than 132; and
- it accepted 11.3% more documents.

Admission control protects target nodes by rejecting buffered work, but it
does not regulate a fixed-concurrency client. Mainline continued filling up to
60 active batches per process while retrying rejected work. That sustained
queue pressure, retained enough data to cause heap OOMs, and allowed
individual batches to exhaust their retry budgets.

The adaptive image reduces offered concurrency after those signals. In this
comparison, reducing wasted and retained work improved accepted throughput
rather than imposing a throughput penalty.

## Failed-document accounting caveat

At the matched cutoff, 308,661 mainline failed-document records were present in
S3. By the latest refreshed count, the still-running migration had emitted
542,647 records across 57 objects.

These are emitted failure records, not yet a count of unique documents that
will be absent when the migration finishes. A successor work item can replay
an uncommitted range, and some recorded documents may subsequently succeed.
Final manual-remediation counting therefore requires reconciliation with the
completed target.

The adaptive run did not have a failed-document stream enabled. Its 96
restarts were clean lease checkpoints, and it had no retry-exhaustion signal
in the matched interval, so there is no evidence of a comparable terminal
failure. That is not equivalent to an independently measured S3 count of
zero.

## Important limitations

- Neither migration completed during the matched window; this report compares
  only the first 9h14m08s.
- The comparison changes the complete image, not only the controller. Commit
  `0709a1b5d` also includes deferred allocation of bulk buffers until a request
  slot is available. That memory optimization can contribute to the absence
  of OOM exits and should not be attributed solely to adaptive limit cuts.
- The mainline failed-document stream adds some S3 work that the adaptive run
  did not perform. Its volume is small relative to bulk traffic and cannot
  explain adaptive's 11.3% accepted-throughput lead.
- The runs used identically configured but separate AOS domains. Ordinary host
  placement and service variability remain possible.
- Mainline target statistics after 2h31m are reconstructed from CloudWatch's
  one-minute AOS metrics. Adaptive statistics use its approximately
  90–110-second observer samples.
- Mainline restart and worker-readiness history was not archived during the
  local authentication gap. The report leaves those values unavailable
  rather than estimating them.
- The exact mainline endpoint document count is a linear interpolation over
  40 seconds between adjacent `SearchableDocuments` samples.

## Current mainline status after credential refresh

At the last complete observer sample, 2026-11-03 05:05:52 UTC, mainline had
been target-writing for 14h50m20s and had:

- 24/24 ready workers;
- 3,783,373,190 target documents, approximately 15.8% of the nominal 24B
  snapshot;
- 200 cumulative worker restarts;
- 877,460 indexing-pressure rejections;
- target health green across all six data nodes;
- hottest-node CPU 100%, hottest-node heap 82%, and maximum write queue 122;
  and
- zero write-thread-pool rejections.

The direct Kubernetes restart-counter total reached 202 a few minutes later.
The refreshed S3 count was 542,647 failed-document records. The test remains
running and has not been stopped.
