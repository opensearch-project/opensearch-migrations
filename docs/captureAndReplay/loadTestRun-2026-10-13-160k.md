# Capture and Replay at 150K+ requests/s (2026-10-13)

This benchmark measures Migration Assistant's capture-and-replay path under sustained, high-rate
ingest:
- Clients write to an Elasticsearch 7.10 source through the capture proxy fleet.
- Every request is recorded to Kafka.
- The Traffic Replayer re-sends the requests, live, to an OpenSearch 3.7 target.

The run is `Test0082CdcK6HighLoadStressTest` from `migrationConsole/lib/integ_test`.

## Results at a glance

| | Result |
|---|---|
| Sustained capture rate (2-hour hold) | 152.5K requests/s and 566 MiB/s mean; every minute between 149.4K and 156.1K requests/s |
| Sustained replay rate (2-hour hold) | 153.7K target responses/s and 586 MiB/s mean; every minute between 147.8K and 158.2K responses/s |
| Source/target comparisons (tuples) | 153.6K/s mean; 1,104,794,294 compared and written durably to S3 |
| Replay lag behind live traffic | 0.26 s median minute, 6.9 s 95th percentile, 21.8 s worst minute, 1.35 s mean |
| Catch-up after load stopped | under one minute |
| Documents written | 1,226,566,752 to the source; 1,226,593,719 to the target |
| Status codes matched, source vs target | 99.9954% of 1,104.8M compared requests |
| Requests the source accepted but the target failed | 7 |
| Pod restarts (proxies, replayers, Kafka brokers) | 0 |
| Coordinator CPU on either domain, 2-hour hold | 31–33% mean, under 47% in every minute |

The target finished with 26,967 more documents than the source. The replay re-sent every captured
request, including about 41K that the source had rejected (`500`: 32,733; `4xx`: 8,347). The target
indexed them. Requests the source rejected are not filtered out of the replay.

## Workload

| | |
|---|---|
| Generator | k6 on k6-operator: 220 runners (1 vCPU requested, 2 limit) on a dedicated on-demand node pool |
| Offered load | 160K requests/s: 123,077/s with keep-alive (100 runners), 30,769/s with `Connection: close` (80), 6,154/s with no connection reuse at all (40) |
| New connections | about 23% of requests open a new TLS connection to the proxies |
| Request | `_bulk` with one NYC-taxi document plus 1,300 bytes of padding: about 2 KiB of body, 3.9 KB per request including headers (SigV4 with a session token); responses about 0.5 KB |
| Profile | 25-minute linear ramp (11:00:33–11:26 UTC), then a two-hour hold at the full rate (11:26–13:26 UTC) |
| Index | one index with 48 primary shards and 1 replica, created identically on both clusters before the run |

## Environment

### Clusters

Both are Amazon OpenSearch Service domains in one VPC, peered to the EKS VPC, with the same shape:

| | Source | Target |
|---|---|---|
| Engine | Elasticsearch 7.10 | OpenSearch 3.7 |
| Data nodes | 48 × c8g.4xlarge | 48 × c8g.4xlarge |
| Dedicated coordinators | 48 × c8g.8xlarge | 48 × c8g.8xlarge |
| Dedicated masters | 3 × r6g.large | 3 × r6g.large |
| Storage per data node | gp3, 1,536 GiB, 4,608 IOPS, 250 MB/s | same |
| Access | HTTPS, SigV4 | HTTPS, SigV4 |

### Migration Assistant on EKS

EKS Auto Mode in us-east-2 across three zones, deployed from this repository with:

```
deployment/k8s/aws/aws-bootstrap.sh --deploy-create-vpc-cfn --stack-name MA-2026-10-13 --stage dev3 \
  --region us-east-2 --build --with-load-test-images --skip-console-exec --helm-values helm-values.yaml
```

`helm-values.yaml` keeps every workload on its own on-demand node pool of current-generation
instances:

```yaml
workloadsNodePool:
  capacityTypes: ["on-demand"]        # shared pool, which runs the Argo step pods
  dedicatedPools:
    proxy:
      capacityTypes: ["on-demand"]
      instanceCategories: ["c", "m"]
      minInstanceGeneration: 6
    k6:
      capacityTypes: ["on-demand"]
      instanceCategories: ["c", "m"]
      minInstanceGeneration: 6
```

The Kafka and replayer pools use the chart defaults: on-demand c/m/r and c/m, generation 6 or later.
The proxy pool was additionally limited to 48-vCPU instances (`eks.amazonaws.com/instance-cpu In
["48"]`). The chart has no setting for this, so it was applied to the NodePool directly.

