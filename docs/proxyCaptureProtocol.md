# Proxy Capture Protocol

**Status:** standalone design contract
**Last revised:** 2026-09-14

This document defines horizontal scaling and failure behavior for capture proxies that write traffic
to Kafka for later replay. It covers the controller-less deployment. Managed-fleet terminal-failure
behavior and fresh-run recovery are specified separately in
[`managedFleetCaptureRecovery.md`](managedFleetCaptureRecovery.md).

The design deliberately separates three concerns:

1. Kafka group membership load-balances partitions for new connections.
2. Periodic records from each writer and partition establish the broker-time baseline used to
   expire incomplete state after a proxy becomes silent.
3. The capture-before-forward contract prevents a strict-mode proxy from completing a source
   request that Kafka cannot later replay.

Group departure causes a rebalance among the remaining members. The proxy that Kafka considers
departed continues using its last usable assignment until it receives a replacement. Departure is
not capture-health or replay evidence and does not authorize another proxy to declare the departed
proxy instance finished.

---

## 1. Goals and accepted boundaries

### 1.1 Required behavior

The system must:

- ensure that Netty's terminal observation and every earlier `TrafficObservation` for a closed
  connection are acknowledged by Kafka before that connection is removed from the active set;
- replay every complete request that the capture parser recognized and detached before its
  connection interval was reset;
- capture every mutating request completely before allowing that request to complete at the source
  in strict mode;
- preserve long-lived connections, including connections with long periods of no traffic;
- let the replayer expire incomplete per-connection HTTP accumulation after normal connection
  completion, clean proxy completion, or proved proxy silence;
- scale proxies horizontally without moving existing connections;
- keep capture and source forwarding ordered correctly during rebalance, shutdown, and Kafka
  failure; and
- alarm loudly when capture develops a gap.

### 1.2 Accepted behavior

The design accepts:

- a complete request may be captured even though it never reaches the source;
- an incomplete accumulation from a hard-crashed proxy may remain retained when no terminal self
  completion record or skew-valid later partition record can settle it;
- hard process death may prevent a clean proxy-completion record;
- pass-through mode may create a known interval in which source traffic is not replayable;
- restoring strict capture after such an interval requires a new capture and replay run;
- HTTP/1.x pipelined requests are outside the settled protocol contract. The protocol does not
  define behavior when one source connection has multiple outstanding requests or one inbound read
  contains bytes from more than one request. Deployments cannot rely on capture or replay
  correctness for those cases;
- this protocol supports only source request handling that cannot mutate state before the complete
  HTTP request arrives. Streaming source handlers that can act on an incomplete body remain out of
  scope; and
- the strict guarantee applies only to requests classified as Critical Mutation Traffic by the
  configured HTTP-method predicate. Endpoints that mutate outside that predicate are unsupported.

The standalone design does not require transactions or a controller.

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

The invariant assumes that source execution is gated by receipt of the complete
HTTP request. Transfer-Encoding chunking does not violate that assumption by itself; a source
handler that applies effects while consuming an incomplete body does. Such streaming source
semantics are not supported by this protocol.

Kafka acknowledgement at the source execution boundary applies only to the final
execution-enabling bytes of Critical Mutation Traffic. Ordinary packet forwarding and requests
outside the configured Critical Mutation Traffic predicate are not synchronously gated on Kafka
acknowledgement. Connection retirement separately waits for acknowledgement of the terminal
observation and every earlier observation for that connection.

This ordering makes terminal connection processing safe:

- if the source completed the request, Kafka already contains the complete request;
- if Kafka contains only an incomplete request, strict mode could not have completed it at the
  source;
- after Netty reports a connection closed, the kernel and Netty deliver no more network traffic for
  that connection;
- the connection's event-loop owner submits the terminal connection observation after every earlier
  observation in the connection-local Kafka submission chain; and
- only after Kafka acknowledges the terminal observation and every earlier observation does the
  event-loop owner remove the connection from the active set.

This invariant combines with the skew-adjusted Kafka `LogAppendTime` proof in §6.3. That proof may
release only incomplete connection accumulators already known to the replayer. It does not retire a
writer or partition and does not prevent a later first observation from starting fresh state.

---

## 2. Terminology and identities

### 2.1 `processId`

`processId` identifies one operating-system process for logs, metrics, and control-plane
diagnostics. It is generated at process start and is never reused.

### 2.2 Capture activation and writer identities

`captureActivationId` identifies one capture-authoritative lifetime of a proxy process. It is fresh
whenever a process begins a new capture activation and is never reused after capture has been
abandoned.

`assignmentSequence` is a process-local, strictly increasing value. The proxy increments it for
every new Kafka group assignment before accepting a connection under that assignment. The
`writerNodeId` used for newly opened connections is:

```text
writerNodeId = captureActivationId + ":" + assignmentSequence
```

Every new assignment therefore creates a `writerNodeId` that cannot be reused within the
`captureActivationId`. Existing connections permanently retain the `writerNodeId` under which they
opened. Rapid rebalances may therefore leave several older writer identities draining concurrently
within one proxy process.

The out-of-group Kafka capability probe runs before a consumer-group generation exists. It uses the
inert attribution value:

```text
writerNodeId = captureActivationId + ":PROBE"
```

`CaptureCapabilityProbe` confirms only that the proxy can publish to Kafka. It has no replay
meaning. The replayer never interprets its `writerNodeId` as a traffic writer identity, heartbeat
owner, or source of connection state.

### 2.3 Captured client connection

Every accepted connection uses Netty's globally unique long-form channel identifier as its
`connectionId`. The protocol requires uniqueness only within its immutable `writerNodeId`. The
proxy records its chosen traffic partition when the connection is accepted:

```text
(writerNodeId, connectionId) -> trafficPartition
```

Both the writer identity and partition mapping are immutable for the life of the connection.
The maximum whole-connection lifetime defaults to 60 minutes and is independently configurable
from incomplete-request duration and byte limits.

This document reserves **drain** for two aggregate operations:

- **writer-partition drain** — stop opening connections under one `(writerNodeId, partition)` while
  the proxy continues serving that identity's existing connections; and
- **proxy connection-set drain** — wait for the proxy's connections to close naturally or close
  them during proxy shutdown.

The lifecycle of one closed connection is **connection retirement**, as defined in §5.1.
The `DRAINING` process state and “still-draining proxy” wording below mean that the proxy's
connection set is undergoing the second aggregate operation; they do not name a third kind of
drain.

### 2.4 Capture failure policy

`--capture-failure-policy` is process-wide:

- **`fail-closed`** terminates the process immediately when required Kafka publication fails or
  capture is otherwise compromised.
- **`fail-open`** permanently abandons capture for the process. Existing and new TCP connections
  may continue forwarding without capture, the process alarms continuously, and capture never
  resumes. A controller-managed deployment additionally requires the controller acknowledgement
  in §7.3 before any uncaptured source forwarding begins.

The Kafka client may retry internally while no failure has been exposed to the application. Once a
Kafka failure, timeout, or ambiguous result is exposed, one of the terminal policies applies and
the proxy does not resubmit the record.

---

## 3. Kafka membership and horizontal scaling

### 3.1 Membership is load balancing only

The proxy group distributes Kafka partitions for newly opened source connections. Membership does
not prove capture health, producer health, proxy death, or writer completion, and it does not
authorize one proxy to emit `NoMoreWrites` for another writer identity.

