# RFS throughput diagnostics

## Question

Find where request throughput is lost when a healthy target has spare capacity and the adaptive in-flight limit is
not binding. Keep load-test behavior fixed while adding enough evidence to separate source/decode work, bulk
preparation, Reactor scheduling, compression, target latency, and response handling.

## Metric-first EKS run

Run the same snapshot, target, worker count, connection limit, and bulk limits after clearing the target index. Align
comparisons at the first target document rather than workflow launch, and exclude incomplete CloudWatch periods at
the end.

Measure these stages without per-document telemetry:

- source operations encoded into an exact-size bulk batch;
- interval between request preparation starts in one worker process;
- NDJSON request-buffer assembly;
- gzip compression;
- adaptive-slot wait and requests in flight;
- HTTP response latency and outcome;
- worker CPU, heap, garbage collection, and restarts;
- target request rate, CPU, JVM pressure, queues, rejections, and disk headroom.

The first four measurements are histograms emitted once per batch or request. They add no spans and no document
identifiers.

Interpret the result as a pipeline:

1. High batch-preparation time points to JSON validation/encoding or transformed-document serialization.
2. Long request-start gaps with low slot wait point upstream to source reads, decode, shard turnover, or Reactor
   scheduling.
3. High assembly or compression time points to request construction in the worker.
4. High HTTP latency with proportional in-flight growth points to the target or network.
5. Low in-flight utilization while request-start gaps grow means more connections will not repair the bottleneck.

## Local profiling

Use Java Flight Recorder on repeatable, synthetic bulk runs with an immediate mocked target and representative
2 MiB, 5 MiB, and 10 MiB requests. Profile the raw and transformed paths separately. Capture CPU samples,
allocation hot spots, garbage collections, thread parks, and Reactor scheduler activity.

Compare the same fixtures across the relevant commits. Local profiling can identify costs in JSON scanning,
metadata serialization, buffer assembly, gzip, allocation, and scheduler handoffs. It cannot reproduce S3 latency,
snapshot-reader behavior, pod placement, or Amazon OpenSearch Service response behavior.

## Trace escalation

Run tracing in EKS if the metric-first run leaves significant request-start gaps unexplained. EKS is required for an
end-to-end answer because the important boundaries include snapshot storage, shard download and decode, Reactor
scheduling, and the real target.

Do not create a span per document. Add spans around:

- shard/range download and decode;
- source batch formation and optional transformation;
- adaptive-slot acquisition;
- request assembly and compression;
- HTTP dispatch and response-body processing;
- checkpoint commit and work-item turnover.

Start with low head sampling, or trace one selected worker at a higher rate, so a 24-worker load test does not make
the collector part of the experiment. Correlate spans with worker identity, work-item phase, operation count, and
byte count, but not customer document identifiers or contents.

## Decision

Use the stage metrics to choose the next code change. Use tracing to explain cross-component gaps, and use local JFR
to explain CPU and allocation within a component. Repeat the unchanged EKS workload after any performance change
before comparing throughput.