Before the run, the Kafka StorageClass
(`TrafficCapture/trafficLoadTest/k6Operator/kafka-storageclass.yaml`) and the source and target
cluster ConfigMaps were applied. The k6 chart was installed with `deployment/k8s/installK6Chart.sh`.

| Component | Count | Per pod | Notes |
|---|---|---|---|
| Capture proxy | 128 | 5 vCPU, 5,000 MiB | behind one NLB; 43/43/42 across zones; 48-vCPU nodes |
| Kafka broker (Strimzi, KRaft) | 12 | 12 vCPU, 24 GiB, 3 TiB gp3 at 8,000 IOPS and 500 MB/s | topic `capture-proxy`: 512 partitions, replication 3, 7-day retention |
| Traffic Replayer | 64 | 8 vCPU requested (12 limit), 24 GiB | 8 partitions each; speedup 1.1; `readyRequestsBufferPerThread` 256; `maxConcurrentRequests` 20,000; default target pacing |

The partition count is a multiple of both fleets (four per proxy, eight per replayer), so every pod
carries an even share. In this run the proxy NLB had cross-zone load balancing enabled. The current
chart instead spreads the fleets evenly across zones (zone spread with `minDomains`), and leaves
cross-zone balancing off.

### Resources reserved and used, two-hour hold

Pod CPU is cAdvisor usage in 5-minute windows. Memory is the fleet's total working set: the mean
pod's working set × the pod count.

| Fleet | Pods | CPU requested / limit (vCPU) | CPU used, mean / peak (cores) | Memory requested (GiB) | Memory used, fleet total (GiB, mean) |
|---|---|---|---|---|---|
| Capture proxies | 128 | 640 / 640 | 454 / 457 (71%) | 625 | 83 (13%) |
| Kafka brokers | 12 | 144 / 144 | 77 / 79 (53%) | 288 | 271 (94%; mostly page cache) |
| Traffic Replayers | 64 | 512 / 768 | 485 / 495 (95% of requests) | 1,536 | 590 (38%) |
| k6 runners | 220 | 220 / 440 | 216 / 218 | 880 (limit 1,760) | 497 (56%) |
| Total | 424 | 1,516 / 1,992 | about 1,232 / 1,249 | 3,329 | about 1,441 (43%) |

Per pod, over the hold:

| Fleet | CPU request / limit | CPU used per pod: mean / busiest 5 min (fleet average) | Memory request | Working set per pod: mean / p50 / p90 / p99 / max |
|---|---|---|---|---|
| Capture proxy | 5 / 5 | 3.54 / 3.57 cores | 4.9 GiB | 0.65 / 0.64 / 0.74 / 1.04 / 1.23 GiB |
| Kafka broker | 12 / 12 | 6.4 / 6.6 cores | 24 GiB | 22.6 / 22.4 / 22.6 / 22.7 / 22.7 GiB |
| Traffic Replayer | 8 / 12 | 7.6 / 7.7 cores | 24 GiB | 9.2 / 8.8 / 11.5 / 19.7 / 20.4 GiB |
| k6 runner | 1 / 2 | 0.98 / 0.99 cores | 4 GiB (limit 8) | 2.3 / 1.7 / 4.3 / 4.9 / 5.2 GiB |

The memory percentiles are taken over every pod's working-set samples, scraped every 5 seconds.

Per-pod CPU percentiles are not available for this run, for two reasons:
- The cluster exports cAdvisor CPU to CloudWatch only as a total for each container name.
- The in-cluster Prometheus, which keeps per-pod series, was restarting during the run.

The kernel throttled the proxies in under 7% of CPU periods. Kafka's working set is mostly page cache
and sits near its limit by design.

The nodes behind the three Migration Assistant fleets:

| Pool | Nodes | vCPU | Memory |
|---|---|---|---|
| Proxies | 15 × c6g.12xlarge | 720 | 1,440 GiB |
| Kafka | 12 × c6g.4xlarge | 192 | 384 GiB |
| Replayers | 3 × 192-vCPU (m6a.48xlarge, m6a.metal, m8g.48xlarge) | 576 | about 2,250 GiB |

The k6 pool's nodes were scaled away when the run ended and are not recorded here.

Each domain, by node type:

| | vCPU | Memory | Busiest node CPU, mean / highest minute |
|---|---|---|---|
| Data nodes (48 × c8g.4xlarge) | 768 | 1,536 GiB | source 22% / 27%; target 17% / 23% |
| Coordinators (48 × c8g.8xlarge) | 1,536 | 3,072 GiB | source 33% / 36%; target 31% / 47% |
| Masters (3 × r6g.large) | 6 | 48 GiB | — |

