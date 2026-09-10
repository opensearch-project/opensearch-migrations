# Proxy Horizontal Scaling, Capture Liveness, and Writer Completion

**Status:** standalone design contract
**Last revised:** 2026-09-10

This document defines horizontal scaling and failure behavior for capture proxies that write traffic
to Kafka for later replay. It covers the controller-less deployment. Managed-fleet recovery is a
separate, deferred design in
[`proxyManagedFleetCaptureRecovery.md`](proxyManagedFleetCaptureRecovery.md).

The design deliberately separates three concerns:

1. Kafka group membership assigns partitions for new connections.
2. Exact open-connection manifests provide positive capture liveness and allow prompt cleanup.
3. The capture-before-forward contract prevents a strict-mode proxy from completing a source
   request that Kafka cannot later replay.

Membership departure is useful operational evidence that a proxy may be unhealthy. It is not replay
evidence and does not authorize another proxy to declare the departed writer finished.

---

## 1. Goals and accepted boundaries

### 1.1 Required behavior

The system must:

- replay every complete captured request at least once;
- capture every mutating request completely before allowing that request to complete at the source
  in strict mode;
- preserve long-lived connections, including connections with long periods of no traffic;
- let the replayer release incomplete connection state after positive evidence or a configured
  expiration;
- scale proxies horizontally without moving existing connections;
- keep capture and source forwarding ordered correctly during rebalance, shutdown, and Kafka
  failure; and
- alarm loudly when capture develops a gap.

### 1.2 Accepted behavior

The design accepts:

- a complete request may be captured even though it never reaches the source;
- an incomplete connection may remain retained until a later partition observation advances the
  replayer's expiration horizon;
- hard process death may prevent a final writer-completion record;
- pass-through mode may create a known interval in which source traffic is not replayable; and
- restoring strict capture after such an interval requires a new capture and replay run; and
- this redesign supports only source request handling that cannot mutate state before the complete
  HTTP request arrives. Streaming source handlers that can act on an incomplete body remain out of
  scope; and
- hardening mutating-request classification beyond the proxy's current HTTP-method predicate is
  deferred. Endpoints that mutate outside that predicate are a known limitation for this round.

The design does not require transactions, a controller, or a peer fencing protocol.

### 1.3 Central end-to-end invariant

For every request `Q` that can mutate source state:

```text
SourceApplied(Q)
    implies
Kafka has durably acknowledged the complete replay representation of Q
```

The converse is intentionally false. Kafka may contain a complete request that the proxy later
chooses not to forward.

The proxy enforces this invariant by withholding the final execution-enabling bytes of a mutating
request until its complete Kafka representation has been acknowledged. After that acknowledgement
and immediately before those source bytes are submitted, the proxy performs the capture-liveness
check in §5.

For this redesign, the invariant assumes that source execution is gated by receipt of the complete
HTTP request. Transfer-Encoding chunking does not violate that assumption by itself; a source
handler that applies effects while consuming an incomplete body does. Such streaming source
semantics are not supported in this round.

More generally, every captured source write requires the corresponding Kafka record to be
acknowledged and the local capture gate to remain open. The complete-request rule is the stronger
condition applied at the source execution boundary.

This ordering is the reason configured expiration can safely release incomplete replayer state:

- if the source completed the request, Kafka already contains the complete request;
- if Kafka contains only an incomplete request, strict mode could not have completed it at the
  source; and
- if a suspended proxy later resumes and completes Kafka capture, its pre-forward liveness check
  prevents a stale strict-mode source write.

---

## 2. Terminology and identities

### 2.1 `processId`

`processId` identifies one operating-system process for logs, metrics, and control-plane
diagnostics. It is generated at process start and is never reused.

### 2.2 `writerNodeId`

`writerNodeId` identifies one capture activation in Kafka traffic records, manifests, and
writer-completion records. It is generated at process start and is never reused by a replacement
process.

This document uses **writer** for the process that emitted a record and **member** for its current
Kafka group membership. A writer identity outlives any one group generation.

### 2.3 Connection identity

Every accepted connection has a unique `connectionId`. The proxy records its chosen traffic
partition when the connection is admitted:

```text
connectionId -> trafficPartition
```

That mapping is immutable for the life of the connection.

### 2.4 Capture modes

- **`FAIL_AND_EXIT`** is strict capture. A capture-liveness failure prevents further source
  execution and causes the process to close connections and exit.
- **`AUTONOMOUS_PASS_THROUGH`** permits source forwarding after capture is abandoned. The process
  remains available, alarms continuously, and never resumes capture.

---

## 3. Kafka membership and horizontal scaling

### 3.1 Membership is routing authority only