The deployment uses a Kafka-provided assignment strategy supported by its selected consumer-group
protocol. The capture protocol uses only the partitions assigned to the local member. It requires
no custom assignor, subscription `userData`, assignment metadata, member readiness state, minimum
group size, or startup quorum. A built-in sticky or cooperative strategy may reduce routing churn,
but neither property is required for correctness.

Fleet capacity and startup policy are external to Kafka assignment. A managed controller may route
source traffic only after enough proxies report `captureReady=true` on their control interfaces,
but that policy does not change group subscription or assignment.

Every member completes the Kafka capability probe before it joins the group. After the first usable
assignment, later group size or membership health does not close a capture or
connection-acceptance gate.

### 3.2 Assignment and connection routing

After a process completes §3.3, it joins the group. Before it has ever received a usable
assignment, it cannot accept a captured source connection because it has no Kafka partition for
that connection. A startup failure or attempted source connection before the first usable
assignment invokes the process-wide `--capture-failure-policy`. After either terminal policy is
applied, that process does not later resume capture when membership supplies an assignment.

The first assignment becomes usable only after:

1. the assignment is installed;
2. the proxy's capture-authoritative mode remains valid; and
3. the initial heartbeats for that assignment's writer identity and usable partitions are
   acknowledged.

Afterward, the proxy retains the last usable assignment until Kafka supplies a replacement.
Revocation, partition-loss callbacks, stale or failed polling, empty polls, delayed heartbeats,
coordinator outages, and group departure do not invalidate it. The proxy continues assigning new
connections with the last usable assignment.

Kafka may temporarily give another proxy the same partition after considering the stale member
gone. This affects load distribution only. The proxies have distinct writer identities, so capture
and replay remain unambiguous.

When a replacement assignment arrives, the proxy creates its next assignment-scoped writer
identity and acknowledges that identity's initial heartbeats before using the replacement for new
connections. Connections opened earlier retain their original writer identity and partition.
There is no membership-health deadline.

### 3.3 Startup capability probing

A process must not join the proxy group until it has demonstrated that its producer can complete an
acknowledged Kafka write.

While in `PROBING`, it:

1. refreshes metadata for the traffic topic;
2. chooses one representative traffic partition for every distinct current leader broker;
3. emits a semantically inert `CaptureCapabilityProbe` carrying
   `writerNodeId = captureActivationId + ":PROBE"` and a producer timestamp of zero to each
   representative partition;
4. accepts each acknowledgement only when its Kafka record metadata reports a positive timestamp,
   proving that the broker replaced the supplied timestamp with `LogAppendTime`;
5. waits for all probe acknowledgements; and
6. refreshes metadata again before joining the group.

A topic configured with `CreateTime` either rejects the deliberately stale producer timestamp or
returns it unchanged. Either result fails the probe. Workflow-managed capture topics set
`message.timestamp.type=LogAppendTime`; unmanaged deployments must configure the same property.

`CaptureCapabilityProbe` is not replay input. If the replayer later encounters the Kafka record, it
performs no replay action and allows the record to become commit-eligible through ordinary
whole-record accounting.

This probe qualifies current producer reachability. It does not guarantee future availability.
Normal runtime failures are handled by §5 and §7.

### 3.4 Scale up

When member B joins:

1. B completes §3.3 and joins the group.
2. Kafka's configured assignment strategy redistributes new-connection partition eligibility.
3. Every member receiving a new assignment increments its `assignmentSequence` and creates that
   assignment's
   `writerNodeId`.
4. Existing member A stops opening connections under its preceding writer identity when the
   replacement assignment becomes usable. Existing connections keep that preceding identity and
   their original partitions.
5. Each member acknowledges an initial heartbeat for its new writer identity on every
   partition that the assignment permits it to use before accepting a connection there.
6. A continues traffic and periodic heartbeats for every older writer identity that still has
   connections.
7. After an older `(writerNodeId, partition)` has no connections, A acknowledges self
   `NoMoreWrites`, permanently retiring that identity on that partition.

Existing connections never migrate between processes or partitions.

### 3.5 Scale down

A planned process retirement first becomes `DRAINING`:

1. it stops accepting new connections;
2. another member becomes the member accepting new captured client connections for its partitions;
3. existing connections continue in place;
4. the proxy continues traffic and heartbeat publication for every draining writer identity;
5. after each `(writerNodeId, partition)` connection set is empty, it follows §4.3 and emits
   terminal self `NoMoreWrites` for that identity and partition; and
6. it leaves the group and exits after those records are acknowledged.

This orderly retirement is performed only while capture and Kafka publication remain trustworthy.
Its default completion target is five minutes. Missing the target emits a high-severity diagnostic
and retirement continues through terminal connection observations and `NoMoreWrites`.
`NoMoreWrites` is submitted once. Kafka may continue its internal delivery attempts, but the proxy
does not resubmit at the application layer. An application-visible Kafka failure or expiration of
the current heartbeat acknowledgement deadline `E`, whichever occurs first, makes retirement
unsuccessful. The proxy exits without claiming that writer retirement completed. This timing
policy is not used for unstable-process failures described in §7.

### 3.6 Later use of a partition

A live proxy process may become eligible to accept new connections on a partition that it used in
an earlier assignment. Those new connections use the last usable assignment's `writerNodeId`. A
replacement identity acknowledges its own initial heartbeat before
accepting its first connection on that partition.

The earlier writer identity never resumes. Heartbeats for an active identity update its continuous
broker-time baseline; they do not end or reset it. After the earlier identity's connections drain,
self `NoMoreWrites` permanently retires that `(writerNodeId, partition)`.

Rapid rebalances may leave several writer identities draining on the same Kafka partition while the
last usable assignment's identity accepts new connections there. Every connection registry,
publisher lane, broker-time baseline, and retirement state is therefore keyed by
`(writerNodeId, partition)`, not by one process-global current identity.

### 3.7 Kafka 4 and assignment-protocol rollout

The capture protocol has no dependency on a custom assignor or custom subscription metadata. The
deployment may use Kafka's Classic group protocol or a newer supported consumer-group protocol with
a Kafka-provided assignor. Members in one group must still use a configuration that Kafka considers
interoperable during rollout.

Changing assignment strategy may redistribute every partition and may temporarily overlap a stale
proxy's last usable assignment with a replacement assignment. That affects load balancing only.
Assignment-scoped writer identities keep captured records unambiguous, and existing connections
retain their original identities and partitions. No capture boundary is required solely because a
different Kafka-provided assignment strategy changes partition placement.

---

## 4. Capture records and publisher ordering

### 4.1 Record types

The traffic topic carries:

```text
TrafficStream {
    nodeId = writerNodeId
    connectionId
    partition
    subStream[] {
        connectionObservationSequence
        captureTimestamp
        ...
    }
    ...
}

WriterPartitionHeartbeat {
    writerNodeId
    partition
    heartbeatIntervalMillis
    emittedAtMillis?  // optional, diagnostic only
}

NoMoreWrites {}

CaptureCapabilityProbe {
    writerNodeId = captureActivationId + ":PROBE"
    probeId
    captureSessionId?  // required for controller-managed mode; absent only in unmanaged mode
}
```

`WriterPartitionHeartbeat` is the fixed protobuf name. One record proves only that one
`(writerNodeId, partition)` can still publish acknowledged records within the configured interval.
It contains no connection identities, chunk metadata, heartbeat sequence, or connection-lifecycle
boundary. `heartbeatIntervalMillis` reports the proxy's configured publication interval for
diagnostics and future sanity checks; it does not configure replayer expiration.

