# Observability review — `stableAndScalableLiveReplay`

Working review notes for the final PR pass on the capture/replay hardening branch.
Scope is observability only: logs, metrics, traces. Not correctness, not performance.

- **Branch:** `stableAndScalableLiveReplay`
- **Historical review base:** `upstream/main` (merge-base
  `dcf88fc9688cd6a8cb257d75873692a4b7839195`)
- **Current verification base:** `upstream/main` at
  `1f39f539041df8fc8628d59540c802c9ca52be73`; branch HEAD
  `7346ed974dca8e617e438be930bcef1d52380f43` plus the current remediation
  working tree
- **Historical size reviewed:** 188 commits, 458 files, ~66.9k insertions /
  ~26.7k deletions
- **Reviewed:** 2026-10-05
- **Reconciled:** 2026-10-06
- **Status:** findings reconciled against the current tree; implementation and
  automated verification are complete, with live Kubernetes gameday validation
  still pending.

### Assumptions and operational contract

- **Production traces are optional and must not be required for diagnosis.** They are
  useful when enabled, but the expected production configuration may disable them.
- **Logs are the primary diagnostic channel.** INFO/WARN/ERROR must let an operator
  detect a problem, identify the affected subsystem and form a useful causal
  hypothesis. DEBUG/TRACE must provide a sufficiently complete event journal for a
  developer to reconstruct the system's decisions without exported spans.
- **Metrics are the primary aggregate health channel.** The metric review
  assumes an OTel collector is configured and healthy. Deployments without a
  collector endpoint, where
  `RootOtelContext.initializeOpenTelemetryWithCollectorsOrAsNoop` yields a no-op SDK,
  are still expected to remain diagnosable through logs.
- **The repository does not install alerts or notifications.** Suggested thresholds
  are operator guidance and dashboard coloring only. An owning environment can
  configure alerts separately after validating thresholds through gameday.
- Log records do not need trace or span IDs for this milestone. They do need the
  stable domain identities that connect events across the capture/replay pipeline.
- Span-derived count, duration and exception metrics remain useful even when trace
  export is disabled, but they do not replace the ordered diagnostic narrative that
  logs must provide.
- The deployment and gameday target is Kubernetes running the capture proxy and
  replayer. RFS-specific worker and lease behavior is out of scope.

---

## Current remediation status

The original findings are retained below as historical observations from the review
baseline. They are not statements about the current production object graph. Commit
`7346ed974` supplied the main remediation; the current working tree adds the
second-opinion follow-ups for exact context membership, inner S3 retry observability,
final gauge sampling, owner-operation type visibility, corrected operator surfaces
and no-alert policy hardening.

| Finding | Current status | Production evidence and verification |
|---|---|---|
| O-1 | Remediated | `RootReplayerContext` constructs and production paths use `ResourceOwnershipMetrics`. Covered by `ResourceOwnershipMetricsTest` and `TrafficReplayerTopLevelConstructionTest`. |
| O-2 | Remediated | `CountHook` and its constructor/test plumbing are removed. Registry snapshots, wait reasons, fatal checks and shutdown obligations remain covered by `OutstandingOperationRegistryTest` and `ActiveContextMonitorTest`. |
| O-3 | Remediated | Activity summaries and old-work detail route to both the main replayer log and the dedicated activity log. Covered by `ActiveContextMonitorTest.dedicatedActivityLoggerRoutesToBothRequiredFiles`. |
| O-4 | Remediated | Kafka commit instruments use the bounded `kafkaSource...` production names. Covered by `KafkaCommitStateMetricsTest` and deployment surface verification. |
| O-5 | Remediated | `targetAttemptsWithoutResponse` has bounded `noResponseKind`; `targetRetriesExhausted` is separate. Owner behavior emits structured events. Covered by `TargetExecutionMetricsTest` and `RequestReplayOwnerTest`. |
| O-6 | Remediated | Capture publishing, routing, heartbeat, write-gate and terminal state have metrics and bounded events. Covered by `CaptureKafkaPublisherTest`, membership tests and `CaptureProcessMetricsTest`. |
| O-7 | Remediated | Capture connection drops include connection identity and bounded reason metrics/events. Covered by `ConditionallyReliableLoggingHttpHandlerTest`. |
| O-8 | Remediated | Grafana, CloudWatch, the Kubernetes README and gameday helper expose the production signals. `verifyCaptureReplayObservability` rejects stale names. |
| O-9 | Remediated | Named global and by-activity trackers feed separate collectors and one reporter; owner and immutable Kafka diagnostics remain supplemental. Exact membership now uses `startTimeNano, contextId`, with thread-local ID blocks. Covered by `ActiveContextMonitorTest`, `ActiveContextTrackerTest` and `ContextIdAllocatorTest`. |
| O-10 | Remediated; live gameday pending | Production owners emit bounded `event=<name> key=value` narratives and behavior tests assert critical log and metric effects. The gameday helper has an executable self-test; Kubernetes fault-injection validation remains the release exercise. |

Additional second-opinion corrections now reflected in the tree:

- Inner `RotatingGzipS3ObjectWriter` same-key retries remain indefinite and keep the
  durability future pending. `TupleSinkUploadMetrics` observes retry attempts,
  retrying-object population and positive recovery without repurposing
  `tupleDurabilityRetries`; `S3TupleSinkObservabilityTest` covers multiple failures
  followed by recovery with traces disabled.
- `ActiveContextMonitor.closeAfterOrderlyDrain()` exports a final real collection
  and rejects a nonzero orderly-drain state. Abort/fatal shutdown does not overwrite
  nonzero last values.
- Owner-operation metrics retain aggregate gauges and add bounded, explicitly
  zeroed `operationType` series. Wait reason remains in detailed logs.
- Target dashboard names match production, the nonexistent Prometheus Kafka-lag
  panel is removed, and broker CLI output remains the authoritative lag source.
- The chart installs no alerts or notifications. Deployment verification rejects
  Prometheus/Alertmanager/ACK alarm resources, alert rules and CloudWatch alarm
  widgets/references across every installed dashboard JSON.

## Historical verdict

This verdict describes the 2026-10-05 review baseline, before the remediation above.

The metrics *design* had strong foundations: subsystem-specific metrics adapters,
bounded attributes, and conservation counters that could expose accounting drift.
The correctness tests were also extensive. The weakness was that observability was
not yet enforced as part of those correctness contracts.

Five concerns kept the baseline from being sufficient as-is:

1. **The activity monitor was narrowed to owner-local operation registries while the
   two context indexes remained populated but unread.** Production therefore lost
   its periodic view of Kafka poll/commit/rebalance, Kafka record lifetime, traffic
   stream and HTTP reconstruction activity. This is a High regression under the
   logs-primary operational contract (`O-9`).
2. **Production could silently select behavior-less metrics implementations.**
   `ResourceOwnershipMetrics` was never constructed (`O-1`), and several new
   production constructors defaulted to a local `Metrics.NOOP`. The older, safer
   design selected no-op behavior at the OTel SDK/provider boundary while preserving
   the same production object graph. `CountHook.NOOP` is a separate dead-plumbing
   issue, not evidence of a missing required metric (`O-2`).
3. **The production logging narrative was too sparse when traces were disabled.**
   Several central lifecycle owners had no logger, critical state transitions had
   no INFO/WARN event, and most correctness tests did not assert logs. DEBUG/TRACE
   could not "rebuild the world" (`O-10`).
4. **Critical behavior tests often did not verify their metric side effects.**
   Kafka commit accounting and target-attempt permits were strong, but 11 of 30
   replay-intake metrics had no exact metric assertion; publisher/routing and tuple
   durability had no metrics to assert; protocol-violation tests asserted a callback
   rather than the production OTel adapter.
5. **Capture routing and steady-state publishing remained dark.**
   `CaptureRoutingState` had no logs, metrics or contexts, and
   `CaptureKafkaPublisher` had no steady-state instruments (`O-6`).

Beyond those, several deliberate outcomes — target no-response reason, retry
exhaustion, connection-limit rejection and tuple durability retry — are bounded and
operationally important but were neither aggregate metric dimensions nor consistent
structured log events (`O-5`, `O-7`).

---

## Historical findings

Severity is this review's assessment, not a project convention. Each narrative is
preserved as release-review history; its `Status` and the reconciliation table above
state the current result.

### O-1 — `ResourceOwnershipMetrics` was dead code at the review baseline

| | |
|---|---|
| Severity | High |
| Area | Replayer / buffer ownership |
| Status | Remediated in `7346ed974`; production wiring and tests verified above. |

At the historical review baseline, `ResourceOwnershipMetrics` had zero references
outside its own class declaration and constructor — not even a test. The only way to
install a non-NOOP implementation was `ByteBufListProducer.trackOwnership()`, which
was called only from `ByteBufListProducerTest`. Every production construction path
passed `ResourceOwnership.Metrics.NOOP`.

Consequence: `resourceOwnedHandles`, `resourceOwnedBuffers`, `resourceOwnedBytes`,
`resourceDuplicateCloseAttempts` and `resourceOwnershipInvariantFailures` were not
emitted. Reference-counted buffer leaks are the dominant failure mode for a
Netty-based replayer, and the detector for them is built but unplugged.