## Results in detail

### Throughput, two-hour hold (per-minute values)

| | Mean | Median | 95th percentile | Lowest | Highest |
|---|---|---|---|---|---|
| Captured requests/s | 152,541 | 152,516 | 155,685 | 149,428 | 156,053 |
| Replayer target responses/s | 153,749 | 154,114 | 157,120 | 147,799 | 158,166 |

The replayer's responses include retried attempts, so they run slightly above the captured rate.

Bytes through each stage (MiB/s, per-minute mean, lowest and highest; total over the hold):

| Stage | Mean | Lowest | Highest | Total |
|---|---|---|---|---|
| Clients → proxies (requests) | 565.9 | 554.2 | 578.9 | 4.27 TB |
| Proxies → clients (responses) | 69.6 | 67.9 | 71.3 | 0.53 TB |
| Proxies → Kafka (published) | 670.1 | 656.1 | 685.5 | 5.06 TB |
| Kafka → replayers (read) | 675.0 | 651.3 | 690.0 | 5.10 TB |
| Replayers → target (requests) | 585.8 | 564.0 | 602.0 | 4.42 TB |
| Target → replayers (responses) | 72.2 | 69.3 | 75.1 | 0.55 TB |

Kafka is written with replication 3, so the 12 brokers together wrote about 2.0 GiB/s, about
167 MiB/s each. Over the whole run the proxies published about 1.52 billion Kafka records, about
1.24 per request. Each broker held about 1.4 TB at the end, including replicas.

Result tuples, one per replayed request:

| | Mean | Lowest minute | Highest minute | Total |
|---|---|---|---|---|
| Tuples accepted for S3 per second | 153,644 | 147,991 | 157,237 | 1,106,234,109 |
| Tuples durable in S3 per second | 153,444 | 147,965 | 163,122 | 1,104,794,294 |

Tuples are batched before upload. The mean time from accepting a tuple to having it durable was
about 30 s.

### Replay lag

Per-minute means over the hold, in seconds:

| | Mean | Median minute | 95th-percentile minute | Highest minute |
|---|---|---|---|---|
| Replay lag (s) | 1.35 | 0.26 | 6.94 | 21.76 |
| Schedule slip (s) | 0.01 | 0.01 | 0.01 | 0.02 |

- **Replay lag** is how far behind the original request each replayed request was sent.
- **Schedule slip** is how far behind the replayer's own speedup schedule it ran.

Both stayed small for the whole run. Lag rose briefly a few times and returned to sub-second levels
within minutes. Kafka backlog stayed even across the 512 partitions:
the largest committed-offset lag on any one partition was usually 30K–70K records, a few seconds of
that partition's traffic.

During the ramp, mean lag was 0.03 s and never exceeded 0.14 s.

### Cluster load, two-hour hold (per-minute values)

| | Source mean | Source highest | Target mean | Target highest |
|---|---|---|---|---|
| Coordinator CPU % (busiest node) | 32.7 | 35.8 | 31.4 | 46.8 |
| Data-node CPU % (busiest node) | 22.1 | 26.8 | 17.3 | 23.1 |
| JVM memory pressure % (busiest node) | 63.2 | 67.2 | 62.4 | 69.0 |

Both domains had substantial headroom at this rate.

### Errors and retries, two-hour hold

| | Total | Per minute (median / highest) |
|---|---|---|
| Replayer retries | 1.85M (0.17% of requests) | 9.9K / 277K |
| Target throttles (IAM-identity concurrency), all retried | 179K | 1.4K / 4.1K |
| Replayer connection errors to the target | 956K | 6.7K / 29K |
| Source 5xx | 28,357 | — |
| Target 5xx | 32,378 | — |
| Target 4xx (mostly the throttles above, returned as 400) | 181,173 | — |

The replayer handled every one of these with retries. None stopped the replay or left a partition
behind.

### Correctness

The replayer compared the source and target status codes for each request, over the two-hour hold:

| Source → target | Requests |
|---|---|
| Matched | 1,104,749,654 |
| `POST 500 → 200` | 32,733 |
| `POST` (no source response) → `200` | 10,076 |
| `POST 4xx → 200` | 8,347 |
| `POST 200 → 500` | 7 |
| `POST 500 → 4xx` | 1 |

Almost every mismatch is a request the source failed and the target then accepted.

### Stability