The Kafka record header for `NoMoreWrites` carries the authoritative `writerNodeId`, and Kafka
record metadata supplies the authoritative partition. Neither is repeated in the protobuf body. A
proxy may publish the record only through that writer identity's publisher lane. A missing or
malformed writer header makes the record inert: the replayer warns and may commit the inert record,
but it does not retire any writer or connection state.

`connectionObservationSequence` is a monotonically increasing connection-local value assigned by
the connection's single event-loop owner. It validates the order of `TrafficObservation` values for
that captured client connection. It is not shared across connections and needs no
contended atomic increment.

Reconstruction-relevant records for one connection enter the producer through a connection-local
submission chain in contiguous sequence order. Other connections and heartbeats may interleave
freely; there is no global packet lock. Producer idempotence preserves the order in which that
connection's records enter `send`. A missing sequence, repeated sequence with different content, or
regression is a protocol failure that closes capture or causes the replayer to retain the record,
emit high-severity diagnostics, and terminate; the replayer does not wait indefinitely for a lower
sequence. Truly incidental packet diagnostics that cannot affect HTTP
request completion, expiration, source forwarding, or target replay are outside this sequence and
may be best effort.

Netty's terminal connection observation is the final sequence value for a connection. Once the
replayer consumes it, any later `TrafficObservation` for that connection is a protocol violation,
including a contiguous next sequence value. Heartbeats have no per-connection meaning and cannot
reopen the connection's traffic-observation state.

### 4.2 Publisher ordering and acknowledgement

Kafka producer ordering is still required within one writer identity and partition, especially for
terminal self `NoMoreWrites`. It is not used to force every ordinary packet and connection
lifecycle change through one global proxy lock.

Broker append order for records submitted in order must remain stable across retries. The producer
therefore requires:

```text
enable.idempotence = true
acks = all
max.in.flight.requests.per.connection <= 5
```

These are correctness settings, not tuning defaults. User configuration must not weaken them, and
startup must validate the effective producer configuration before the process becomes
capture-authoritative. Failure to establish ordering-preserving settings fails capture closed.

One Kafka producer and one publisher lane can serve all partitions. The lane orders accepted
submissions, producer calls, and producer callbacks. Multiple producers are safe only when, for
each `(writerNodeId, partition)`:

- exactly one producer and publisher-lane owner publishes observations and heartbeats in one
  submission order;
- ownership never overlaps; and
- handoff waits for every submission and callback from the previous owner before the successor
  begins.

Different proxy writers may still publish existing connections to the same Kafka partition. The
single-owner rule is scoped to one writer and partition. It preserves one submission order for that
writer, and the drained handoff prevents late callbacks or writes from the previous owner.

The lane distinguishes:

- **accepted** — submission entered the lane;
- **submitted** — the producer API accepted it;
- **acknowledged** — Kafka acknowledged it; and
- **failed or ambiguous** — success cannot be established.

Only acknowledgement advances the proxy's durable publication state.

For permanent `(writerNodeId, partition)` retirement, the publisher lane has a one-way local state:

```text
OPEN -> RETIRING -> RETIRED
```

- `OPEN` accepts existing-connection traffic and ordinary periodic heartbeats. The separate routing
  and acceptance gate determines whether a new connection may be opened.
- `RETIRING` is entered only after all connections have been fully retired and the local registry
  is empty. It rejects heartbeats. Only the retirement barrier may submit terminal self
  `NoMoreWrites`.
- `RETIRED` rejects every submission.

Publisher initialization and shutdown are completion-gated lifecycle operations. The composition
root owns any newly created producer and executor until publisher construction succeeds. A failed
initialization closes and joins both before returning failure; successful construction transfers
their sole lifecycle ownership to `CaptureKafkaPublisher`. Clean process shutdown first runs the
§4.3 retirement barrier for every used partition; only after each terminal self `NoMoreWrites` is
acknowledged does publisher shutdown close the producer and terminate its executor. A capture
failure may instead close the producer without claiming terminal completion. Publisher shutdown
completes only after callback quiescence, producer closure, and executor termination. There is no
optional no-op lifecycle callback or default method that can omit this cleanup.

### 4.3 Terminal self `NoMoreWrites` barrier

Self `NoMoreWrites` permanently retires one `(writerNodeId, partition)`. It is a compact mechanism
for promptly releasing incomplete state when that proxy instance shuts down cleanly.

Its Kafka offset K is the terminal cutoff. Records from that activation and partition below K
remain valid. Any `TrafficStream` or `WriterPartitionHeartbeat` record above K violates the
protocol. Only duplicate self `NoMoreWrites` is idempotent.

Before emitting it for partition P, the proxy:

1. stops accepting new connections for P under this `writerNodeId` using the routing and acceptance
   gate. The publisher lane remains `OPEN` for existing-connection observations and periodic
   heartbeats;
2. disconnects every Netty connection using P and fully retires each connection under §5.1,
   including Kafka acknowledgement of its terminal observation and every preceding observation;
3. verifies that P's local connection registry is empty;
4. atomically moves P's publisher lane from `OPEN` to `RETIRING` and asynchronously quiesces the
   periodic heartbeat publisher: no future run may start, and any
   callback already running must either have entered the lane before `RETIRING` or finish without
   submitting. The quiescence completion gate must not block a Netty event loop;
5. waits for every remaining send previously accepted by P's publisher to succeed and treats any failed
   or ambiguous send as a capture failure under §7;
6. submits and acknowledges `NoMoreWrites`; and
7. moves the publisher lane to `RETIRED` before reporting completion.

The `RETIRING` transition and heartbeat-publisher quiescence ensure that no delayed periodic task
can submit an ordinary heartbeat after terminal `NoMoreWrites`. Multiple retirement requests share the
same idempotent completion gate.

The useful guarantee is:

```text
Every source-completable request from this proxy instance on P
has a complete acknowledged Kafka representation before NoMoreWrites.
```

Because every connection is fully retired before the lane enters `RETIRING`, no proxy thread
remains able to submit source or capture bytes for P after `NoMoreWrites`. The source service may
still finish processing a request that it received before disconnection, but that request's complete
Kafka representation is already before the proxy-completion record.

After acknowledgement, no code path may reopen capture submission for P under this
`writerNodeId`.

### 4.4 Hard failure

A killed, crashed, or indefinitely suspended process may emit no `NoMoreWrites`. That is expected.
The replayer may still settle a known incomplete accumulator through the skew-adjusted broker-time
rule in §6.3. Without a qualifying later partition record, the affected accumulation remains
retained. Group departure does not substitute for completion.

---

## 5. Connection retirement, heartbeats, and the pre-forward liveness gate

### 5.1 Local registry and connection retirement

For every `(writerNodeId, partition)`, the proxy maintains an exact registry of **all** open
captured connections under that identity, including idle connections. This registry is local
bookkeeping. The proxy never serializes or publishes the connection set.

Creating a captured client connection adds it to the local registry before the proxy accepts its
first `TrafficObservation`. The connection permanently retains its selected `writerNodeId` and
partition.

**Connection retirement** is the lifecycle of one closed connection:

1. Netty reports the connection closed because of remote closure, local closure, or channel
   failure.
2. After that notification, the kernel and Netty provide no further network traffic for the
   connection.