Evidence:
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/tracing/ResourceOwnershipMetrics.java:43` — constructor, never called
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/datatypes/ByteBufListProducer.java:28` — field defaults to `NOOP`
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/datatypes/ByteBufListProducer.java:50` — `trackOwnership`, test-only caller
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/datatypes/AttemptPayload.java:33`
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/datatypes/DiagnosticPayload.java:24`

Suggested fix: construct `ResourceOwnershipMetrics` in `RootReplayerContext`
alongside the other four, and thread it to the producer/payload construction sites
so `trackOwnership` is called on the real path.

---

### O-2 — `CountHook` was dead observer plumbing, not a missing metrics system

| | |
|---|---|
| Severity | Low–Medium — misleading and unnecessary, but no production signal is lost |
| Area | Replayer / owner-local asynchronous obligations |
| Status | Remediated in `7346ed974`; registry behavior preserved and tested. |

At the historical review baseline, `OutstandingOperationRegistry` and `CountHook`
should not have been judged as one feature.
The registry is live correctness and diagnostic machinery. It is event-loop
confined, tracks exact asynchronous obligations for a particular owner, checks
duplicate/missing/wrong-registry completions, retains identity and changing
`WaitReason`, and publishes immutable snapshots. Those snapshots are aggregated by
`TrafficReplayerTopLevel.activitySnapshots()` and consumed by the current
`ActiveContextMonitor`.

`CountHook`, by contrast, is an optional observer of local count changes. It does
not affect registration, completion, fatal detection, shutdown gating or snapshot
publication. Passing `NOOP` is therefore functionally valid: the registry still
performs all of its required work. It also explains why the seam can be useful in a
unit test without being a valid production metrics design. The defect is the dead
API and misleading constructor plumbing, not a silently disabled correctness path.

**It had never had a production implementation.** Born `NOOP` in `be74f1169`
("G5: implement event-loop core owners") and still `NOOP`; no commit on this branch
ever supplied a real one. Searched with
`git log -S CountHook upstream/main..HEAD` across all 8 touching commits. This reads
as a seam built for test observation that was never needed in production.

#### Why the registry still matters when contexts exist

The context trackers answer "which execution scopes are still open?" The registry
answers "which exact callback, future, timer, permit or close obligation must this
owner still apply before it can progress or terminate?" These overlap for some work
but are not substitutes.

1. **Most `OperationType` values have no one-to-one context.** Four have useful
   overlap: `REQUEST_PREPARATION`→`transformation`,
   `TARGET_ATTEMPT`→`targetTransaction`, `RETRY_TIMER`→`scheduled`, and
   `TUPLE_DURABILITY`→the logical `comparingResults`/tuple context.
   `PERMIT_ACQUISITION`, `TARGET_WRITE_MILESTONE`, `PHYSICAL_TUPLE_WRITE`,
   `CANCELLATION_CLEANUP`, `REQUIRED_DELIVERY`, `TARGET_CHANNEL_CLOSE`,
   `RETRY_SOURCE_RESPONSE_WAIT` and `FINAL_SOURCE_RESPONSE_WAIT` are internal async
   obligations with no dedicated context. The registry's changing `WaitReason` has
   no context equivalent either.

2. **The registry is deliberately owner-local, not system-wide.** Production creates
   registries for target-connection, request-replay and tuple-writer owners. Kafka
   poll, rebalance, record lifetime and commit progression are outside that owner
   population. "No registry entry" therefore means "not an owner-local async
   obligation represented here," not "untracked."
3. **Kafka has its own stronger domain authorities.** `KafkaSourceOwner`,
   `ObservedRecordCommitQueue`, `inFlightCommitOperation`, Kafka record/commit
   contexts and commit/conservation metrics track why a partition offset can or
   cannot advance. Duplicating that state in this registry would make the diagnostic
   model less clear, not more complete.

#### Why `CountHook` should be removed

1. **It cannot be safely aggregated across registries.**
   `operationCountChanged(type, typeCount, totalCount)` reports absolute counts local
   to one registry, but does not identify that registry. A single shared adapter
   cannot distinguish "registry A changed from 1 to 0" from "registry B still has
   1," and an up-down counter cannot treat the reported absolute value as a delta.
2. **It omits wait-reason transitions.** `updateWaitReason` republishes the
   diagnostic snapshot with `publish(null)`, which deliberately skips `CountHook`.
   Even a correct operation-type gauge would therefore be unable to report
   `WAITING_FOR_PERMIT` moving to `WAITING_FOR_TARGET`, the most useful distinction
   during a stall.
3. **A second push-based reporting API is unnecessary.** Each registry already
   publishes immutable snapshots, and `TrafficReplayerTopLevel.activitySnapshots()`
   already aggregates those snapshots across connection, request and tuple-writer
   owners. `ActiveContextMonitor` already scans that aggregate periodically.
   Operation and wait-reason counts can therefore be derived at the monitoring
   boundary without threading another callback through every owner.

So: keep the registry — it tracks real async waits that contexts don't model — but
remove `CountHook`. If aggregate operation metrics prove useful, derive them from
the existing snapshot supplier in `ActiveContextMonitor` or a sibling monitor.
There is no production signal to preserve from the hook because every production
site currently passes `NOOP`.

Evidence:
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/TrafficReplayerTopLevel.java:1239`
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/TrafficReplayerTopLevel.java:1332`
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/lifecycle/OutstandingOperationRegistry.java:39-66` — `OperationType` (12) and `WaitReason` (11)
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/lifecycle/OutstandingOperationRegistry.java:73` — interface, `NOOP` at `:74`
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/kafkasource/KafkaSourceOwner.java` — Kafka-domain progression and heartbeat state
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/kafkasource/ObservedRecordCommitQueue.java` — per-partition record/commit authority

Suggested fix: delete `CountHook` and its constructor plumbing. Keep
`OutstandingOperationRegistry` snapshots and all invariant/fatal behavior. Consider
renaming it to `OwnerOutstandingOperationRegistry` in a later cleanup if that avoids
the false impression that it is system-wide. If aggregate operation metrics prove
useful, derive them periodically from `activitySnapshots()` at the monitoring
boundary; do not patch or replace `CountHook`.

---

### O-3 — Stall warnings did not reach the main log

| | |
|---|---|
| Severity | High |
| Area | Replayer / logging config |
| Status | Remediated in `7346ed974`; dual-route test verified above. |

The baseline registry-backed `ActiveContextMonitor` was the branch's only periodic
stall detector. Its WARN output went to the `AllActiveWorkMonitor` logger, which was
configured with
`additivity = false` and a single appender reference to `AllActiveWorkMonitorFile`.
So "Long-running replay activity: …" lands only in
`logs/longRunningActivity/longRunningActivity.log` — not stderr, not `replayer.log`.

The 30s status line (`active=` / `longRunning=`) went to the same file under a
`%msg%n` pattern, so it carries no level and no log timestamp (the `observedAt`
field in the message is the only time reference).

`log4j2.properties` is byte-identical to `upstream/main`, so this is inherited
config — but the rewrite promoted this logger from a debugging aid to *the* stall
signal, which changes whether the routing is acceptable.

Evidence:
- `TrafficCapture/trafficReplayer/src/main/resources/log4j2.properties` — `logger.AllActiveWorkMonitor.*` block at end of file
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/util/ActiveContextMonitor.java:31,33,178,187`

Suggested fix: send monitor output to both `ReplayerLogFile` and the dedicated
`AllActiveWorkMonitorFile`, filtering the periodic INFO status line if necessary.

---

### O-4 — The metric naming convention split mid-branch

| | |
|---|---|
| Severity | Medium — cheap now, breaking later |
| Area | Replayer / metric names |
| Status | Remediated in `7346ed974`; adapter and deployment tests verified above. |

At the historical review baseline, `KafkaCommitStateMetrics` used unprefixed
`snake_case`. Every other new metrics class used prefixed `camelCase`.

| Class | Convention | Examples |
|---|---|---|
| `ReplayIntakeMetrics` | `camelCase`, `replayIntake` prefix | `replayIntakeRecordsApplied` |
| `TargetAttemptPermitMetrics` | `camelCase`, `targetAttemptPermit` prefix | `targetAttemptPermitsActive` |
| `ProtocolViolationMetrics` | `camelCase`, `replayProtocolViolation` prefix | `replayProtocolViolationDrainsStarted` |
| `ReplayProcessFatalMetrics` | `camelCase`, `replay` prefix | `replayFatalFailures` |
| `ResourceOwnershipMetrics` | `camelCase`, `resource` prefix | `resourceOwnedBytes` |
| `CaptureProcessMetrics` | `camelCase`, `captureProcess` prefix | `captureProcessPassThroughTransitions` |
| **`KafkaCommitStateMetrics`** | **`snake_case`, no prefix** | **`records_read`, `records_outstanding`** |

All of these register on the same `replayer` meter scope, so a bare `records_read`
is ambiguous against `replayIntakeRecordsApplied`. Renaming after release breaks
dashboards and external consumers, so this is much cheaper to settle pre-merge.

Evidence: `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/tracing/KafkaCommitStateMetrics.java:33-41`

Suggested fix: rename to `kafkaSourceRecordsRead` etc., or state the snake_case
convention explicitly and migrate the others. Either way, write the convention down
(see `O-8`).

---