- No proxy, replayer or Kafka broker restarted during the run.
- All 220 k6 runners exited cleanly and passed their request checks.

## Sizing from this run

Per-unit throughput at the measured utilization. Use these figures as starting points for a
workload with similar requests: single-document bulks, about 3.9 KB with headers, with about a
quarter on new TLS connections. They are not upper limits.

| Unit | Throughput per unit | At | Notes |
|---|---|---|---|
| Capture proxy pod | 1,190 requests/s; 4.4 MiB/s in, 5.2 MiB/s to Kafka | 3.5 cores, 0.65 GiB | about 335 requests/s per core; scales with the client TLS-handshake rate as well as requests |
| Kafka broker | about 15.8K records/s as partition leader; 167 MiB/s of disk writes including replicas | 6.4 cores, 22.6 GiB | disk throughput is the limit. Published bytes were about 1.05 × (request + response bytes); per-broker writes are that × replication ÷ brokers. |
| Kafka storage | about 1.4 TB per broker over 2.5 hours at this rate | — | that is capture bytes × replication ÷ brokers; multiply by your retention window |
| Traffic Replayer pod | 2,400 requests/s; 9.2 MiB/s to the target | 7.6 cores, 9.2 GiB | about 316 requests/s per core; 8 partitions per pod |
| Domain coordinators | about 3,200 requests/s per coordinator node | c8g.8xlarge at 31–33% busiest-node CPU | 48 nodes per domain |
| Domain data nodes | about 3,200 documents/s per data node (with 1 replica) | c8g.4xlarge at 17–22% busiest-node CPU | 48 nodes, one primary shard each |

To size for a different rate:
- Scale the proxy and replayer counts with requests per second.
- Scale Kafka's broker count, or its provisioned disk throughput, with bytes per second.
- Keep the partition count a multiple of both the proxy and replayer counts.

In this run the replayers used 95% of their CPU request, with headroom up to their 12-core limit.
The proxies used 71% of their 5-core limit.

## Configuration this run depends on

These settings were needed to sustain this rate; the defaults fall short.

| Setting | Used in this run | Default | Load observed | Why |
|---|---|---|---|---|
| Kafka volume throughput | 500 MB/s, 8,000 IOPS (`kafka-gp3-provisioned`) | gp3 baseline: 125 MB/s, 3,000 IOPS | about 167 MiB/s written per broker | Above the baseline, produce requests outlast the 10 s delivery timeout and the proxies stop to protect the capture. |
| Kafka volume size | 3 TiB per broker | — | 1.4 TB per broker after 2.5 hours | Retention is 7 days. |
| Proxy CPU | 5 vCPU per pod (640 in total) | 3.5 vCPU | 3.5 cores per pod mean (71%) | Proxies terminate client TLS and open TLS to the source. |
| Partitions | 512 (4 per proxy, 8 per replayer) | — | 2,400 requests/s per replayer pod | Even shares keep every replayer at the same load. |
| Replayer CPU | 8 vCPU requested, 12 limit | — | 7.6 cores per pod mean (95% of the request) | — |
| Node size | 48-vCPU proxy nodes; on-demand everywhere | unconstrained | 15 proxy nodes for 128 pods | Very large instances can exhaust the account's vCPU quota before the fleet is placed; Spot interruptions can stop the run. |
| Zone balance | 43/43/42 proxies across 3 zones | — | — | Uneven zones overload the NLB node of the sparse zone. |

## Caveats

- **The capture averaged 152.5K req/s against 160K offered.** The keep-alive tester reported
  dropped iterations near the end of the ramp, so part of the gap is client-side. The rest was not
  attributed.
- **AWS/ES `2xx` metrics read far below the real request rate on these coordinator domains.**
  Throughput here comes from the proxy and replayer metrics and from document counts.
- **127 of the 128 proxies reported telemetry.** All 128 were running and serving traffic.

## How the numbers were measured

- **Requests, bytes, lag and tuples** come from the proxies' and replayers' own OpenTelemetry metrics
  in CloudWatch, as one-minute sums and means:
  - `fullRequest`, `bytesRead` and `bytesWritten` on the proxies;
  - `receivingResponseCount`, `bytesWrittenToTarget`, `bytesReadFromTarget`,
    `lagBetweenSourceAndTargetRequests`, `scheduleLag`, `tupleComparison` and
    `tupleDurabilityWrites*` on the replayers.
- **Pod CPU and memory** come from cAdvisor.
- **Domain CPU and JVM pressure** come from AWS/ES, using each metric's busiest node.
- **Document counts** come from `_count` on each index after the run.