The proxy group assigns every traffic partition to one member for **new-connection admission**.
Membership does not prove that a departed writer has stopped all execution, and it does not
authorize peer `NoMoreWrites` records.

Each member publishes enough assignment metadata for every member to compute the same routing
table. The leader uses a custom cooperative assignor so partitions can move gradually.

The implementation must retain the existing minimum-capacity gate:

```text
ACTIVE member count >= minimumActiveProxyCount
```

When the gate is not satisfied, no member admits new captured connections.

### 3.2 Member phases

Each process has one membership phase:

- **`PROBING`** — outside the consumer group while Kafka capability is tested;
- **`PROBATIONARY`** — in the group but not eligible for new traffic;
- **`ACTIVE`** — eligible for group-assigned new connections;
- **`DRAINING`** — admits no new connections and finishes existing connections in place; or
- **`CAPTURE_ABANDONED`** — permanently outside capture participation for this process.

`PROBATIONARY` members may receive a future assignment, but traffic admission begins only after an
assignment containing them as `ACTIVE` has completed.

### 3.3 Startup capability probing

A process must not join the proxy group until it has demonstrated that its producer can complete an
acknowledged Kafka write.

While in `PROBING`, it:

1. refreshes metadata for the traffic topic;
2. chooses one representative traffic partition for every distinct current leader broker;
3. emits a semantically inert `CaptureCapabilityProbe` to each representative partition;
4. waits for all probe acknowledgements; and
5. refreshes metadata again before joining the group as `PROBATIONARY`.

The replayer recognizes `CaptureCapabilityProbe` as a semantically inert record. It never creates
replay work, opens a connection, or refreshes any connection's
`lastPositiveLivenessBrokerTime`. Because it is a valid broker-appended partition record, it does
advance `scannedThroughBrokerTime` when replay or scanning covers it.

This probe qualifies current producer reachability. It does not guarantee future availability.
Normal runtime failures are handled by §5 and §7.

### 3.4 Scale up

When member B joins:

1. B completes §3.3 and joins as `PROBATIONARY`.
2. The leader computes a cooperative assignment.
3. Existing member A stops admitting new connections on partitions assigned to B.
4. A continues writing existing connections to their original partitions.
5. B becomes `ACTIVE` and admits new connections for its assigned partitions.
6. A emits manifests for its remaining connections until each drains.
7. After A's final connection on a transferred partition drains, A emits and acknowledges a final
   empty manifest. It does not emit `NoMoreWrites`, because ordinary assignment may later return
   that partition to A.

Existing connections never migrate between processes or partitions.

### 3.5 Scale down

A planned process retirement first becomes `DRAINING`:

1. it stops accepting new connections;
2. another member becomes the new admission owner for its partitions;
3. existing connections continue in place;
4. the draining writer continues traffic and manifest publication;
5. after all connections drain, it follows §4.3 and emits terminal self `NoMoreWrites` for each
   partition the writer used; and
6. it leaves the group and exits after those records are acknowledged, or after the operator's
   drain deadline expires.

If the deadline expires, the process applies the configured strict or pass-through behavior in §7.

### 3.6 Reacquisition

A live writer may later reacquire a partition that it previously drained through an empty manifest.
It acknowledges a new initial manifest before admitting the first new connection.

A writer must never reacquire a partition after emitting `NoMoreWrites` for that
`(writerNodeId, partition)`. Reuse requires a fresh process and `writerNodeId`. In normal scaling,
`NoMoreWrites` is therefore reserved for permanent writer-partition retirement, typically process
shutdown or permanent capture abandonment after all related connections have been disconnected.

### 3.7 Kafka 4 and assignment-protocol rollout

The initial implementation uses Kafka's Classic group protocol because the required custom
cooperative assignment metadata and phase transitions are under application control.

If a future group protocol cannot interoperate with the deployed assignor or subscription metadata,
the fleet must not perform an in-place mixed-protocol rollout. The rollout creates a capture
boundary:

1. stop the old capture run;
2. retire or restart any pass-through processes;
3. use a distinct capture run identity and, when required for clean operational separation, a new
   traffic topic;
4. restore strict capture;
5. take a fresh source snapshot; and
6. start a new replay run at the new run's configured offsets.

Traffic from before and after an uncaptured or protocol-incompatible interval must not be presented
as one complete replay run.

---

## 4. Capture records and publisher ordering

### 4.1 Record types

The traffic topic carries:

```text
TrafficRecord {
    writerNodeId
    connectionId
    partition
    captureTimestamp
    ...
}

OpenConnectionManifestChunk {
    writerNodeId
    partition
    manifestId
    chunkIndex
    chunkCount
    emittedAt
    connectionIds[]
}

NoMoreWrites {
    writerNodeId
    partition
    emitterNodeId
}

CaptureCapabilityProbe {
    writerNodeId
    probeId
}
```