3. The connection's event-loop owner submits its terminal connection observation through the Kafka
   publisher's connection-local submission chain after every earlier observation for the
   connection.
4. The event-loop owner waits for Kafka to acknowledge the terminal observation and every earlier
   `TrafficObservation` for the connection.
5. Only after those acknowledgements does the event-loop owner remove the connection from the local
   registry.

If any required Kafka acknowledgement fails or becomes ambiguous, the connection is not
successfully retired. The process enters its compromised state. It must not keep publishing
heartbeats as though capture remained healthy.

The terminal observation is the only normal per-connection completion record. After the replayer
processes it, any later `TrafficObservation` for the same connection identity is a protocol
violation. A heartbeat cannot complete, omit, reopen, or otherwise change a connection.

### 5.2 Heartbeat publication

Every current or still-draining `(writerNodeId, partition)` with an active publisher emits one
atomic `WriterPartitionHeartbeat` at `heartbeatInterval`. The default interval is 10 seconds.
Each record carries that configured value in `heartbeatIntervalMillis` for information only. The
replayer uses separately configured expiration rules and may add sanity checks for this field
later.

Heartbeat publication is sequenced per writer and partition. A later heartbeat is not submitted
until the previous heartbeat has been acknowledged and its timestamp accepted. Overlapping
periodic callbacks coalesce into the next run. A failed, ambiguous, or late heartbeat closes the
capture gate, so no later heartbeat is authoritative.

Each `(writerNodeId, partition)` also has a local monotonic heartbeat acknowledgement deadline. The
initial deadline starts when the initial heartbeat is submitted. Every accepted heartbeat renews
the deadline for another `E`. If the deadline expires before the required acknowledgement is
processed, the first expiration task atomically compromises the process. A later Kafka
acknowledgement cannot update capture state, renew the deadline, activate an assignment, or complete
writer retirement. This local deadline is independent of the broker `LogAppendTime` validation in
§5.4.

`WriterPartitionHeartbeat` is exactly one Kafka record and contains no connection identities.
There is no chunk assembly, partial-heartbeat state, sequence number, or connection-membership
interpretation.

### 5.3 Initial heartbeat

For the first assignment and each replacement assignment, before accepting the first captured
client connection for a partition permitted by that assignment, the proxy must increment
`assignmentSequence`, create the assignment's `writerNodeId`, and acknowledge an initial heartbeat
for that identity and partition. This establishes the first accepted broker-time baseline before
the writer can publish connection traffic.

### 5.4 Acknowledged heartbeat broker time

Kafka must use `LogAppendTime` for the traffic topic. The proxy tracks, for each
`(writerNodeId, partition)`:

```text
lastAcceptedHeartbeatLogAppendTime
```

The initial heartbeat establishes `lastAcceptedHeartbeatLogAppendTime`. That baseline is continuous
for the lifetime of the `(writerNodeId, partition)`. An empty local connection registry,
inactivity, and later assignments do not reset it.

The default configured heartbeat expiration interval `E` is 30 seconds with the default 10-second
publication interval. The interval and `E` are configurable, but `heartbeatInterval` must remain
lower than `E`.

The proxy and replayer must receive the same `E` and `S` values for one capture-and-replay run; the
managed orchestration requirement is defined in
[Managed Fleet Capture Recovery](managedFleetCaptureRecovery.md).
Unmanaged deployment configuration must preserve the same agreement.

A later heartbeat updates the value only when:

```text
heartbeatLogAppendTime - lastAcceptedHeartbeatLogAppendTime < E
```

A failed, ambiguous, or late heartbeat does not update the value. When the difference is greater
than or equal to `E`, the proxy irreversibly enters its compromised state and immediately follows
the process-wide `--capture-failure-policy` in §7. That process never becomes capture-authoritative
again.

This conclusion is enforced at the proxy rather than reconstructed downstream. As specified in
§5.2, the next heartbeat is not submitted until the current heartbeat is acknowledged and its
timestamp is accepted. A late acknowledged heartbeat closes the capture gate before any later
heartbeat can be submitted. Tests must prove this ordering. The replayer does not
maintain a separate sticky-lapse state.

### 5.5 Pre-forward liveness check

For every Critical Mutation Traffic request:

1. capture and acknowledge every `TrafficObservation` needed to reconstruct the complete request;
2. obtain the Kafka `LogAppendTime` of the `TrafficStream` containing the observation that permits
   the request to take effect;
3. immediately before submitting the corresponding source bytes, read the current capture state
   and `lastAcceptedHeartbeatLogAppendTime`;
4. permit source submission only when capture remains authoritative and:

   ```text
   observationLogAppendTime - lastAcceptedHeartbeatLogAppendTime < E
   ```

5. otherwise irreversibly enter the compromised state and apply `--capture-failure-policy`:
   `fail-closed` does not forward the waiting request and closes the connection as the process
   terminates; unmanaged `fail-open` forwards the waiting request without authoritative capture
   and leaves the connection open in permanent pass-through mode; managed `fail-open` blocks that
   forwarding until the controller acknowledgement required by §7.3.

The check and source-forwarding decision must share a one-way local capture gate. Once the gate
closes, no thread may newly pass the check. This avoids a simple check-then-transition race inside
the process.

No local check can fence a thread that has already entered an uninterruptible source syscall.
That does not violate the central invariant: the request was completely acknowledged to Kafka
before that syscall was allowed.

### 5.6 Timing relationship

The configured heartbeat publication interval remains lower than `E`, with margin for scheduling,
Kafka publication, acknowledgement latency, retries, and the fleet's operational skew threshold.
The defaults are 10 seconds and 30 seconds respectively:

```text
heartbeatInterval < E
```

---

## 6. Replayer interpretation

### 6.1 Complete requests

A request that the capture parser already recognized as complete and detached from its connection
continues normally regardless of later heartbeats, `NoMoreWrites`, or proxy health.

Connection completion and writer expiration apply only to incomplete per-connection HTTP
accumulation. They must never discard an already detached complete request merely because its proxy
later became silent.

### 6.2 Connection and writer completion

For one captured client connection emitted by writer identity X on partition P:

| Observation | Replayer action |
|---|---|
| normal terminal connection-close record | Expire the connection's incomplete request and source-response accumulators, finish the connection through the ordinary close path, and reject every later `TrafficObservation` for that identity |
| timely heartbeat from X on P | Update the writer-partition broker-time baseline; do not change any connection directly |
| heartbeat lapse proved by §6.3 | End the current process-local reconstruction for each already-known connection from X on P; expire its incomplete request and source-response accumulators; let already-detached complete requests continue; do not permanently retire X or any connection identity |
| valid `NoMoreWrites` whose Kafka header identifies X, on P | Settle all incomplete state already known for X on P and permanently retire that writer-partition identity |
| traffic or heartbeat from X on P at a Kafka offset above terminal cutoff K | Retain the containing record, emit high-severity diagnostics, alarm, and terminate the replayer process; do not treat it as a new activation |
| exact duplicate valid `NoMoreWrites` for X on P | Treat idempotently as the same terminal retirement |
| `NoMoreWrites` with a missing or malformed writer header | Warn, treat the record as inert, and do not retire any state |

The terminal connection observation is the traffic-observation cutoff for the connection.
`connectionId` is unique within its `writerNodeId`. Heartbeats never reopen, complete, or retire a
connection.