### O-5 — There was no aggregate dimension for *why* target attempts failed

| | |
|---|---|
| Severity | Medium |
| Area | Replayer / request outcomes |
| Status | Remediated in `7346ed974`; production names corrected in current surfaces. |

At the historical review baseline, `NoTargetResponseKind` — `READ_TIMEOUT`,
`TRANSPORT_FAILURE`, `MISSING_HTTP_RESPONSE` — was produced in
`NettyPacketToHttpConsumer` and reached the per-tuple JSON output under the exception
key, so single-request diagnosis worked. It was not a counter attribute.

Likewise `RetryDecision.TargetServerAttemptsFinished` (retry exhaustion) was not
counted; `numRetries` counted attempts made, and nothing counted giving up.

The progress-summary line renders a no-response attempt as response size `0` and
drops the diagnostic entirely.

Consequence: "what fraction of replays are read-timing-out vs. connection-failing
vs. returning no response?" requires grepping tuple logs, which at production
volume means gigabytes.

Evidence:
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/lifecycle/ReplayOutcomes.java:73` — enum declared
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/datahandlers/NettyPacketToHttpConsumer.java:765,1231,1241` — produced
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/ParsedHttpMessagesAsDicts.java:97` — reaches tuple JSON
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/ResultsToLogsConsumer.java:180` — diagnostic dropped from summary
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/http/retries/OpenSearchDefaultRetry.java:315,320,328` — exhaustion, uncounted

Suggested fix: `NoTargetResponseKind` is a 3-value enum, so it is safe as an
attribute on the existing `tupleComparison` counter or on a new
`targetAttemptsWithoutResponse` counter. Add a counter for retry exhaustion.

---

### O-6 — `CaptureRoutingState` was dark; capture had no steady-state publish metrics

| | |
|---|---|
| Severity | **High** for `CaptureRoutingState`, Medium for the rest |
| Area | Capture proxy / Kafka offloader |
| Status | Remediated in `7346ed974`; capture behavior tests verified above. |

Unlike the replayer owners — which get count, duration and exception metrics free
from span open/close — the capture-side state machines created no contexts at the
historical baseline, so nothing was emitted implicitly. Whatever they did not report
explicitly was invisible.

**`CaptureRoutingState` (647 lines) then had zero logs, metrics, spans or contexts.**
This was the routing-generation state machine the new scaling model turned on.
Generation changes, partition-set changes and writer-lane assignment were
unobservable, so a capture/replay gap could not be correlated against the routing
change that caused it. The nearest available signals were two INFO lines elsewhere — the
publisher's `"Retired proxy writer {} for partition {}"` (`CaptureKafkaPublisher.java:684`)
and `CaptureKafkaMembership`'s `"Initialized proxy routing writer {} for partitions {}"`
(`:99`) — which cover initialization and retirement but not the transitions between.

**`CaptureKafkaPublisher` (1063 lines) then had no steady-state instruments.** No records or
bytes published, no send failures, no heartbeat acknowledgement-deadline expirations,
no write-gate rejections, no lane-retirement counts. Its entire logging budget across
1063 lines is 2 `error`, 1 `warn`, 1 `info`.

`CaptureProcessMetrics` is the only new capture-side instrument class, and it covers
exactly two transitions (pass-through, termination) plus the `captureGapActive`
up-down counter.

The terminal-failure *chain* is well covered — ack-deadline expiry → `failPublisher`
→ ERROR with cause → write gate trips → pass-through → `captureProcessPassThroughTransitions`
+ `captureGapActive`. The gap is steady-state health and routing-change correlation,
not catastrophic failure.

Evidence:
- `TrafficCapture/captureKafkaOffloader/src/main/java/org/opensearch/migrations/trafficcapture/kafkaoffloader/CaptureKafkaPublisher.java:684,916,950,1038` — the only log sites
- `TrafficCapture/captureKafkaOffloader/src/main/java/org/opensearch/migrations/trafficcapture/kafkaoffloader/CaptureRoutingState.java` — whole file
- `TrafficCapture/trafficCaptureProxyServer/src/main/java/org/opensearch/migrations/trafficcapture/proxyserver/CaptureProcessMetrics.java:27-29`

---

### O-7 — New connection-drop paths were not countable or attributable

| | |
|---|---|
| Severity | Medium |
| Area | Capture proxy / netty handler |
| Status | Remediated in `7346ed974`; bounded reason metrics/events are tested. |

At the historical review baseline, `IncompleteRequestLimits` violations and
max-connection-duration closes logged at WARN and closed the channel. There was no
counter, no connection id in the message, and no MDC anywhere in
`LoggingHttpHandler`. An operator could learn that drops happened, but not how many,
at what rate, or to which client.

These are new limits that silently terminate client connections — exactly the kind
of change that generates "why did my client get disconnected?" escalations.

Evidence:
- `TrafficCapture/nettyWireLogging/src/main/java/org/opensearch/migrations/trafficcapture/netty/LoggingHttpHandler.java:271` — max connection duration
- `TrafficCapture/nettyWireLogging/src/main/java/org/opensearch/migrations/trafficcapture/netty/LoggingHttpHandler.java:567` — limit violation (does name which limit)
- `TrafficCapture/nettyWireLogging/src/main/java/org/opensearch/migrations/trafficcapture/netty/LoggingHttpHandler.java:664` — assembly duration
- `TrafficCapture/nettyWireLogging/src/main/java/org/opensearch/migrations/trafficcapture/netty/IncompleteRequestLimits.java` — defaults: 5min / 10MB headers / 250MB total

Suggested fix: add the connection id to all three messages, and a counter attributed
by limit kind (bounded: `assemblyDuration`, `headerBytes`, `totalBytes`,
`connectionDuration`).

---

### O-8 — ~57 new metric names had no operator reference

| | |
|---|---|
| Severity | Medium |
| Area | Docs |
| Status | Remediated in `7346ed974` plus current operator-surface corrections. |

At the historical review baseline, no document enumerated the new metrics, their
units, their attributes, or which ones were expected to return to zero.
`docs/captureReplayHardeningChangelog.md` had no observability section. The only doc
that mentioned any new metric name was
`docs/captureAndReplay/asyncMessagePassingProgrammingGuide.md`.

Dedicated adapter tests exist for three of six new replayer metrics classes
(`KafkaCommitStateMetricsTest`, `ReplayProcessFatalMetricsTest`,
`TargetAttemptPermitMetricsTest`) plus `CaptureProcessMetricsTest`.
Replay-intake behavior tests reference 19 of its 30 metric names, but 11 remain
unasserted. `ProtocolViolationTerminatorTest` asserts an injected callback rather
than the production `ProtocolViolationMetrics` instruments.
`ResourceOwnershipMetrics` has no test or production construction path.

The critical-case test scan is mixed:

| Area | Correctness tests | Do those tests assert metrics? | Assessment |
|---|---|---|---|
| Kafka record/commit conservation | extensive `KafkaSourceOwnerTest` and queue tests | yes: behavior tests assert read/committed/cancelled/ineligible/outstanding deltas, and `KafkaCommitStateMetricsTest` verifies the adapter | strongest area; still add top-level production-wiring coverage |
| target-attempt permits | cancellation, failure, acquisition/release and duration cases | yes: provider tests assert the callback state and `TargetAttemptPermitMetricsTest` verifies the adapter | strong |
| replay intake/reconstruction | extensive owner, source reconstruction and accumulator tests | partial: 19 of 30 metric names are asserted | add exact assertions for the 11 listed in Phase 3 |
| protocol violation | drain/termination behavior is tested | callback only; the production OTel adapter is not exercised | deny production metric coverage |
| resource ownership | ownership, close and invariant behavior is tested with a fake callback | no `ResourceOwnershipMetrics` adapter or production construction test | deny production metric coverage |
| capture publisher/routing | extensive state-machine tests | no steady-state metric exists to assert | observability hole, not a missing test assertion |
| tuple durability | writer behavior and owner obligations are tested | no tuple-durability metric exists to assert | observability hole; registry coverage is not a substitute |
| activity/operation monitoring | current registry snapshots and log cadence are tested | no context-population metric; the current monitor tests only the registry-backed rewrite | confirms `O-9` rather than mitigating it |

The distinction matters: a fake domain callback proves that the owner invokes an
observer; it does not prove that the production root installs the OTel adapter or
that the intended instrument receives a point.

See the [metric inventory](#appendix-a--new-metric-inventory) below as raw material.

---

### O-9 — The activity-monitor rewrite disconnected both live context indexes

| | |
|---|---|
| Severity | High — production lost context-level stall diagnosis |
| Area | Replayer / context monitoring and logging |
| Status | Remediated in `7346ed974`; exact-membership follow-up is in the current working tree. |

Found while answering "why does `CountHook` exist?" (`O-2`).

At the historical review baseline, `RootReplayerContext:47` still installed
`new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())`,
so **every context create and close** in the replayer paid:

- `ActiveContextTracker` — insert/remove on a `ConcurrentSkipListSet`
- `ActiveContextTrackerByActivityType` — `ConcurrentHashMap` lookup plus
  insert/remove on a per-type `ConcurrentSkipListSet`

Nothing read either one. In `upstream/main` they were inputs to context monitoring;
the rewrite had changed `ActiveContextMonitor` to accept only a
`Supplier<List<OutstandingOperationRegistry.Snapshot>>`. The supplied
`TrafficReplayerTopLevel.activitySnapshots()` aggregated target-connection,
request-replay and tuple-writer registries. It did not read the context indexes.

This is more than wasted hot-path work. It removes production visibility for every
activity that is represented by a context but not by one of those owner registries:
Kafka poll, commit and rebalance; Kafka record lifetime; traffic-stream lifetime;
HTTP transaction reconstruction; request/response accumulation; and other nested
execution phases. These contexts are still tracked when trace export is disabled,
so reconnecting the monitor does not depend on spans being exported.

More precisely, the replayer has **two populations represented by three objects**:

- `ActiveContextTracker` and `ActiveContextTrackerByActivityType` hold the same open
  context objects in two indexes: globally by age and by concrete context class.
- `OutstandingOperationRegistry` holds a different population: asynchronous
  obligations that must be applied before an owner can progress or terminate.

The overlap is intentional but incomplete. The third column is the domain authority
that can explain progression when neither generic population is sufficient:

| Work | Open-context indexes | Owner operation registry | Domain authority |
|---|---|---|---|
| Kafka poll / rebalance | yes | no | `KafkaSourceOwner` consumer state |
| Kafka record lifetime | `KafkaRecordContext` | no | `ObservedRecordCommitQueue` and record disposition |
| Kafka commit execution | commit contexts | no | `KafkaSourceOwner.inFlightCommitOperation` and commit queue |
| traffic stream / HTTP reconstruction | record descendants | no | replay-intake/source-connection state |
| request / response accumulation | accumulation contexts | no | source-connection reconstruction state |
| request preparation | `transformation` | `REQUEST_PREPARATION` | `RequestReplayOwner` |
| target attempt | target plus connect/send/wait/receive descendants | `TARGET_ATTEMPT` | target/request owner state |
| retry timer | `scheduled` | `RETRY_TIMER` | request owner retry state |
| tuple durability | tuple/comparison context | logical and physical tuple writes | `TupleWriter` |
| permit / source-response / required-delivery / channel-close waits | usually no dedicated context | yes | corresponding owner/provider |

`no` in the registry column does **not** mean untracked. It means that the activity
is represented by contexts or by a domain-specific state machine rather than by an
owner-local async-obligation registry.

The two context indexes also retain strong references to every unclosed context.
That is intentional for leak/stall diagnosis once they have a consumer. Their cost
remains even when trace export is disabled because the context objects and tracker
callbacks still run.

#### Expected collectors and combined reporter

Use separate read-only collectors feeding one combined reporter:

1. A context-activity collector consumes both context indexes and produces total,
   by-activity, age and parent-chain views.
2. An owner-operation collector consumes the immutable
   `OutstandingOperationRegistry` snapshots and produces exact owner-wait detail.
3. A Kafka diagnostic collector reads an immutable snapshot published by
   `KafkaSourceOwner` and explains commit eligibility/progression.
4. The combined reporter periodically emits one bounded summary containing total
   open contexts, count by bounded activity name, globally oldest age, oldest age by
   activity and over-threshold counts.
5. The reporter always identifies the one globally oldest `KafkaRecordContext` with
   age, topic, partition, generation and offset. Do not add a per-partition oldest
   view: sustained stuck work is exceptional, and the global oldest should expose it.
6. For an old record, the context collector finds active descendants by scanning the
   global set and walking each context's `getEnclosingScope()` ancestor chain. Parent
   links already exist; adding mutable child collections to every context is
   unnecessary.
7. The reporter distinguishes whether work is in reconstruction, accumulation,
   scheduling, permit acquisition, target I/O, response waiting or tuple durability.
   Emit bounded detailed WARN entries for old work, including stable domain
   identities and the parent chain.

There is one important boundary: Kafka commit scopes are not descendants of a
particular `KafkaRecordContext`. Descendant scanning explains a record through HTTP
processing and tuple durability. Once a record has no active descendant, the monitor
must join the record identity with an immutable diagnostic snapshot published by
`KafkaSourceOwner` from its owner thread. That snapshot should incorporate
`ObservedRecordCommitQueue`, staged/in-flight commit and uncertainty state so the
monitor can distinguish "processing complete, awaiting eligibility," "commit in
flight/uncertain," and terminal committed, commit-ineligible or
generation-ended-uncommitted outcomes. The monitor runs on its own scheduled thread
and must not read the owner-confined queue or maps directly. Domain conservation
metrics may further classify cancellation or abandonment.

`KafkaSourceOwner`'s existing periodic heartbeat is a **logging heartbeat**, not a
Kafka consumer-group protocol heartbeat. It reports valuable generation, partition,
commit-head/tail, unfinished-record and commit-uncertainty state and should be kept,
but it is supplementary: it cannot replace the context monitor's execution-phase
and parent-chain view.

If counts are exported as observable gauges, explicitly report zero for every known
activity type; iterating only `getActiveScopeTypes()` would make an empty series
disappear rather than return to zero. At the review baseline, `numScopesFor()` also
assumed that the type had previously been observed. The remediation instead derives
the count from the same captured context list used for age calculation, and the
obsolete accessor has been removed.

Use hard-coded initial age thresholds. Tune them from gameday evidence rather than
adding configuration machinery in the first implementation.

Recommended fix:

1. **Retain and reconnect both context indexes.** Once the per-type tracker is kept,
   the global tracker's incremental cost is one additional skip-list insert/remove
   per context. In return it provides an efficient total population and a globally
   oldest-first view without merging every per-type set.
2. **Give each index a distinct consumer.**
   - Use `ActiveContextTracker` for total active contexts and the globally oldest
     diagnostic entries.
   - Use `ActiveContextTrackerByActivityType` for active count and oldest age by
     bounded activity name, plus per-stage threshold breaches.
   Periodically log threshold breaches with domain identities and export the bounded
   aggregate metrics.
3. **Keep `OutstandingOperationRegistry` as owner-local correctness machinery, and
   remove `CountHook`.** Keep detailed long-running-operation snapshots. Derive
   aggregate operation populations from those snapshots only if gameday shows they
   add value beyond context and domain metrics.
4. **Keep both trackers without a pre-release performance gate.** This visibility is
   important enough to retain. If an observed performance problem is attributable to
   the indexes, profile it then and trade other work, capacity or replica count
   before removing the diagnostic surface.

This restores three complementary questions:

- context monitor: **which execution phase/context is old?**
- owner registry: **which exact async owner obligation remains?**
- Kafka domain state: **why can this partition offset not advance?**

Evidence:
- `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/tracing/RootReplayerContext.java:5,6,47`
- `coreUtilities/src/main/java/org/opensearch/migrations/tracing/ActiveContextTrackerByActivityType.java:21-53`
- `git grep -ln ActiveContextTracker upstream/main -- TrafficCapture/trafficReplayer/src/main` → `TrafficReplayer.java`, `ActiveContextMonitor.java`; on `HEAD` → `RootReplayerContext.java` only

---

### Strengths to preserve

Worth preserving explicitly so none of it gets refactored away:

- **Cardinality discipline is deliberate and documented.** Every new metrics class
  has a javadoc paragraph stating which dimensions are attached and which identities
  are deliberately kept out. Attributes are enum-derived throughout.
- **Conservation is checkable by construction.** Up-down counters are paired with
  separate addition and removal counters, so a residual can be attributed rather
  than merely observed: `replayIntakeRetryReadySupplyAdditions` /
  `…Removals` beside `replayIntakeRetryReadyRequestSupply`; `records_read` /
  `records_committed` / `records_cancelled` / `records_abandoned_at_revocation` /
  `records_commit_ineligible` beside `records_outstanding`.
  `ReplayIntakeMetrics.retryReadySupplyChanged` even rejects deltas other than ±1.
- **The current registry-backed monitor report is an excellent supplemental
  diagnostic.** It carries partition, generation, record, captured connection,
  connection processing id, request, owner, operation type, elapsed, wait reason and
  scheduled target time. Preserve that exact owner-wait detail while restoring the
  broader context monitor.
- **Observability failures can't perturb behavior.** `ResourceOwnership.Tracker`
  swallows metric-callback throwables and tracks whether the positive accounting
  actually landed before publishing the matching negative.
- **Span tree preserved and extended, and spans carry their own metrics.** Each
  context emits `<activity>Count` / `<activity>Duration` / `<activity>ExceptionCount`
  with no explicit call, so the new owners get count, latency and error rate per
  lifecycle stage for free — this is why an explicit-`metrics.`-call audit
  understates their coverage. `addTraceException` on connect and socket failure paths
  (`NettyPacketToHttpConsumer.java:373,406,417,467,595`); `scheduleLag` and
  `lagBetweenSourceAndTargetRequests` retained; `tupleComparison` still carries
  source/target status categories and match.
- **Span attributes carry the full correlation chain.** `HttpTransactionContext.fillAttributesForSpansBelow`
  puts topic, partition, generation, writer node, connection and connection lifetime
  onto every span beneath it, so a single trace is enough to tie a replayed request
  back to its captured origin.
- **Capture-side startup is well logged** — partition provisioning, leader probing
  races, membership join, metadata unavailability, terminal failure, all with causes.

---

### O-10 — Production and developer logs did not form a complete event narrative

| | |
|---|---|
| Severity | High |
| Area | Capture and replay / logging |
| Status | Remediated in `7346ed974` plus current S3 retry events; live gameday pending. |

At the historical review baseline, the drop in log sites was a material finding
under the operating assumption that production traces may be disabled and logs are
the primary diagnostic channel.

Replayer `src/main` grew from 17,564 to 28,423 lines while log call sites fell from
231 to 64.

| Level | `upstream/main` | `HEAD` |
|---|---|---|
| error | 25 | 6 |
| warn | 33 | 7 |
| info | 29 | 11 |
| debug | 64 | 23 |
| trace | 80 | 17 |

Keeping pure decision functions free of logging is intentional and good.
`PartitionIntakeState`, for example, should continue returning decisions to
`ReplayIntakeOwner`; the owner should emit the event after applying the decision.
The gap was that many owners did not perform that reporting.

**Reading the table below:** an explicit `metrics.` call count badly understates
coverage, because every context open/close emits metrics on its own. Creating and
closing a context emits `<activity>Count` and `<activity>Duration`, and
`addTraceException` emits `<activity>ExceptionCount` — no explicit call needed. So
the "context refs" column is a metrics column too.

| Class | Lines | Logs | Explicit `metrics.` calls | Context refs → span-derived metrics |
|---|---|---|---|---|
| `RequestReplayOwner` | 1820 | 0 | 0 | 70 — emits `transformation*`, `scheduled*`, `targetTransaction*`, `comparingResults*`, plus `numRetries`, `scheduleLag`, `tupleComparison` |
| `TargetConnectionOwner` | 1734 | 0 | 0 | 32 — emits `scheduled*` for admission and execution timers |
| `NettyPacketToHttpConsumer` | 1371 | 0 | 0 | 44 — emits `requestConnecting*`, `requestSending*`, `waitingForResponse*`, `receivingResponse*`, `tcpConnection*`; 5 `addTraceException` sites |
| `PartitionIntakeState` | 1200 | 0 | 0 | 0 — pure decision function, owner reports |
| `ReplayIntakeOwner` | 937 | few | 41 | 7 |
| `SourceConnectionState` | 835 | 1 warn | 0 | — |
| `CaptureRoutingState` | 647 | 0 | 0 | **0 — nothing, in any configuration** |

No logger is declared at all in `RequestReplayOwner`, `TargetConnectionOwner`,
`NettyPacketToHttpConsumer` or `CaptureRoutingState`. `ReplayIntakeOwner`,
`PartitionIntakeState`, `TargetAttemptPermitProvider`, `CaptureKafkaWriteGate` and
`TupleWriter` also provide little or no operational narrative around critical
decisions.

Consequences:

- An operator cannot reliably distinguish intake starvation, target starvation,
  retry waiting, tuple-durability waiting and Kafka commit uncertainty from the main
  log.
- Capture generation preparation, activation, draining and routing changes cannot
  be reconstructed.
- The DEBUG/TRACE stream omits many of the exact decisions needed to replay the
  lifecycle mentally: input application, request reconstruction, retry selection,
  target-attempt completion, tuple durability and commit resolution.
- Log usefulness is largely untested. Apart from isolated Kafka heartbeat,
  retirement, monitor and TLS-dedup tests, critical behavior suites can remain green
  after their diagnostic log events are removed.

Suggested fix: adopt the logging contract and event inventory in the remediation
plan below. Emit events from lifecycle owners after decisions are applied, use
bounded event names and domain identities, keep high-volume records at DEBUG/TRACE,
and assert important event names and fields in the corresponding behavior tests.

---

## Remediation plan

The work should be split into small, independently reviewable changes. Tests for a
signal belong in the same change that introduces or rewires that signal; testing is
not a final cleanup phase.

### Definition of done

The observability work is complete when all of the following are true:

1. With trace export disabled, a healthy run and every gameday failure below can be
   detected and diagnosed using only metrics plus INFO/WARN/ERROR logs.
2. DEBUG/TRACE logs can reconstruct the relevant state-machine decisions for one
   selected capture connection or replayed request.
3. No production constructor silently substitutes a subsystem `Metrics.NOOP`.
   Telemetry-disabled behavior is selected at the OTel provider boundary.
4. Every operationally critical behavior either emits a metric event or changes a
   metric-observable population/state, and its behavior test asserts that exact
   effect. Operator-important transitions also assert their structured log event.
5. Every up-down counter has a tested conservation equation and returns to zero
   after clean shutdown.
6. Metric names, units, descriptions, dimensions and suggested investigation
   thresholds are documented without installing alerts or notifications.
7. An operator who did not implement the feature can identify the injected gameday
   fault and the affected identity chain within ten minutes.
8. INFO-level logging remains bounded during healthy steady state and during a
   prolonged failure.
9. With traces disabled, the monitor can identify the oldest Kafka record, its
   active execution descendants, any supplemental owner obligation and the Kafka
   domain state that prevents its offset from advancing.

### Phase 0 — Freeze the observability contract

Do this before adding instruments so names and dimensions do not drift across PRs.

- Adopt `event=<bounded_event_name>` as the stable beginning of operational log
  messages. The rest of the message may remain human-readable key/value text; a
  JSON logging migration is not required.
- Define the standard identity fields:
  - process: `processId`, `service.instance.id` and Kubernetes pod identity when
    available
  - capture: `captureActivationId`, `writerNodeId`
  - Kafka: `topic`, `partition`, `generation`, `offset`
  - traffic: `connectionId`, `connectionLifetime`, `requestOrdinal`
  - execution: `attempt`, `operationType`, `waitReason`
- Require only identities available at the reporting boundary. Do not manufacture
  IDs or put high-cardinality identities on aggregate metrics.
- Set the level policy:
  - INFO: startup summaries, lifecycle transitions, periodic health and shutdown
    summaries
  - WARN: degraded operation that may recover or intentionally discards work
  - ERROR: terminal process/subsystem failure or violated safety invariant
  - DEBUG: per-record/request decisions and asynchronous state transitions
  - TRACE: per-observation, chunk, scheduling and I/O callback detail
- Settle the metric naming convention before release. Recommended:
  component-prefixed camelCase, including renaming the eight bare
  `KafkaCommitStateMetrics` names to `kafkaSource...`.
- Add descriptions and units to every new instrument.

#### `service.instance.id` decision

`ProcessHelpers.getNodeInstanceName()` returns hostname plus a random UUID. In the
Kubernetes deployment this identifies one JVM lifetime; the hostname component will
normally identify the pod while the UUID distinguishes restarts.

The `service.instance.id` meter-resource change is expected to land on mainline
shortly. Once merged into this branch it fixes trace/metric resource asymmetry, but
current AWS collector configurations enable `resource_to_telemetry_conversion` with
`NoDimensionRollup`. It can therefore turn every metric into a per-JVM CloudWatch
series.

Immediately after taking that mainline merge:

1. Render or inspect the actual collector configuration used by each deployment.
2. Decide which metrics genuinely need per-pod/instance dimensions.
3. Preserve fleet-aggregate metric series for dashboards and operator queries.
4. Verify existing dashboards against a multi-pod Kubernetes deployment.
5. Add a short metric-cardinality test or deployment-config assertion so a collector
   change cannot silently multiply every series.

Recommended default: keep the restart-scoped instance ID in logs and the OTel
resource, but do not make it an implicit CloudWatch dimension on every fleet metric.

Execution checkpoint (2026-10-06): upstream commit `a5705abb8` landed the
`service.instance.id` metric-resource change and was incorporated through merge
commit `a3275f79a` before telemetry wiring changed. The EKS application-metric
pipeline removes `service.instance.id` before `resource_to_telemetry_conversion`;
`deployment/k8s:verifyCaptureReplayObservability` now enforces that fleet-cardinality
safeguard and the traces-disabled Kubernetes default.

#### Mainline integration checkpoint

Do not independently reimplement the pending `service.instance.id` change on this
branch. Finish and checkpoint the review/contract document, then:

1. Wait for the identified change to land on mainline.
2. Merge mainline into this long-lived branch before Phase 1 changes
   `RootReplayerContext`, metrics construction or constructor wiring.
3. Resolve and test the resource/dimension behavior as part of the observability
   foundation change.
4. Record the exact mainline commit in the review so the cardinality decision is
   reproducible.

Taking the merge before Phase 1 avoids resolving the same root-context and telemetry
wiring conflicts twice. Taking it only before gameday would be too late: dashboards,
tests and metric dimensions might already have been designed around behavior that
the merge changes.

### Phase 1 — Restore activity monitoring and remove silent NOOP wiring

Restore the older safety property: production and telemetry-disabled deployments use
the same object graph, and restore the context-level diagnostic surface before
adding more subsystem signals.

1. Change `RootReplayerContext` to retain named instances of
   `ActiveContextTracker` and `ActiveContextTrackerByActivityType` while continuing
   to install their composite as the context tracker.
2. Add separate context-activity, owner-operation and Kafka diagnostic collectors
   feeding one combined reporter. Preserve the current registry-snapshot detail.
3. Construct every subsystem metrics adapter in the appropriate root context.
4. Make the adapter a required argument in canonical production constructors.
5. Remove or make test-only/package-private constructor overloads that substitute
   `Metrics.NOOP`.
6. When metric export is disabled, supply a no-op OTel `MeterProvider`; do not
   substitute a behavior-less domain callback.
7. Add a production-wiring test that creates the real top-level object graph with an
   in-memory meter, executes one event per subsystem, and verifies the expected
   instruments receive data.
8. Add an architecture check with a narrow allowlist that rejects new
   `Metrics.NOOP` references and any reintroduction of `CountHook` in production
   source.

Specific changes:

- Construct and wire `ResourceOwnershipMetrics` through
  `ByteBufListProducer`, `AttemptPayload` and `DiagnosticPayload`.
- Remove `CountHook` and its constructor plumbing. Preserve
  `OutstandingOperationRegistry`, its immutable snapshots and all invariant/fatal
  behavior. Optionally rename it `OwnerOutstandingOperationRegistry` in a separate
  cleanup.
- Replace callback-only production wiring for `ProcessSupervisor`,
  `ProtocolViolationTerminator`, `ReplayIntakeOwner`, `KafkaSourceOwner` and
  `TargetAttemptPermitProvider` with root-context adapters.
- Keep `ReplayIntakeOwner.RecordObserver` as an optional functional seam, but rename
  its sentinel to `NONE` or `DISCARD` so it is not confused with missing telemetry.
- Retain both `ActiveContextTracker` and
  `ActiveContextTrackerByActivityType`, but reconnect them to total/global-oldest and
  per-activity metrics/logging respectively.
- Have the context monitor scan the global set and walk ancestor chains to find
  active descendants of the oldest Kafka record. Join records without active
  descendants to an immutable, owner-published `KafkaSourceOwner` diagnostic snapshot
  for commit eligibility/execution diagnosis; never read owner-confined mutable state
  from the monitor scheduler.
- Keep the existing `KafkaSourceOwner` periodic logging heartbeat as a separate,
  supplementary Kafka-domain summary.
- Add tests proving contexts are removed from both indexes.

Exit gate: a source scan has no production metrics NOOP references outside the
documented allowlist; a wiring test fails if any subsystem adapter or context-monitor
input is omitted; and a traces-disabled test diagnoses both a context-only stall and
an owner-registry-only wait.

### Phase 2 — Capture-side observability

Report transitions from `CaptureKafkaPublisher`, `CaptureKafkaMembership` and the
proxy owner after they apply `CaptureRoutingState` decisions. Keep
`CaptureRoutingState` itself deterministic and log-free.

#### Metrics

Add a capture publishing/routing metrics adapter covering:

- records and bytes accepted for publishing
- publish completions by bounded outcome
- records and bytes currently awaiting acknowledgement
- heartbeat submissions, acknowledgements and deadline violations
- routing generation transitions by transition kind
- active, draining and retired writers
- write-gate transitions and rejected capture writes
- connection closes by bounded reason
- terminal publisher failures by bounded reason

Prefer event-driven up-down counters over callbacks that walk mutable state.
Oldest-unacknowledged age and similar age signals may be periodic measurements if
they cannot be maintained safely as instruments.

#### INFO/WARN/ERROR events

- `capture_startup_summary`
- `capture_assignment_received`
- `capture_generation_prepared`
- `capture_generation_activated`
- `capture_writer_draining`
- `capture_writer_retired`
- `capture_health`
- `capture_write_gate_changed`
- `capture_heartbeat_late`
- `capture_broker_time_violation`
- `capture_connection_dropped`
- `capture_publisher_failed`
- `capture_shutdown_summary`

`capture_health` should be periodic and include current/draining writer counts,
partitions, active connections, in-flight sends, oldest unacknowledged age,
queued/detached bytes, last heartbeat broker time and write-gate state.

#### DEBUG/TRACE events

Cover route selection, connection creation/closure, record detachment, submission,
acknowledgement and lane retirement. TRACE may include individual captured
observations and exact sequencing/deadline calculations.

Exit gate: the existing publisher and routing failure tests assert the corresponding
metric and operational log event, including heartbeat deadline, broker-time
violation, assignment change, writer draining/retirement and terminal failure.

### Phase 3 — Replayer metrics

Fill the aggregate signals that traces cannot replace:

- Add exact assertions for the 11 currently unasserted `ReplayIntakeMetrics`
  instruments:
  - retry-source response complete/unavailable
  - writer-time transition
  - source-connection expiration
  - target-connection expiration command
  - broker-time violation
  - capture protocol violation
  - generation grace/force/cleanup
  - stale-generation input ignored
- Add direct adapter tests for `ProtocolViolationMetrics` and
  `ResourceOwnershipMetrics`.
- Add target-attempt outcome metrics attributed by `NoTargetResponseKind`.
- Count retry exhaustion separately from retry attempts.
- Add tuple durability metrics: writes accepted, writes pending, retries, terminal
  failures, durable completions and write latency.
- Add bounded connection-drop metrics for assembly duration, header bytes, total
  bytes and connection duration.
- Decide how Kafka consumer lag is obtained. Either export a source lag metric or
  document and test the broker-side signal used by operators.
- Export the context populations already maintained:
  - total active contexts and globally oldest context age from
    `ActiveContextTracker`
  - active count, oldest age and over-threshold count by bounded activity name from
    `ActiveContextTrackerByActivityType`
- Report explicit zeroes for known activity types that are currently empty.
- Start with only global active/long-running owner-operation counts if they are
  useful for dashboards. Add `OperationType`/`WaitReason` aggregate metrics only if
  gameday shows that context and domain metrics plus detailed registry logs leave an
  aggregate detection gap; derive them periodically from `activitySnapshots()`.

Do not attach connection, request, offset, process or worker identifiers to these
aggregate instruments. Those identities belong in logs.

### Phase 4 — Replayer logging

#### INFO/WARN/ERROR events

- `replayer_startup_summary`
- `kafka_assignment_changed`
- `kafka_generation_retiring`
- `kafka_commit_uncertain`
- `replay_generation_grace_started`
- `replay_generation_force_started`
- `replay_generation_cleanup_finished`
- `replay_health`
- `replay_oldest_record`
- `replay_stall_detected`
- `capture_protocol_violation`
- `source_connection_expired`
- `target_response_unavailable`
- `target_retries_exhausted`
- `tuple_durability_degraded`
- `replayer_fatal_failure`
- `replayer_shutdown_summary`

`replay_health` should be periodic and bounded. Include total contexts, counts by
activity, globally oldest age, oldest age/over-threshold count by activity, oldest
Kafka record identity and age, input queue depth, incomplete source connections,
retry-ready supply, owner-operation count, greatest observed broker time,
active/retiring generations, pending permits, pending tuples and Kafka commit
uncertainty. Do not emit one INFO line per healthy record.

`replay_oldest_record` should identify topic, partition, generation and offset. When
old, `replay_stall_detected` should include the record's active descendants and
parent chains, distinguishing reconstruction, accumulation, scheduling, permit,
target I/O, response wait and tuple durability. If no descendant explains the wait,
include the relevant commit-head/eligibility/in-flight/uncertainty state from
the immutable `KafkaSourceOwner` diagnostic snapshot.

Append supplemental `OutstandingOperationRegistry` details when an exact owner
obligation is old: owner, operation type, wait reason, elapsed time and available
traffic identities. The registry report must not be presented as the complete set of
replayer activity.

Route both context-monitor and registry-monitor WARN events to the main replayer log.
Preserve the dedicated long-running activity file if useful, but it must not be the
only destination. Keep the existing Kafka logging heartbeat as a separate periodic
domain summary; do not describe it as the Kafka consumer-group heartbeat or as a
replacement for context monitoring.

#### DEBUG/TRACE events

Add a coherent journal at owner boundaries:

- Kafka batch polled and input offered
- intake input applied
- record tracker started/retired
- source connection created/completed/expired
- request reconstituted and admitted
- target permit requested/acquired/released
- target attempt started/completed
- retry decision and scheduling
- tuple write submitted/retried/durable/failed
- Kafka commit staged/submitted/resolved
- generation cleanup decision

DEBUG records decisions and state transitions. TRACE records individual observations,
chunks, timer calculations, Netty writes and completion callbacks. Every ERROR event
must include the relevant state snapshot and identifiers needed to find surrounding
DEBUG/TRACE events.

### Phase 5 — Make observability part of the test contract

Use the existing correctness tests as the trigger points instead of creating a
parallel observability-only behavior suite.

For each operationally critical behavior:

1. Assert the domain state/result.
2. Assert the exact emitted metric or changed observable population/state, including
   bounded attributes, using an in-memory meter.
3. Assert the operational event name and required fields using a test appender.
4. Avoid asserting complete prose, timestamps or field ordering.

Tests of diagnostic selection, snapshot publication, log cadence, identity
correlation or internal ordering still need appropriate assertions, but those
mechanics are not classified as operationally critical behaviors merely because
they support the diagnostic implementation.

Add cross-cutting tests for:

- one opened context appears in both global and by-activity indexes
- closing a context removes it from both indexes
- trace export disabled while context tracking, context monitoring, logs and metrics
  continue to work
- the oldest open `KafkaRecordContext` is selected correctly
- active descendants of an old record are found by scanning the global set and
  walking ancestor chains
- the monitor distinguishes reconstruction, target response waiting, tuple
  durability and Kafka commit progression
- Kafka poll, commit and rebalance contexts appear despite having no owner-registry
  entry
- registry-only waits still report permit acquisition, required delivery/receiver
  and channel-close obligations
- removing `CountHook` does not change registry snapshots, invariant/fatal detection
  or monitor output
- periodic INFO summaries and detailed old-context WARN output stay bounded under
  large active populations and prolonged stalls
- collector absent while logs still provide startup, health, degradation and
  terminal-failure diagnosis
- production top-level wiring, rather than only anonymous callback fakes
- clean-shutdown conservation:
  - records read = committed + cancelled + abandoned + ineligible + outstanding
  - retry-ready additions - removals = current supply
  - acquired permits - released permits = active permits
  - owned resources return to zero
  - open contexts, owner obligations and pending tuples return to zero
- metric attributes restricted to documented bounded sets
- INFO-level log-rate bounds during prolonged failures
- startup logs explicitly state whether metric export and trace export are enabled

### Phase 6 — Operator surfaces

Before gameday, make the new signals usable without reading source:

- Publish the metric inventory with descriptions, units, dimensions, conservation
  equations and healthy-state expectations.
- Create capture, replay-intake, target execution, Kafka commit and tuple durability
  dashboard sections.
- Document suggested investigation thresholds for:
  - capture gap active
  - heartbeat deadline violation
  - publisher terminal failure
  - write gate rejecting capture
  - protocol violation
  - fatal replay failure
  - open contexts, owner obligations or owned resources that remain non-zero without
    progress
  - oldest-work age above threshold
  - tuple durability terminal failure or sustained retrying
  - commit uncertainty or consumer lag
- Do not install Prometheus alert rules, Alertmanager notification configuration,
  CloudWatch alarms, or notification targets. Dashboard threshold colors are
  display-only.
- Document log queries keyed by event name and each identity field.
- Add short operator decision trees:
  - capture not reaching Kafka
  - Kafka records not becoming replay requests
  - requests waiting before target execution
  - target attempts failing or exhausting retries
  - tuples not becoming durable
  - clean shutdown not completing

### Phase 7 — Gameday validation

Run with production trace export disabled. For each scenario, record:

- expected first metric or threshold breach
- expected first INFO/WARN/ERROR event
- exact identity chain used to investigate
- time to detection and time to causal hypothesis
- recovery signal
- missing, misleading or excessively noisy signals

| Scenario | Injection | Observability must distinguish |
|---|---|---|
| Healthy baseline | Sustained representative traffic and one clean scale event | Normal context/activity ranges, bounded INFO volume, both context indexes and owner registries drain, all conservation residuals zero |
| Capture acknowledgement stall | Delay or drop Kafka producer acknowledgements | In-flight growth, oldest-ack age, heartbeat deadline, writer/gate state and pass-through/termination mode |
| Capture routing change | Add/remove partitions or replace a writer generation | Assignment, preparation, activation, draining and retirement in order |
| Kafka unavailable to capture | Block broker access, then restore it | Retriable degradation versus terminal capture failure and recovery |
| Replayer rebalance | Revoke/lost partitions with work in flight | Poll/rebalance contexts, oldest record, generation retirement, abandoned/uncertain records, retained-partition progress and cleanup |
| Slow or unavailable target | Add latency, connection failure and read timeout | Oldest record descendants and owner obligations distinguish permit waiting, network connection, response timeout and retry exhaustion |
| Malformed/stale capture input | Inject protocol violation and stale-generation records | Violation type, affected generation, drain/termination behavior and ignored stale input |
| Tuple sink unavailable | Block S3/file durability, then restore it | Tuple descendants and registry writes identify pending durability, separated from target/replay progress and Kafka commit eligibility |
| Resource ownership fault | Test-only injected duplicate close or deliberately retained buffer | Ownership invariant/age signal and the resource type responsible |
| Kubernetes process restart | Restart proxy and replayer pods while traffic continues | Restart-scoped pod/JVM identity, recovery and fleet aggregates unaffected by metric-cardinality churn |
| Collector unavailable | Disable the OTel collector while traffic continues | Explicit telemetry degradation while logs remain sufficient for diagnosis |

#### Gameday acceptance criteria

- Detection occurs within two health-reporting intervals for every sustained fault.
- A responder unfamiliar with the implementation identifies the failing subsystem
  and bounded reason within ten minutes.
- The responder can follow one affected connection/request through capture, intake,
  target execution and tuple durability using logs only.
- For an old Kafka record, the responder can identify its topic/partition/generation/
  offset, current descendant phase or absence of one, supplemental owner obligation
  and the Kafka-domain reason its partition offset cannot advance.
- Recovery is visible without inferring it from the absence of new errors.
- No scenario requires a trace or span ID.
- No sustained fault creates unbounded INFO/WARN duplication.
- Conservation equations explain all intentionally discarded or abandoned work.
- Every confusing or missing signal becomes either a code/test action or an explicit
  runbook limitation before release.

### Suggested review/merge sequence

1. **Observability contract:** naming, levels, identities and initial metric catalog.
2. **Mainline checkpoint A:** after the pending `service.instance.id` change lands,
   merge mainline, run the existing targeted suites, and settle the CloudWatch
   dimension decision against the merged behavior.
3. **Monitoring and wiring safety:** restore both context-monitor inputs, preserve
   owner-registry diagnostics, remove `CountHook`, remove production metric NOOPs,
   connect resource ownership metrics, and add the top-level wiring test.
4. **Capture observability:** routing/publisher metrics, structured logs, health
   summary and behavior-test assertions.
5. **Replay observability:** missing aggregate metrics, owner-level logs, health
   summary and behavior-test assertions.
6. **Mainline checkpoint B:** merge mainline again after the capture/replay
   implementation stabilizes and before dashboards and investigation thresholds are
   finalized. Rerun the traces-disabled observability tests.
7. **Operator readiness:** log routing, dashboards and runbooks.
8. **Gameday:** run all scenarios, patch gaps, rerun failed scenarios, and preserve
   outputs as release evidence.
9. **Release checkpoint:** merge the intended release baseline, rerun the failed
   gameday scenarios plus the healthy baseline, and treat any changed metric
   dimensions or missing log events as release blockers.

Each change should be deployable independently and should avoid renaming previously
merged metric/event contracts unless the same change updates dashboards and
documentation.

---

## Open questions / not yet checked

Hooks for expanding this review:

- [x] Kafka consumer **offset lag** is obtained from the broker-side consumer-group
      view. `deployment/k8s/scripts/capture-replay-gameday.sh lag` executes the
      Strimzi Kafka CLI and reports one group summary. `records_outstanding` remains
      an in-flight conservation signal, not broker lag.
- [x] `TracingTest` asserts the complete span tree it constructs, including exact
      activity counts, both root spans, and every parent relationship. Traces remain
      optional in production while enabled behavior stays covered.
- [x] **Removed contexts: `ITrafficSourceContexts` / `TrafficSourceContexts`.**
      These classes were entirely inside `REBUILD-LIMBO` comments before deletion,
      so their `Count`, `Duration`, and `ExceptionCount` instruments were not live.
      No deployment dashboard in this repository references their activity names.
- [ ] Span-derived metrics are emitted per context *close*. For the highest-frequency
      contexts (`requestSending`, `receivingResponse`, one per attempt per request)
      confirm the resulting metric volume and histogram bucket counts are acceptable
      at production request rates during the live Kubernetes gameday — the
      cardinality review covered attributes, not emission frequency, and local tests
      cannot establish production export volume.
- [x] The removed `sonar-project.properties` lines were only issue suppressions for
      retired replay classes (`ClientConnectionPool`, `BlockingTrafficSource`,
      `TrafficReplayerCore`, and `ReplayEngine`). No coverage or
      instrumentation-related exclusion was removed.
- [x] Metric export remains enabled while trace export defaults off in Kubernetes.
      Startup-summary and traces-disabled context-monitor tests cover this mode.
- [x] `KafkaSourceOwner` publishes immutable diagnostic snapshot copies. The monitor
      correlates the globally oldest record with those snapshots and never reads
      owner-thread-confined commit state.

---

## Appendix A — new metric inventory

57 instruments across 7 classes. `UD` = up-down counter, `H` = histogram, otherwise
monotonic counter.

### `ReplayIntakeMetrics` — scope `replayer`, 30 instruments

| Name | Type | Unit | Attributes |
|---|---|---|---|
| `replayIntakeOwnerStarted` | counter | owners | — |
| `replayIntakeOwnerStoppedAfterDraining` | counter | owners | — |
| `replayIntakeInputsApplied` | counter | inputs | `inputKind` |
| `replayIntakeRecordsApplied` | counter | records | — |
| `replayIntakeActiveRecordTrackers` | UD | records | — |
| `replayIntakeRecordTrackersRetired` | counter | records | — |
| `replayIntakeRequestsReconstituted` | counter | requests | — |
| `replayIntakeInterimResponsesObserved` | counter | responses | — |
| `replayIntakeResponsesProvenComplete` | counter | responses | — |
| `replayIntakeResponsesUnprovenComplete` | counter | responses | — |
| `replayIntakeResponsesIncomplete` | counter | responses | `incompleteReason` |
| `replayIntakeRetrySourceResponsesComplete` | counter | responses | — |
| `replayIntakeRetrySourceResponsesUnavailable` | counter | responses | — |
| `replayIntakeWriterTimeTransitions` | counter | transitions | `writerTimeTransition` |
| `replayIntakeSourceConnectionsExpired` | counter | connections | — |
| `replayIntakeTargetConnectionExpirationsSent` | counter | commands | — |
| `replayIntakeBrokerTimeViolations` | counter | violations | — |
| `replayIntakeBatchRequestsSubmitted` | counter | requests | — |
| `replayIntakeBatchEntitlementsResolved` | counter | batches | `batchEntitlement` |
| `replayIntakeRetryReadyRequestSupply` | UD | requests | — |
| `replayIntakeRetryReadySupplyAdditions` | counter | requests | — |
| `replayIntakeRetryReadySupplyRemovals` | counter | requests | — |
| `replayIntakeDemandEvaluations` | counter | evaluations | `demandState` (`OPEN`/`SATISFIED`) |
| `replayIntakeCapturedClosesAccepted` | counter | closes | — |
| `replayIntakeCaptureProtocolViolations` | counter | violations | — |
| `replayIntakeRecordBatchesRejectedAfterProtocolViolation` | counter | batches | — |
| `replayIntakeGenerationGraceStarted` | counter | generations | `graceMode` (`REVOCATION`/`SHUTDOWN`) |
| `replayIntakeGenerationForceStarted` | counter | generations | — |
| `replayIntakeGenerationCleanupFinished` | counter | generations | — |
| `replayIntakeStaleGenerationInputsIgnored` | counter | inputs | `inputKind` |

### `KafkaCommitStateMetrics` — scope `replayer`, 8 instruments — see `O-4`

| Name | Type | Unit | Attributes |
|---|---|---|---|
| `kafkaSourceRecordsRead` | counter | records | — |
| `kafkaSourceRecordsCommitted` | counter | records | — |
| `kafkaSourceRecordsCancelled` | counter | records | — |
| `kafkaSourceRecordsAbandonedAtRevocation` | counter | records | `cause` |
| `kafkaSourceRecordsCommitIneligible` | counter | records | — |
| `kafkaSourceRecordsOutstanding` | UD | records | — |
| `kafkaSourceCommitAttemptsRejected` | counter | attempts | — |
| `kafkaSourceCommitResolutions` | counter | events | `outcome` |

### `TargetAttemptPermitMetrics` — scope `replayer`, 8 instruments

| Name | Type | Unit |
|---|---|---|
| `targetAttemptPermitAcquisitionRequests` | counter | requests |
| `targetAttemptPermitAcquisitionsPending` | UD | requests |
| `targetAttemptPermitAcquisitionsCancelled` | counter | requests |
| `targetAttemptPermitAcquisitionsFailed` | counter | requests |
| `targetAttemptPermitsAcquired` | counter | permits |
| `targetAttemptPermitsActive` | UD | permits |
| `targetAttemptPermitsReleased` | counter | permits |
| `targetAttemptPermitHeldDuration` | H | ms |

### `ResourceOwnershipMetrics` — production-wired, 5 instruments

| Name | Type | Unit | Attributes |
|---|---|---|---|
| `resourceOwnedHandles` | UD | handles | `resourceType` |
| `resourceOwnedBuffers` | UD | buffers | `resourceType` |
| `resourceOwnedBytes` | UD | By | `resourceType` |
| `resourceDuplicateCloseAttempts` | counter | attempts | `resourceType` |
| `resourceOwnershipInvariantFailures` | counter | failures | `resourceType` |

`resourceType` values: `prepared_request`, `attempt_payload`, `diagnostic_payload`.

### `ProtocolViolationMetrics` — scope `replayer`, 2 instruments

| Name | Type | Unit |
|---|---|---|
| `replayProtocolViolationDrainsStarted` | counter | drains |
| `replayProtocolViolationTerminationsTriggered` | counter | terminations |

### `ReplayProcessFatalMetrics` — scope `replayer`, 1 instrument

| Name | Type | Unit | Attributes |
|---|---|---|---|
| `replayFatalFailures` | counter | failures | `reason` |

### `CaptureProcessMetrics` — capture proxy, 3 instruments

| Name | Type | Unit | Attributes |
|---|---|---|---|
| `captureProcessPassThroughTransitions` | counter | count | `processId`, `captureActivationId`, `mode`, `sourceForwardingContinued` |
| `captureProcessTerminationTransitions` | counter | count | `processId`, `captureActivationId` |
| `captureGapActive` | UD | processes | `processId`, `captureActivationId`, `mode`, `sourceForwardingContinued` |

Only this class sets `setDescription(...)` on its instruments.

### Span-derived metrics (per context)

These are not declared in any `*Metrics` class and will not show up in a grep for
`counterBuilder` in replayer code. Every context type gets three instruments
automatically, named from its `ACTIVITY_NAME`:

| Instrument | Source | Emitted when |
|---|---|---|
| `<activity>Count` | `CommonScopedMetricInstruments.contextCounter` | context `close()` → `sendMeterEventsForEnd()` |
| `<activity>Duration` | `CommonScopedMetricInstruments.contextDuration` (ms) | context `close()` |
| `<activity>ExceptionCount` | `CommonMetricInstruments.exceptionCounter` | `addTraceException(...)` |

Mechanism: `IScopedInstrumentationAttributes.close()` calls `endSpan()` then
`sendMeterEventsForEnd()` (`coreUtilities/.../IScopedInstrumentationAttributes.java:78`).
Subclasses that override `sendMeterEventsForEnd()` call `super` first and then add
their own — e.g. `TupleHandlingContext` adds `tupleComparison`, `ScheduledContext`
adds `scheduleLag`.

The 15 activity names (`IReplayContexts.ActivityNames`), so the full set is 45
instruments:

`channel`, `tcpConnection`, `recordLifetime`, `trafficStreamLifetime`,
`httpTransaction`, `accumulatingRequest`, `accumulatingResponse`, `transformation`,
`scheduled`, `targetTransaction`, `requestConnecting`, `requestSending`,
`waitingForResponse`, `receivingResponse`, `comparingResults`.

Note `comparingResults` is the activity name behind `TUPLE_COMPARISON`, so the
tuple-handling context emits `comparingResultsCount` / `comparingResultsDuration` /
`comparingResultsExceptionCount` **and** the separately-declared `tupleComparison`
counter. Worth confirming nobody reads that pair as a duplicate.

### Pre-existing instruments relied on (not new, do not break)

`scheduleLag`, `lagBetweenSourceAndTargetRequests`, `tupleComparison` (with
`sourceStatusCode` / `targetStatusCode` / `statusCodeMatch` / `method`),
`numRetries`, `unretryableConnectionFailures`, plus per-context count and duration
from `CommonScopedMetricInstruments`, and capture-side `WireCaptureContexts`
counters (`blockingRequest`, `captureSuppressed`, `fullRequest`, `bytesRead`,
`bytesWritten`, `unregistered`, `removed`).

---

## Appendix B — how to re-derive these numbers

```bash
BASE=$(git merge-base HEAD upstream/main)