For `NoMoreWrites`, the emitter must be the writer:

```text
emitterNodeId == writerNodeId
```

Peer-emitted `NoMoreWrites` is invalid. A consumer may ignore and alarm on such a record; it must
not use it to settle replay work or install a terminal cutoff.

The emitter field may remain in the wire format for compatibility.

### 4.2 One ordered submission lane per partition

Traffic records, manifest chunks, and self `NoMoreWrites` for one writer and partition pass through
the same ordered publisher lane.

Broker append order must preserve that lane order across retries. The producer therefore requires:

```text
enable.idempotence = true
acks = all
max.in.flight.requests.per.connection <= 5
```

These are correctness settings, not tuning defaults. User configuration must not weaken them, and
startup must validate the effective producer configuration before the process enters `ACTIVE`.
Failure to establish ordering-preserving settings fails capture closed.

The lane distinguishes:

- **accepted** — submission entered the lane;
- **submitted** — the producer API accepted it;
- **acknowledged** — Kafka acknowledged it; and
- **failed or ambiguous** — success cannot be established.

Only acknowledgement advances the writer's durable state.

For permanent writer-partition retirement, the lane has a one-way local state:

```text
OPEN -> RETIRING -> RETIRED
```

- `OPEN` accepts connection traffic and ordinary periodic manifests.
- `RETIRING` rejects new connection traffic and ordinary manifests. Only the retirement barrier
  may submit the final empty manifest and terminal self `NoMoreWrites`.
- `RETIRED` rejects every submission.

### 4.3 Terminal self `NoMoreWrites` barrier

Self `NoMoreWrites` permanently retires one `(writerNodeId, partition)`. It is a compact,
offset-ordered mechanism for promptly releasing incomplete state when the writer shuts down
cleanly.

Before emitting it for partition P, the proxy:

1. atomically moves P's publisher lane from `OPEN` to `RETIRING`, permanently revoking
   new-connection admission, connection-originated traffic submission, and ordinary manifest
   submission for P under this `writerNodeId`;
2. asynchronously quiesces the periodic manifest publisher: no future run may start, and any
   callback already running must either have entered the lane before `RETIRING` or finish without
   submitting. The quiescence completion gate must not block a Netty event loop;
3. disconnects every Netty connection capable of producing traffic for P and waits for every
   connection-teardown future to settle;
4. waits for every send previously accepted by P's publisher lane to succeed and treats any failed
   or ambiguous send as a capture failure under §7;
5. verifies that P's exact open-connection registry is empty;
6. emits and acknowledges a final empty manifest through the retirement barrier;
7. submits `NoMoreWrites` through the same lane; and
8. after its acknowledgement, moves the lane to `RETIRED` before reporting completion.

The `RETIRING` transition and manifest-publisher quiescence ensure that no delayed periodic task can
enqueue an ordinary manifest behind terminal `NoMoreWrites`. Multiple retirement requests share the
same idempotent completion gate.

The useful guarantee is:

```text
Every source-completable request from this writer on P
has a complete acknowledged Kafka representation before NoMoreWrites.
```

Because emission waits for every related Netty connection and teardown future, no proxy thread
remains able to submit source or capture bytes for P after `NoMoreWrites`. The source service may
still finish processing a request that it received before disconnection, but that request's complete
Kafka representation is already before the writer-completion record.

After acknowledgement, no code path may reopen capture submission for P under this
`writerNodeId`.

### 4.4 Hard failure

A killed, crashed, or indefinitely suspended process may emit no `NoMoreWrites`. That is expected.
The replayer then uses manifests and configured expiration as described in §6.

---

## 5. Exact manifests and the pre-forward liveness gate

### 5.1 Exact open-connection registry

For every writer and partition, the proxy maintains an exact registry of **all** open captured
connections, including idle connections.

Registry and publisher ordering obey these rules:

- a connection enters the registry before its first traffic submission;
- registry mutation, manifest construction, and traffic admission are ordered through one
  publisher-owned linearization construct;
- copying a manifest and admitting all of its chunks to the ordered lane are one publisher
  operation. A copied manifest cannot be overtaken by a connection's first traffic submission;
- a connection remains present while later traffic could still be submitted;
- connection close or cancellation is ordered before removal; and
- removal occurs only after the connection's complete accepted record set has been acknowledged.

Therefore, if a manifest omits connection C, either the manifest entered the lane before C's first
traffic or C's final accepted traffic had already been acknowledged before removal. A registry copy
may not be taken on one thread and queued later after independently admitted traffic.

This makes a complete manifest omission meaningful. It also keeps an inactive but open connection
alive indefinitely, because periodic manifests continue to list it.

### 5.2 Manifest completeness