Kafka record composition does not weaken this rule. A Kafka record may contain several
observations, but the replayer can commit only the whole record. The record remains uncommitted
until every observation's required processing is complete. Expiration releases only the affected
incomplete accumulator; target replay, tuple output, or other processing associated with the same
record may remain pending.

Only a valid `NoMoreWrites` published by that writer identity's terminal publisher lane permanently
retires `(writerNodeId, partition)`. A hard crash emits no such record.

### 6.3 Skew-adjusted broker-time expiration

The normative timestamp proof, including restart after the preceding heartbeat has already been
committed, is defined once in
[Capture and Replay Architecture §§8–8.1](captureAndReplayArchitecture.md#8-broker-time-expiration-of-known-connection-state).

This proxy protocol supplies the proof's capture-side premises:

- the proxy and replayer use separately configured, agreed `E` and `S` values;
- `heartbeatIntervalMillis` is informational and does not change those values;
- the proxy serializes heartbeat publication and cannot restore freshness after accepting a late
  heartbeat; and
- §5.5 rejects Critical Mutation Traffic whose acknowledged observation is outside the accepted
  heartbeat baseline.

The replayer expires only incomplete accumulators already known at the qualifying offset. It does
not retire the writer, partition, or connection identity. A later first observation starts fresh
state. Already-started target work continues, and every reconstituted request still requires
durable tuple output. Consumer wall-clock time is not commit authority, and an unhealthy or
unattested skew bound disables expiration.

### 6.4 Long gaps

A connection with no traffic for hours remains represented because its writer-partition heartbeats
remain timely. No application-idle timeout is inferred from that silence.

### 6.5 Timestamp domains

The protocol intentionally contains independent values with different meanings:

- `TrafficObservation.ts` is source event time for replay pacing;
- `WriterPartitionHeartbeat.heartbeatIntervalMillis` reports configured publication cadence for
  information only;
- optional `WriterPartitionHeartbeat.emittedAtMillis` is diagnostic only;
- Kafka `LogAppendTime` is used only for the skew-adjusted proxy and replayer expiration proof in
  §5.4 and the top-level architecture's §§8–8.1; and
- Kafka offsets order records within a partition.

No conversion between source event time and broker time is part of expiration correctness.

### 6.6 Commit behavior

When a connection's incomplete source state is settled, all of its retained source records follow
the ordinary terminal-disposition and commit-accounting path. No special force-commit bypass is
introduced.

The replayer advances a partition's Kafka commit watermark only across a contiguous prefix of
`Commit` dispositions. A `Retain` disposition closes process-local contexts but deliberately keeps
its offset eligible for redelivery and therefore continues to block that watermark, as does
incomplete state that has not reached a sound terminal disposition.

---

## 7. Proxy failure behavior

### 7.1 Shared transition rule

The first of these events closes the local capture gate permanently for the process:

- any Kafka failure, timeout, or ambiguous result exposed to the application;
- expiration of a heartbeat acknowledgement deadline `E`; or
- any other event proving that capture can no longer be trusted.

The transition atomically closes every capture-authoritative gate and notifies the acceptor,
connections, heartbeat scheduler, and publisher lane. Capture never resumes within the same
compromised process. A new assignment and a later Kafka acknowledgement cannot restore capture
authority to that process. Previously acknowledged traffic remains valid.

The Kafka client may retry internally before exposing an outcome. Once a failure, timeout, or
ambiguous result is exposed to the proxy, there is no application-level resubmission. Recovery
requires a fresh process and fresh `captureActivationId`.

The process emits a high-severity alarm containing at least:

- `processId`, `captureActivationId`, and every affected `writerNodeId`;
- mode;
- affected partitions;
- last acknowledged heartbeat submission age from the proxy's local monotonic clock;
- last acknowledged traffic offset when available;
- producer error or stale-gate reason; and
- whether source forwarding continued.

### 7.2 `fail-closed`

After capture is compromised:

- the request currently waiting at the pre-forward check is not sent to the source;
- the process emits high-severity diagnostics; and
- the process terminates immediately with a distinct nonzero capture-failure exit status.

It does not attempt connection retirement, writer retirement, or `NoMoreWrites`. Those operations
require trustworthy Kafka acknowledgements. The replayer handles the termination as a crash and
does not fabricate completion.

### 7.3 `fail-open`

After the gate closes:

- Kafka capture submission remains permanently closed;
- the process emits a loud, persistent capture-gap alarm; and
- it does not claim orderly connection or writer retirement for the compromised capture
  activation.

In an unmanaged deployment, the Critical Mutation Traffic request waiting at the pre-forward check
and later traffic follow the configured one-way pass-through behavior immediately.

In a controller-managed deployment, capture compromise and permission to begin pass-through are
separate transitions:

1. The proxy enters `COMPROMISED_AWAITING_CONTROLLER_ACK`. Capture remains permanently closed, and
   no source-bound traffic that has not already passed the capture-before-forward gate may be
   forwarded.
2. The proxy immediately reports the compromise on an authenticated control path. The report
   identifies the exact resource, process, Pod, capture activation, session, and controller fence.
   Because one capture activation can be compromised only once, retries for that exact activation
   are idempotent and require no second event identity.
3. The controller durably marks capture incomplete and terminal for that activation before it
   acknowledges the report. Merely receiving a notification, scraping a metric, or observing an
   unhealthy endpoint is not acknowledgement.
4. Only after the proxy receives an authenticated acknowledgement matching its current activation
   and controller fence may surviving existing connections and new connections forward without
   capture. The proxy then enters `PASS_THROUGH_COMPROMISED`.
5. If acknowledgement does not arrive within the configured finite controller-acknowledgement
   deadline, the proxy terminates without forwarding the blocked traffic.

The notification must not use the traffic Kafka cluster whose failure caused the transition. A
push notification or controller-held event stream is the normal low-latency path. Status polling
may discover the pending transition, but the controller must still perform the explicit
acknowledgement after its durable state update. Duplicate reports and acknowledgements are harmless.
If the controller persisted the transition but its response was lost, the proxy retries and the
controller returns the same acknowledgement.

The process must not resume heartbeats or generate captured traffic under the same or a new
`writerNodeId`, even if Kafka later recovers. Recovery requires retiring the process and starting a
fresh capture-authoritative process.

### 7.4 Process suspension

A process may be suspended after Kafka acknowledgement and before source submission. If it resumes:

- in strict mode, the pre-forward stale-heartbeat check prevents execution once the threshold is
  exceeded;
- if suspension occurred after the source-forwarding decision, the request was already completely
  captured and remains replayable; and
- in pass-through mode, the capture gap is an accepted and alarmed policy outcome.

This is the base containment boundary without broker transactions. A managed controller adds the
pass-through acknowledgement rule in §7.3 so no uncaptured forwarding can begin before the fleet
has durably recorded incomplete capture.

### 7.5 Membership observations

A missing group member, failed health check, or control-plane declaration of node death should
alarm operators and may trigger process replacement. It does not cause replay settlement and does
not cause another proxy to write completion on behalf of the missing proxy activation.

After the first usable assignment, membership polling and callbacks do not control capture or
connection acceptance. The proxy continues using its last usable assignment until Kafka supplies a
replacement. A replacement assignment creates a new writer identity for subsequently opened
connections after its initial heartbeats are acknowledged. Existing connections remain unchanged.

Membership recovery does not restore capture because membership loss never removed capture
authority. Conversely, membership recovery cannot restore a process that entered `fail-open` or
avoid termination after a capture-compromising failure.

---

## 8. Recovery after a capture gap

Pass-through creates an interval whose source effects cannot be proven complete from Kafka. The
system must not silently splice later strict capture onto the previous replay run.

Recovery is:

1. alarm and record the start of the gap;
2. retire every process that entered pass-through;
3. restore a fully strict capture fleet with fresh process and capture-activation identities;
4. create a new managed capture session, trusted replay-boundary plan, and session-aware
   checkpoints, using either a complete same-topic reset package or a distinct immutable topic as
   that plan's Kafka boundary;
5. establish the source-specific barrier proving that failed-run operations can no longer complete;
6. take a fresh source snapshot appropriate to the replay workflow; and
7. start a new replay run from the trusted boundary.

The managed controller marks the failed capture resource terminal and does not restore capture or
authorize a replacement snapshot within that resource. A fresh workflow
may proceed only after the managed-fleet addendum's Kafka-input-isolation, ended-run workload retirement,
and source-quiescence preconditions are satisfied. If that fresh workflow reuses the same Kafka
topic, its complete session-fenced reset package is required. Automatic same-resource reset,
regrant, and coverage restoration are not specified.

---

## 9. Component responsibilities

| Responsibility | Required behavior |
|---|---|
| Group assignor | Use a Kafka-provided assignment strategy with no custom assignor, subscription `userData`, assignment metadata, member readiness state, or group-size barrier. Retain the last usable assignment until Kafka installs a replacement. Stickiness and cooperative movement are optional routing optimizations. |
| Startup | Add out-of-group capability probes using `writerNodeId = captureActivationId + ":PROBE"` before joining the group; probes remain inert to replay. |
| Routing | Keep the last usable assignment until a replacement is initialized; increment `assignmentSequence` and create a new assignment-scoped `writerNodeId` for that replacement; persist each connection's immutable writer identity and partition. |
| Registry | Maintain exact all-open connection sets locally per `(writerNodeId, partition)`; retain older registries while their connections drain; never serialize the set; remove a closed connection only after its terminal and all earlier observations are acknowledged. |
| Publisher | Publish one atomic `WriterPartitionHeartbeat` per writer and partition with no connection identities or chunking; preserve connection-local acknowledgement and terminal self-`NoMoreWrites` ordering while packet capture on other connections continues concurrently. Keep one producer and one lane owner that orders submissions and callbacks per writer and partition; drain all submissions and callbacks before handoff. |
| Source forwarding | Establish an acknowledged initial heartbeat baseline; reject late heartbeats; compare acknowledged observation `LogAppendTime` against `lastAcceptedHeartbeatLogAppendTime`; make compromise irreversible. |
| Failure mode | Make capture abandonment irreversible per process; implement strict exit and permanent pass-through transitions. In managed mode, block uncaptured source forwarding until the controller durably records incomplete capture and acknowledges the exact activation; terminate if the bounded acknowledgement deadline expires. |
| Record validation | Accept terminal self `NoMoreWrites` idempotently; treat a `NoMoreWrites` record whose writer header does not identify its own writer-partition stream as inert; retain the poison record, log and metric at least once, alarm, and terminate the replayer process on protocol violations. |
| Replayer | Apply the normative broker-time expiration proof in the top-level architecture to expire only already-known accumulators; treat a future first observation as fresh initialized state. |
| Observability | Emit accepted heartbeat `LogAppendTime`, skew health, broker-time expiration, capture-gate, retained-incomplete-state, and capture-gap metrics. |

### 9.1 Required status

Each proxy should expose:

- process identity, `captureActivationId`, current `assignmentSequence`, current
  `writerNodeId`, and every older writer identity still draining;
- whether the first usable assignment has been received, the last usable assignment, and any
  replacement assignment being initialized;
- capture mode and whether the capture gate is open;
- whether a managed compromise is awaiting controller acknowledgement, the age of that wait, and
  whether the matching acknowledgement was accepted;
- current connections by `(writerNodeId, partition)`;
- pending connection retirements and the oldest unacknowledged terminal-or-earlier observation;
- `lastAcceptedHeartbeatLogAppendTime` and the latest rejected late-heartbeat time by
  `(writerNodeId, partition)`;
- oldest unacknowledged publisher work;
- whether the process has permanently abandoned capture; and
- capture-gap alarm state.

The replayer should expose:

- incomplete per-connection HTTP accumulations by writer identity and partition;
- accepted heartbeat broker time, fallback observation time when used, `E`, `S`, and broker-time
  expiration counts;
- whether the fleet currently attests the clock-skew bound;
- terminal-disposition counts by normal close, broker-time expiration, and terminal self
  `NoMoreWrites`;
- unresolved record-processing counts;
- retained state for which no sound terminal disposition exists;
- invalid or cross-wired `NoMoreWrites` records; and
- the oldest commit-blocking state.

---

## 10. Implementation requirements and unsupported scope

### 10.1 Heartbeat schema and connection retirement

Before implementation is declared complete:

- `TrafficObservation` carries the connection-local observation sequence defined in §4.1;
- `WriterPartitionHeartbeat` carries the writer identity, partition, informational
  `heartbeatIntervalMillis`, managed `captureSessionId` when applicable, and optional diagnostic
  `emittedAtMillis`;
- connection addition to the local registry precedes acceptance of its first
  `TrafficObservation`;
- Netty's terminal connection observation is the final contiguous
  `connectionObservationSequence` value for the connection;
- any later `TrafficObservation` for that connection is rejected even when its sequence is
  contiguous;
- the connection remains in the local active set until Kafka acknowledges that terminal observation
  and every earlier observation for it;
- a failed or ambiguous terminal send cannot remove the connection or leave the process publishing
  healthy heartbeats;
- one heartbeat is acknowledged and accepted before the next is submitted; and
- tests prove that heartbeats never complete, omit, reopen, or otherwise mutate per-connection
  state.

### 10.2 Known limitation: streaming source execution

This protocol assumes that the source application cannot mutate state until it has received the
complete HTTP request. Under that assumption, withholding the final request bytes until complete
Kafka acknowledgement preserves §1.3.

Full-request buffering and source handlers that apply effects while streaming an incomplete body
are outside the protocol. Deployments with those semantics cannot claim the strict-mode
capture-before-forward guarantee for those endpoints.

HTTP chunked transfer encoding remains supported when it is only wire framing and the source still
waits for the complete request. Supporting true streaming execution requires buffering or
spooling the complete mutating request, acknowledging its complete Kafka representation, checking
the capture gate, and only then releasing effect-causing bytes to the source.

### 10.3 Mutating-request classification

The central invariant applies to requests classified as Critical Mutation Traffic by the
configured HTTP-method predicate. A deployment whose source mutates state for requests outside that
predicate does not satisfy the strict capture-before-forward contract. Supporting such a source
requires a source-specific mutation policy, a default-mutating rule, or an explicit read-only
allowlist.

### 10.4 Expiration implementation

The replayer must implement
[Capture and Replay Architecture §§8–8.1](captureAndReplayArchitecture.md#8-broker-time-expiration-of-known-connection-state)
and verify that the fleet's skew-bound attestation remains healthy. It expires only already-known
incomplete accumulators. It does not retire the writer or partition, and a future first observation
initializes fresh state.

### 10.5 Proxy-completion schema

The `NoMoreWrites` protobuf body carries neither writer nor partition. The Kafka record header
supplies the authoritative `writerNodeId`, and Kafka record metadata supplies the authoritative
partition. A missing or malformed writer header makes the record inert and cannot settle state.

### 10.6 Managed-fleet addendum

The managed-fleet addendum makes a capture failure terminal for the resource: the resource remains
incomplete and recovery starts a new capture and replay run. Every managed fresh run requires a
controller-issued session, trusted replay-boundary plan, and session-aware checkpoints. The
addendum also specifies:

- terminal self-only `NoMoreWrites`;
- heartbeat-based expiration and explicit retention when neither a broker-time proof nor self
  completion exists;
- the irreversible local capture gate;
- explicit new-run recovery after a capture gap;
- session identities that reject delayed records from an ended run;
- separate ended-run capture-activation retirement and Kubernetes traffic retirement; and
- immutable pre-grant replay start offsets—reset-derived for a same-topic run or sampled after setup
  for a distinct-topic run—that remain conservative across the new source snapshot.

---

## 11. Validation plan

### 11.1 Deterministic proxy tests

- A mutating request cannot submit execution-enabling source bytes before complete Kafka
  acknowledgement.
- The supported-source contract states that mutating handlers do not apply effects before the
  complete HTTP request arrives; true streaming source execution remains out of scope.
- Mutating-request classification uses the configured HTTP-method predicate, and tests identify
  requests outside that predicate as unsupported by the strict guarantee.
- A stale heartbeat after capture acknowledgement blocks strict-mode source forwarding.
- The initial heartbeat establishes `lastAcceptedHeartbeatLogAppendTime` before a source connection
  is accepted.
- A later heartbeat whose broker-time difference is greater than or equal to `E` does not update the
  baseline and irreversibly compromises the proxy.
- An acknowledged observation whose difference from the accepted heartbeat baseline is greater than
  or equal to `E` is not forwarded to the source.
- Closing the capture gate races safely with many source-forwarding threads.
- A source-forwarding operation allowed before gate closure has a complete Kafka representation.
- Failed or ambiguous heartbeat publication does not refresh proxy-local acknowledged-heartbeat
  freshness, and a later acknowledgement cannot restore capture.
- Periodic callbacks never submit the next heartbeat before the previous heartbeat is acknowledged
  and accepted.
- A heartbeat is one Kafka record with no connection identities, chunks, or connection-lifecycle
  sequence.
- Publisher initialization failure closes its producer and executor; repeated failed starts leave
  no publisher threads behind.
- Registry addition precedes acceptance of the connection's first `TrafficObservation`.
- Netty close caused by remote closure, local closure, or channel failure produces exactly one
  terminal connection observation after every earlier observation in the connection's event-loop
  submission chain.
- The kernel and Netty produce no later network traffic for the connection after Netty reports
  closure.
- Netty's terminal observation immediately becomes the replayer's traffic-observation cutoff for
  that connection; a later contiguous sequence value is retained, alarmed, and terminates the
  replayer process.
- The connection remains in the active set until Kafka acknowledges its terminal observation and every earlier
  `TrafficObservation`.
- A failed or ambiguous terminal send leaves the connection registered, compromises capture, and
  prevents later healthy heartbeat publication.
- Heartbeats never complete, omit, retire, or reopen a connection.
- The connection-local submission chain preserves same-connection Kafka order, and
  `connectionObservationSequence` makes a gap, regression, or conflicting duplicate a protocol
  violation.
- Removing a closed connection from the active set is impossible until Kafka acknowledges its
  terminal observation and every earlier `TrafficObservation`.
- Terminal self `NoMoreWrites` closes the new-connection gate first, fully retires all related
  Netty connections, verifies the registry is empty, enters `RETIRING`, quiesces periodic
  heartbeats, waits for remaining accepted sends, and then acknowledges `NoMoreWrites`.
- A periodic heartbeat callback racing permanent `(writerNodeId, partition)` retirement either entered the lane
  before `RETIRING` and completes or exits without submission; it never publishes after terminal
  `NoMoreWrites`.
- Producer configuration cannot disable idempotence, weaken `acks=all`, or set
  `max.in.flight.requests.per.connection` above the ordering-preserving limit.
- Startup fails closed when the effective producer settings cannot preserve same-partition lane
  order across retries.
- Multiple producers are allowed only with one non-overlapping producer and lane owner that orders
  submissions and callbacks per writer and partition, and handoff waits for every previous
  submission and callback.
- Pass-through never resumes capture.
- Strict mode stops new source execution and exits.

### 11.2 Heartbeat and replayer tests

- A heartbeat updates only the writer-partition broker-time baseline and contains no connection
  membership.
- `heartbeatIntervalMillis` reports the proxy configuration but does not change the replayer's
  separately configured expiration rules.
- An idle connection's incomplete state remains live while its writer-partition heartbeats remain
  timely.
- A terminal connection observation expires that connection's incomplete request and
  source-response accumulators.
- A `TrafficObservation` after the connection's terminal observation is a protocol violation.
- One Kafka record containing several observations remains uncommitted until every observation's
  required processing is complete.
- Terminal self `NoMoreWrites` settles prior incomplete state and retires the
  `(writerNodeId, partition)`.
- Later traffic or heartbeats after terminal self `NoMoreWrites` are retained, alarmed, and
  terminate the replayer process; duplicate self completion is idempotent.
- A `NoMoreWrites` record with a missing or malformed writer header is inert and does not settle
  state.
- `NoMoreWrites` obtains its partition only from Kafka record metadata; its protobuf body cannot
  disagree with the partition that contains it.
- A later record from any writer on the partition may establish `R - M >= E + S` and expire only
  the silent writer's already-known incomplete accumulators.
- After restart when the preceding heartbeat is before the committed cursor, the first observation
  establishes the conservative `R - T >= E + 2S` fallback.
- A heartbeat encountered after that first observation replaces the fallback with its exact
  `LogAppendTime`.
- A later assignment may create fresh initialized connection state on the same partition under its
  new `writerNodeId`; expiration never joins that state to an older expired accumulator.
- A later first observation for an unknown connection under an old writer identity also starts
  isolated state and may expire independently. The proxy invariant prevents a compromised process
  from publishing a later heartbeat that restores freshness; the replayer adds no sticky-lapse
  state.
- A later observation for a connection whose previous process-local reconstruction expired starts
  fresh reconstruction and never joins the expired state. A complete request from that fresh state
  replays normally; an incomplete one may expire independently.
- A violated or unattested `S` bound disables broker-time expiration and retains the affected
  records.
- Source event time, proxy monotonic time, Kafka offset, and Kafka timestamp remain separate
  domains.
- A complete request continues replay even when its connection later closes or its source response
  expires.
- Every terminal path settles its process-local accounting exactly once; only contiguous
  `Commit` dispositions advance Kafka's commit watermark.

### 11.3 Membership tests

- A process completes leader-broker probes before joining.
- Every capability probe uses `writerNodeId = captureActivationId + ":PROBE"` and creates no writer baseline,
  heartbeat state, or connection state in the replayer.
- A process joins the group only after its capability probes are acknowledged.
- Scale-up moves eligibility to accept new captured client connections using a Kafka-provided
  assignment while the existing proxy connection set drains in place.
- The protocol works with the deployment's Kafka-provided assignor without custom subscription or
  assignment metadata. Controller startup capacity uses proxy `captureReady` status rather than
  group membership.
- Every new assignment increments `assignmentSequence` and creates a new `writerNodeId` for new
  connections.
- Existing connections keep their original writer identities across rebalance.
- Every new assignment identity acknowledges an initial heartbeat before accepting a
  connection on a permitted partition.
- An older writer identity emits periodic heartbeats until its connections drain, then emits self
  `NoMoreWrites`.
- Rapid rebalances may leave several writer identities draining concurrently without sharing
  registries, publisher lanes, or broker-time baselines.
- Revocation, partition loss, empty polls, coordinator outage, and failed membership polling leave
  the last usable assignment available for new connections until a replacement assignment is
  installed.
- A stale proxy and its replacement may temporarily assign new connections to the same partition;
  their distinct writer identities keep capture unambiguous.
- Assignment-strategy changes may redistribute every partition without changing capture
  correctness; deployment rollout still obeys Kafka's own group-protocol compatibility rules.

### 11.4 Failure tests

- Kill a proxy with open idle and active connections and no self completion; verify that a
  qualifying later partition record expires only already-known incomplete state.
- Repeat without a later partition record; verify that the incomplete Kafka state remains retained
  and alarms.
- Suspend a proxy beyond the stale threshold and resume it; verify strict source execution is
  blocked after complete Kafka capture.
- Suspend it after source forwarding was allowed; verify the request was already completely
  captured.
- Fail Kafka during heartbeat publication in strict and pass-through modes.
- Create an ambiguous producer send and verify the capture gate closes.
- In `fail-closed`, verify that Kafka or capture compromise terminates immediately without orderly
  retirement.
- In unmanaged `fail-open`, verify that existing and new TCP connections forward without capture
  and that Kafka recovery does not resume capture.
- In managed `fail-open`, verify that no uncaptured source traffic is forwarded while compromise
  acknowledgement is pending; the controller durably records incomplete terminal status before
  acknowledging; a matching acknowledgement enables whole-process pass-through; lost and duplicate
  notifications and acknowledgements are idempotent; controller failover reconstructs the durable
  acknowledgement; and deadline expiration terminates without forwarding the blocked traffic.
- Verify that orderly trustworthy shutdown emits a high-severity diagnostic after its five-minute
  target, submits `NoMoreWrites` at most once, and fails retirement on the first
  application-visible Kafka failure or heartbeat acknowledgement deadline expiration.
  Verify that later acknowledgements cannot activate an assignment or complete writer retirement.
  Capture-compromise and unstable-process failures do not enter retirement.

### 11.5 Acceptance criteria

The design is complete when:

1. every source-completable mutating request is completely acknowledged to Kafka first;
2. timely writer-partition heartbeats preserve already-known incomplete state for open idle
   connections without transmitting a connection list;
3. terminal connection observations, broker-time expiration, and terminal self completion release
   only the state they are authorized to settle;
4. only records emitted under the affected writer identity authorize that writer's settlement;
5. later use of a partition occurs under the new assignment's writer identity, while an old
   identity never resumes after terminal `NoMoreWrites`;
6. strict suspension recovery cannot create an uncaptured completed request;
7. pass-through creates a loud, explicit capture gap and never resumes capture; in managed mode,
   the controller durably records incomplete capture before the first uncaptured source byte is
   permitted;
8. Kafka offset, source event time, proxy monotonic time, and Kafka `LogAppendTime` retain their
   distinct meanings; and
9. a Kafka record commits only after all required processing for every observation it contains is
   complete, while retained offsets continue to block Kafka commit progress.

---

## 12. Protocol summary

- Kafka membership only load-balances new connections. Before the first assignment it is a startup
  prerequisite; afterward the proxy retains its last usable assignment through membership loss,
  revocation, and polling failure until a replacement assignment is installed.
- Kafka uses a built-in assignment strategy. The capture protocol has no custom assignor,
  subscription `userData`, assignment metadata, member readiness state, minimum group size, or
  startup quorum. Stickiness and cooperative movement are optional routing optimizations.
- The proxy keeps its exact connection registry locally and never publishes the connection list.
- One atomic `WriterPartitionHeartbeat` per `(writerNodeId, partition)` establishes and refreshes
  the broker-time baseline. It contains no connection identities, chunking, or connection-lifecycle
  sequence.
- The initial heartbeat establishes `lastAcceptedHeartbeatLogAppendTime`; later heartbeats update
  the baseline only while their difference is less than `E`.
- Normal connection completion depends on the durable terminal `TrafficObservation`. Registry
  removal waits for acknowledgement of it and every earlier observation.
- A Kafka record containing several observations commits only after all required processing for
  every observation is complete.
- Capture-before-forward is the primary completeness guarantee.
- Capture-before-forward assumes non-streaming source execution; chunked wire framing
  is allowed only when the source waits for the complete request.
- Requests outside the configured HTTP-method predicate are unsupported by the strict guarantee.
- The proxy compares an acknowledged observation's `LogAppendTime` with the accepted heartbeat
  baseline after Kafka acknowledgement and before source execution.
- `NoMoreWrites` is self-emitted, partition-scoped, ordered, and terminal for that
  `(writerNodeId, partition)`; its writer comes from the required Kafka header and its partition
  comes from Kafka record metadata.
- Kafka producer idempotence and ordering-preserving retry settings are mandatory and cannot be
  weakened by deployment configuration.
- Every new assignment increments `assignmentSequence` and creates an assignment-scoped
  `writerNodeId`; existing connections retain their original identity, and new connections use the
  last usable assignment's identity until the replacement becomes usable.
- After an old writer identity's connections drain, self `NoMoreWrites` permanently retires that
  identity and partition.
- A later record from any writer may establish the skew-adjusted `E + S` broker-time horizon that
  expires only already-known incomplete state for a silent writer. A quiet partition has no such
  evidence and remains retained.
- Expiration ends only the current process-local reconstruction. It does not retire a writer,
  partition, or connection identity. A future observation starts fresh initialized state, but only
  a new assignment-scoped writer identity may legitimately accept a new connection after
  rebalance. An explicit terminal connection observation remains permanent, and later observations
  for that connection are protocol violations.
- If the exact preceding heartbeat is before the committed cursor after restart, the first
  observation provides the conservative `E + 2S` fallback. A later accepted heartbeat restores the
  ordinary `E + S` rule.
- Long inactivity is safe because the writer-partition heartbeat remains timely; the heartbeat does
  not need to name the idle connection.
- `--capture-failure-policy=fail-closed` terminates immediately after capture compromise and does
  not attempt orderly retirement.
- `--capture-failure-policy=fail-open` switches the whole process, including existing and new TCP
  connections, to uncaptured forwarding, alarms loudly, and never resumes capture. A managed proxy
  first blocks uncaptured forwarding until its controller has durably recorded incomplete capture
  and acknowledged the exact capture activation; acknowledgement timeout terminates the process.
- Group-member departure only causes group rebalance; it has no replay meaning.
- The maximum whole-connection lifetime defaults to 60 minutes.
- Orderly shutdown while capture and Kafka remain trustworthy uses a five-minute default retirement
  bound; capture-compromise and unstable-process failures do not use that retirement path.
- Recovery after a capture gap starts a new capture and replay run.
- In managed Kubernetes, a terminal capture failure terminally fails the whole capture workflow;
  same-resource reset, regrant, and replacement-snapshot automation are not specified.
- Replayer wall-clock time is not commit authority. Kafka `LogAppendTime` is authoritative only
  through the `E + S` or conservative restart `E + 2S` proof while the managed fleet attests the
  `S` bound.