# Scope
git diff --stat "$BASE"...HEAD | tail -1

# Log sites by level, before and after
for ref in "$BASE" HEAD; do
  echo "== $ref"
  for lvl in Error Warn Info Debug Trace; do
    n=$(git grep -c "log\.at$lvl\|log\.$(echo $lvl | tr 'A-Z' 'a-z')" "$ref" \
        -- TrafficCapture/trafficReplayer/src/main \
        | awk -F: '{s+=$NF} END {print s+0}')
    echo "  $lvl: $n"
  done
done

# Replayer main LOC
for ref in "$BASE" HEAD; do
  git ls-tree -r --name-only "$ref" -- TrafficCapture/trafficReplayer/src/main \
    | grep '\.java$' \
    | while read f; do git cat-file blob "$ref:$f" | wc -l; done \
    | awk -v r="$ref" '{s+=$1} END {print r, s}'
done

# Which metrics classes are actually constructed in production
for c in ReplayIntakeMetrics KafkaCommitStateMetrics TargetAttemptPermitMetrics \
         ResourceOwnershipMetrics ProtocolViolationMetrics ReplayProcessFatalMetrics \
         CaptureProcessMetrics; do
  echo "== $c"
  grep -rn "new $c(" --include='*.java' TrafficCapture | grep -v '/test/'
done

# Every NOOP observability seam, and whether main code installs a real one
grep -rn "Metrics.NOOP\|CountHook.NOOP\|RecordObserver.NOOP" \
  --include='*.java' TrafficCapture | grep -v '/test/'

# Span-derived metrics are NOT findable this way -- they are named from ACTIVITY_NAME
# at runtime, so no counterBuilder call exists in replayer code for them.  Enumerate
# the activity names instead and expand each to Count / Duration / ExceptionCount.
grep -n 'public static final String [A-Z_]* = "' \
  TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/tracing/IReplayContexts.java
```

**Caveat for anyone re-running these:** a `metrics.`-call or `counterBuilder` grep
systematically undercounts coverage. Any class that creates and closes a context is
emitting three instruments per context type through
`IScopedInstrumentationAttributes.close()`. The first draft of this review drew a
wrong conclusion from exactly that mistake.