Every assigned or still-draining writer emits a complete manifest per partition at
`manifestInterval`, with a default operational target of 30 seconds.

The manifest may be chunked. All chunks share one `manifestId`, `chunkCount`, writer, partition, and
logical emission time. Chunks are numbered exactly `0..chunkCount-1`.

The replayer uses a manifest only after every chunk is present exactly once. A partial,
inconsistent, or duplicate-index manifest is non-authoritative and produces an alarm.

An empty complete manifest is a positive statement that the writer currently has no open
connections on that partition.

### 5.3 Initial manifest

Before admitting the first connection for a newly writable partition, the proxy must acknowledge
an initial complete manifest through that partition's ordered lane. This prevents a connection from
being created before the partition has any positive liveness baseline.

### 5.4 Proxy-local acknowledged-manifest freshness

The proxy tracks, per partition:

```text
lastAcknowledgedManifestSubmissionMonotonicTime[P]
```

“The last manifest went out” always means Kafka acknowledged the entire manifest. Enqueue,
serialization, or producer submission is insufficient.

The stored value is the proxy's local monotonic time from when that now-acknowledged manifest was
created or submitted, not the later acknowledgement-callback time. Kafka acknowledgement makes the
value valid; it does not make an old manifest fresh again.

The manifest publisher treats a failed or incomplete manifest as a capture failure. It does not
advance `lastAcknowledgedManifestSubmissionMonotonicTime`.

This value exists only for the proxy's local capture-health check. It is never written into Kafka,
never compared with Kafka broker time, and never used by the replayer.

### 5.5 Pre-forward liveness check

For every source-forwarding step:

1. capture and acknowledge the record that represents the bytes to be forwarded;
2. for execution-enabling bytes of a mutating request, also ensure that the request's complete
   replay representation has been acknowledged;
3. immediately before submitting the corresponding source bytes, read the current capture state
   and `lastAcknowledgedManifestSubmissionMonotonicTime` for the request's partition;
4. permit source submission only if capture remains healthy and the manifest age is within
   `proxyManifestStaleTimeout`; and
5. otherwise apply §7.

The check and source-admission decision must share a one-way local capture gate. Once the gate
closes, no thread may newly pass the check. This avoids a simple check-then-transition race inside
the process.

No local check can fence a thread that has already entered an uninterruptible source syscall.
That does not violate the central invariant: the request was completely acknowledged to Kafka
before that syscall was allowed.

### 5.6 Timeout relationship

The proxy's stale-manifest threshold must be shorter than the replayer's configured incomplete-state
expiration:

```text
manifestInterval
    < proxyManifestStaleTimeout
    < replayer incomplete-state timeout
```

Deployments must include margin for:

- manifest scheduling and Kafka acknowledgement latency;
- scanner/read-ahead delay; and
- ordinary transient retries.

Setting both thresholds to the same value is invalid.

This inequality compares configured durations only. It never subtracts a proxy-local monotonic
timestamp from a Kafka broker timestamp.

The concrete replayer setting is the existing `--packet-timeout-seconds` unless implementation work
introduces a more precisely named replacement.

---

## 6. Replayer interpretation

### 6.1 Complete requests

A complete replayable request is processed normally regardless of later manifests,
`NoMoreWrites`, or writer health.

Expiration is only for incomplete reconstruction state. It must never discard a complete request
merely because its writer later became silent.

### 6.2 Positive settlement evidence

For connection C owned by writer X on partition P:

| Observation after C's latest traffic record | Replayer action |
|---|---|
| normal connection-close record | Finish C through the ordinary close path |
| complete exact manifest from X on P lists C | Keep C alive and refresh its manifest liveness |
| complete exact manifest from X on P omits C | Settle incomplete state for C immediately |
| terminal self `NoMoreWrites{X, P, X}` | Settle all incomplete state already known for X on P and permanently retire that writer-partition identity |
| later traffic or manifest from X on P after terminal completion | Halt and alarm on a protocol violation; do not silently discard or treat it as a new interval |
| duplicate self `NoMoreWrites{X, P, X}` | Treat idempotently as the same terminal retirement |
| peer `NoMoreWrites{X, P, E}` where `E != X` | Ignore for settlement, alarm as invalid provenance |

Manifest omission and self `NoMoreWrites` are prompt cleanup paths. They avoid waiting for timeout
when the writer completed its own bookkeeping. Empty manifest omission supports reversible
assignment drain; terminal `NoMoreWrites` supports irreversible retirement.

### 6.3 Configured expiration fallback

If no prompt settlement evidence arrives, the replayer may expire **incomplete** state after its
configured timeout.

The replayer defines a distinct Kafka broker-time value:

```text
lastPositiveLivenessBrokerTime[C]
```

It is the latest Kafka `LogAppendTime` of:

- a traffic record for C; or
- a complete exact manifest from C's writer and partition that lists C.

For a chunked manifest, its broker-time value is the maximum effective broker append time across
all chunks, and it becomes usable only after the manifest is complete.

The replayer also tracks:

```text
scannedThroughBrokerTime[P]
```

This is the greatest monotonically clamped Kafka `LogAppendTime` among valid, recognized partition
records whose metadata the replay or scan cursor has actually covered. It includes semantically
inert records such as `CaptureCapabilityProbe`. It is not the current broker wall clock, the
consumer's poll time, or an estimate derived from offset position.

Once:

```text
scannedThroughBrokerTime[P]
    >= lastPositiveLivenessBrokerTime[C] + --packet-timeout-seconds
```

and no later traffic or complete listing manifest exists within that broker-time horizon, the
incomplete state becomes `CONFIGURED_EXPIRED`.

`CONFIGURED_EXPIRED` is commit-eligible, but it must remain distinguishable in metrics and audit
logs from:

- normal close;
- exact manifest omission; and
- terminal self `NoMoreWrites`.

This is a policy decision supported by the end-to-end proxy invariant in §1.3. It is not presented
as mathematical proof that the process can never resume.

### 6.4 Long gaps

A connection with no traffic for hours remains live if complete manifests continue to list it.
Every listing refreshes its liveness point. The timeout therefore detects loss of both traffic and
positive manifests, not application idleness.

### 6.5 Kafka broker time

Kafka broker append time is the only timestamp domain used in replayer liveness and
configured-expiration arithmetic. The traffic topic must use
`message.timestamp.type=LogAppendTime`, and the replayer reads the stored timestamp from each Kafka
record.

For each partition P, the effective broker-time horizon is monotonic:

```text
effectiveBrokerTime[P] =
    max(previousEffectiveBrokerTime[P], currentRecordLogAppendTime)
```

This clamp protects the expiration state machine from a broker-clock regression. It does not permit
producer timestamps to enter the calculation.

Only traffic for connection C and a complete manifest listing C may update
`lastPositiveLivenessBrokerTime[C]`. Other valid recognized records advance only the partition
horizon. A malformed record, invalid partition stamp, unknown unsafe record type, or other protocol
violation cannot advance expiration; processing halts or remains inconclusive according to the
record-validation policy.

`lastPositiveLivenessBrokerTime`, `scannedThroughBrokerTime`, and every intermediate timestamp in
the comparison are broker-time values. The configured timeout is a duration added to a broker-time
value. Code must not compare, subtract, choose between, or convert into this calculation any value
from another clock domain.

The following values are explicitly excluded from liveness and expiration arithmetic:

- `TrafficObservation.ts`, which remains source event time for replay pacing;
- manifest `emittedAtMillis`, which remains diagnostic only;
- proxy-local monotonic manifest freshness from §5.4; and
- replayer process wall clock or commit-head age.

The replayer therefore measures expiration using durable observations from the traffic partition
rather than “time since the consumer noticed silence.” A paused replayer cannot expire work merely
because process wall time elapsed.

If no later record appears in the partition, `scannedThroughBrokerTime` does not advance and the
replayer does not expire the incomplete state. Later partition activity supplies the durable
broker-time evidence needed to cross the configured horizon.

### 6.6 Commit behavior

When a connection's incomplete source state is settled, all of its retained source records follow
the ordinary terminal-disposition and commit-accounting path. No special force-commit bypass is
introduced.

The replayer must never let one incomplete state retain the partition forever after that state has
reached a terminal disposition.

---

## 7. Proxy failure behavior

### 7.1 Shared transition rule

Manifest staleness, traffic-producer failure, an ambiguous send, or any other loss of trustworthy
capture closes the local capture gate permanently for that process.

Capture never resumes under the same `writerNodeId`.

The process emits a high-severity alarm containing at least:

- `processId` and `writerNodeId`;
- mode;
- affected partitions;
- last acknowledged manifest submission age from the proxy's local monotonic clock;
- last acknowledged traffic offset when available;
- producer error or stale-gate reason; and
- whether source forwarding continued.

### 7.2 Strict mode: `FAIL_AND_EXIT`

After the gate closes:

- the request currently waiting at the pre-forward check is not sent to the source;
- no later request is admitted to source execution;
- new connection admission stops;
- existing connections are closed or terminated;
- the process begins immediate exit or restart;
- accepted Kafka sends are drained only while their outcome remains trustworthy; and
- terminal self `NoMoreWrites` is attempted only after every related Netty connection is
  disconnected and the §4.3 barrier succeeds.

Failure to emit self `NoMoreWrites` does not delay exit. The replayer has the expiration fallback.

### 7.3 Pass-through mode: `AUTONOMOUS_PASS_THROUGH`

After the gate closes:

- capture admission and Kafka traffic submission remain permanently closed;
- existing and new source connections may continue without capture;
- connections do not need to be broken merely because replay cannot reconstruct them;
- the process emits a loud, persistent capture-gap alarm; and
- it does not emit terminal `NoMoreWrites` while any retained pass-through connection could still
  contain capture-side work from the retired writer.

The process must not rejoin capture, resume manifests, or generate captured traffic under the same
or a new `writerNodeId`. Recovery requires retiring the process and starting a fresh strict-mode
process.

### 7.4 Process suspension

A process may be suspended after Kafka acknowledgement and before source submission. If it resumes:

- in strict mode, the pre-forward stale-manifest check prevents execution once the threshold is
  exceeded;
- if suspension occurred after the source admission decision, the request was already completely
  captured and remains replayable; and
- in pass-through mode, the capture gap is an accepted and alarmed policy outcome.

This is the meaningful containment boundary without broker transactions or an external controller.

### 7.5 Membership observations

A missing group member, failed health check, or control-plane declaration of node death should
alarm operators and may trigger process replacement. It does not cause replay settlement and does
not cause another proxy to write completion on behalf of the missing writer.

---

## 8. Recovery after a capture gap

Pass-through creates an interval whose source effects cannot be proven complete from Kafka. The
system must not silently splice later strict capture onto the previous replay run.

Recovery is:

1. alarm and record the start of the gap;
2. retire every process that entered pass-through;
3. restore a fully strict capture fleet with fresh process and writer identities;
4. establish the new capture run and its start offsets;
5. take a fresh source snapshot appropriate to the replay workflow; and
6. start a new replay run from that boundary.

For the current Kubernetes round, the controller marks the failed capture resource terminal and
starts a fresh capture, snapshot, and replay workflow. It does not restore capture or authorize a
replacement snapshot within the failed resource. The managed-fleet addendum records a future
automatic-recovery design using controller-issued `captureSessionId`, per-partition reset, a trusted
replay plan, and old-workload traffic-retirement proof.

---

## 9. Implementation deltas

| Area | Required change |
|---|---|
| Group assignor | Remove designated-witness graph, witness maturity, peer visibility, and writer-footprint confirmation from admission. Retain cooperative admission ownership and member phases. |
| Startup | Add out-of-group capability probes to representative leader brokers before joining as `PROBATIONARY`. |
| Routing | Persist immutable connection-to-partition choice; move only new-connection admission during rebalance. |
| Registry | Maintain exact all-open connection sets per writer and partition. |
| Publisher | Use one ordered lane per partition for traffic, complete manifests, and terminal self `NoMoreWrites`; expose proxy-local acknowledged-manifest submission age. |
| Source forwarding | Enforce complete-capture acknowledgement and the local pre-forward stale-manifest gate for mutating requests. |
| Failure mode | Make capture abandonment irreversible per process; implement strict exit and permanent pass-through transitions. |
| Record validation | Accept terminal self `NoMoreWrites` idempotently; reject peer completion; halt and alarm on later traffic or manifests from a retired writer-partition identity. |
| Replayer | Re-enable configured expiration for incomplete state under this proxy contract and retain distinct terminal evidence. |
| Observability | Replace witness and peer-cutoff metrics with manifest-age, capture-gate, configured-expiration, and capture-gap metrics. |

### 9.1 Removed concepts

The standalone implementation no longer needs:

- designated witnesses;
- witness count or failure-domain placement;
- `confirmedPeerVisibility`;
- dynamic witness repair;
- peer-emitted `NoMoreWrites`;
- terminal peer cutoffs and zombie-record discard;
- writer-footprint dissemination for peer completion; or
- quiescence delays intended to make peer completion safer.

### 9.2 Required status

Each proxy should expose:

- process and writer identity;
- membership phase and current admission assignment;
- capture mode and whether the capture gate is open;
- current connections by partition;
- proxy-local acknowledged-manifest submission age by partition;
- oldest unacknowledged publisher work;
- whether the process has permanently abandoned capture; and
- capture-gap alarm state.

The replayer should expose:

- incomplete connections by writer and partition;
- latest complete manifest offset and Kafka broker append time;
- terminal-disposition counts by normal close, manifest omission, terminal self `NoMoreWrites`, and
  configured expiration;
- invalid peer-completion records; and
- the oldest commit-blocking state.

---

## 10. Integration issues to resolve during implementation

### 10.1 Kafka broker-time contract

The documents require:

```text
proxyManifestStaleTimeout < replayer incomplete-state timeout
```

The two sides use intentionally separate clocks:

- the proxy capture-health gate uses only local monotonic elapsed time from §5.4; and
- the replayer expiration state machine uses only Kafka `LogAppendTime` from §6.5.

The timeout relationship compares durations and safety margins, never timestamp values from the two
domains.

Before implementation is declared complete, the code and deployment contract must state:

- the traffic topic is configured with `message.timestamp.type=LogAppendTime`;
- proxy and replayer startup validate that setting and fail closed if it is absent;
- every liveness-bearing scanner value has an explicit `BrokerTime` type or name;
- complete-manifest broker time is derived only from its Kafka records;
- per-partition broker time is monotonically clamped;
- broker clocks are synchronized and monitored, with the permitted forward skew included in the
  configured expiration margin;
- no producer, payload, proxy-local, or replayer-wall-clock timestamp can enter expiration
  arithmetic; and
- timeout validation includes manifest cadence, publication and acknowledgement delay, and scanner
  progress margin.

### 10.2 Known limitation: streaming source execution

This redesign assumes that the source application cannot mutate state until it has received the
complete HTTP request. Under that assumption, withholding the final request bytes until complete
Kafka acknowledgement preserves §1.3.

The round does not add full-request buffering or support source handlers that apply effects while
streaming an incomplete body. Deployments with those semantics cannot claim the strict-mode
capture-before-forward guarantee for those endpoints.

HTTP chunked transfer encoding remains supported when it is only wire framing and the source still
waits for the complete request. Supporting true streaming execution later requires buffering or
spooling the complete mutating request, acknowledging its complete Kafka representation, checking
the capture gate, and only then releasing effect-causing bytes to the source.

### 10.3 Deferred hardening: mutating-request classification

The central invariant applies to every request that can mutate source state, but the current proxy
classifies requests through its existing HTTP-method predicate. Replacing that rule with a
source-specific policy, default-mutating classification, or explicit read-only allowlist is
deferred.

This is a known coverage limitation rather than a new guarantee. A future hardening round should
make unknown requests mutating by default and ensure that capture-suppression rules cannot exempt a
potentially mutating request from strict capture-before-forward.

### 10.4 Scope of current expiration code

The existing `--packet-timeout-seconds` path must be audited to verify that it expires only
incomplete reconstruction state. A completed replayable request must not be culled because its
connection stopped receiving manifests.

If the current accumulator combines incomplete source capture with other work, the implementation
must split the terminal reasons or narrow the expiration target before enabling this policy for
Kafka replay.

### 10.5 Existing writer-completion schema and consumers

Existing code may already accept `emitterNodeId != writerNodeId` and install a terminal cutoff.
Those paths must be removed or made non-authoritative. Compatibility parsing can remain, but peer
provenance must not settle state.

### 10.6 Managed-fleet addendum

The managed-fleet addendum is realigned with this protocol. Its current-round Kubernetes rule is
terminal: after bounded retry reaches a capture failure, the resource remains incomplete and the
workflow starts over. Future automatic recovery is documented around:

- terminal self-only `NoMoreWrites`;
- configured incomplete-state expiration;
- the irreversible local capture gate;
- explicit new-run recovery after a capture gap;
- controller-issued capture sessions that fence delayed old records;
- separate old-writer capture retirement and Kubernetes traffic retirement; and
- reset-derived replay start offsets that remain conservative across the new source snapshot.

---

## 11. Validation plan

### 11.1 Deterministic proxy tests

- A mutating request cannot submit execution-enabling source bytes before complete Kafka
  acknowledgement.
- The supported-source contract states that mutating handlers do not apply effects before the
  complete HTTP request arrives; true streaming source execution remains out of scope.
- Mutating-request classification retains the current HTTP-method predicate as a documented
  limitation; conservative default-mutating classification is deferred.
- A stale manifest after capture acknowledgement blocks strict-mode source forwarding.
- Closing the capture gate races safely with many source-forwarding threads.
- A thread admitted before gate closure has a complete Kafka representation.
- Failed or partial manifest publication does not refresh proxy-local acknowledged-manifest
  freshness.
- A manifest snapshot racing connection registration either enters the lane before the
  connection's first traffic or includes the connection; a copied omission can never be queued
  behind independently admitted traffic for that connection.
- Terminal self `NoMoreWrites` waits for all related Netty connections to disconnect, an empty
  manifest to be acknowledged, every previously accepted send to succeed, and the periodic
  manifest publisher to become quiescent.
- A periodic manifest callback racing permanent retirement either enters the lane before
  `RETIRING` and is drained or exits without submission; it never publishes after terminal
  `NoMoreWrites`.
- Producer configuration cannot disable idempotence, weaken `acks=all`, or set
  `max.in.flight.requests.per.connection` above the ordering-preserving limit.
- Startup fails closed when the effective producer settings cannot preserve same-partition lane
  order across retries.
- Pass-through never resumes capture.
- Strict mode stops new source execution and exits.

### 11.2 Manifest and replayer tests

- Complete manifests list active and idle connections.
- Chunk loss, conflicting metadata, and duplicate indexes make a manifest non-authoritative.
- A listing manifest refreshes an idle connection.
- A later omission settles incomplete state promptly.
- Terminal self `NoMoreWrites` settles prior incomplete state and retires the writer-partition.
- Later traffic or manifests after terminal self `NoMoreWrites` halt and alarm as a protocol
  violation; duplicate self completion is idempotent.
- Peer `NoMoreWrites` does not settle state or discard later traffic.
- Configured expiration settles only incomplete state and records `CONFIGURED_EXPIRED`.
- `lastPositiveLivenessBrokerTime`, `scannedThroughBrokerTime`, and every intermediate expiration
  value are derived only from monotonically clamped Kafka `LogAppendTime`.
- Valid recognized records, including capability probes, advance `scannedThroughBrokerTime`; only
  connection traffic and complete listing manifests refresh `lastPositiveLivenessBrokerTime`.
- Malformed or invalid records never advance an expiration verdict.
- Tests fail if `TrafficObservation.ts`, manifest `emittedAtMillis`, proxy-local monotonic time, or
  replayer wall clock enters broker-time expiration arithmetic.
- A complete request is replayed even when its connection later expires.
- Commit accounting advances after every terminal path.

### 11.3 Membership tests

- A process completes leader-broker probes before joining.
- `PROBATIONARY` members receive no new traffic.
- Scale-up moves new admission while old connections drain in place.
- Temporary assignment drain ends with an acknowledged empty manifest.
- Reacquisition after temporary drain begins only after a new initial manifest is acknowledged.
- Reacquisition after terminal `NoMoreWrites` requires a fresh process and `writerNodeId`.
- The active-capacity gate closes below `minimumActiveProxyCount`.
- Mixed incompatible group protocols are rejected as an in-place rollout.

### 11.4 Failure tests

- Kill a proxy with open idle and active connections; verify timeout fallback.
- Suspend a proxy beyond the stale threshold and resume it; verify strict source execution is
  blocked after complete Kafka capture.
- Suspend it after source admission; verify the request was already completely captured.
- Fail Kafka during manifest publication in strict and pass-through modes.
- Create an ambiguous producer send and verify the capture gate closes.
- Enter pass-through, recover Kafka, and verify that the process still does not resume capture.

### 11.5 Acceptance criteria

The design is complete when:

1. every source-completable mutating request is completely acknowledged to Kafka first;
2. exact manifests preserve open idle connections;
3. prompt self evidence and bounded configured expiration can both release incomplete state;
4. no peer observation authorizes replay settlement;
5. ordinary partition reacquisition works after empty-manifest drain and is forbidden after
   terminal `NoMoreWrites`;
6. strict suspension recovery cannot create an uncaptured completed request;
7. pass-through creates a loud, explicit capture gap and never resumes capture;
8. timeout relationships are validated against a documented clock model; and
9. all terminal paths release commit obligations through the normal accounting path.

---

## 12. Decisions

- Kafka membership assigns new connections; it does not prove writer death.
- Manifests list all open connections and act as positive heartbeats.
- Manifest age advances only after acknowledgement of a complete manifest.
- Capture-before-forward is the primary completeness guarantee.
- Capture-before-forward in this round assumes non-streaming source execution; chunked wire framing
  is allowed only when the source waits for the complete request.
- Hardening mutating-request classification beyond the existing HTTP-method predicate is deferred.
- The proxy checks manifest freshness after Kafka acknowledgement and before source execution.
- `NoMoreWrites` is self-emitted, partition-scoped, ordered, and terminal for that
  writer-partition identity.
- Kafka producer idempotence and ordering-preserving retry settings are mandatory and cannot be
  weakened by deployment configuration.
- Temporary partition drain uses an acknowledged empty manifest and permits later reacquisition.
- Hard crashes use configured incomplete-state expiration.
- Long inactivity is safe because manifests continue to list the connection.
- Strict mode blocks source execution and exits after capture loss.
- Pass-through mode may continue connections uncaptured, alarms loudly, and never rejoins capture.
- Peer death observations remain operational signals only.
- Recovery after a capture gap starts a new capture and replay run.
- In the current managed Kubernetes round, a terminal capture failure terminally fails the whole
  capture workflow; same-resource reset, regrant, and replacement-snapshot automation are deferred.
- Replayer liveness and configured expiration use Kafka broker `LogAppendTime` exclusively.
- Proxy manifest freshness uses a separate local monotonic clock; timestamp values never cross
  those domains.
