# Proxy Capture Protocol

**Status:** standalone design contract
**Last revised:** 2026-09-11

This document defines horizontal scaling and failure behavior for capture proxies that write traffic
to Kafka for later replay. It covers the controller-less deployment. Managed-fleet terminal-failure
behavior and fresh-run recovery are specified separately in
[`managedFleetCaptureRecovery.md`](managedFleetCaptureRecovery.md).

The design deliberately separates three concerns:

1. Kafka group membership load-balances partitions for new connections.
2. Exact open-connection manifests establish cycle boundaries for resetting incomplete
   per-connection HTTP accumulation.
3. The capture-before-forward contract prevents a strict-mode proxy from completing a source
   request that Kafka cannot later replay.

Group departure causes a rebalance among the remaining members. The proxy that Kafka considers
departed continues using its last usable assignment until it receives a replacement. Departure is
not capture-health or replay evidence and does not authorize another proxy to declare the departed
proxy instance finished.

Five minutes is the default operational target for orderly retirement, not permission to terminate
without attempting terminal self `NoMoreWrites`. Retirement continues after that target. Periodic
manifests continue while existing connections retire. After the connection registry is empty, the
proxy quiesces periodic manifests and keeps trying to publish the final empty manifest and
`NoMoreWrites` until the most recently acknowledged complete manifest reaches the configured
manifest expiration interval `E`. An acknowledged final empty manifest becomes that most recent
manifest before the proxy attempts `NoMoreWrites`.

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
- let the replayer reset incomplete per-connection HTTP accumulation after an exact manifest-cycle
  omission or terminal self `NoMoreWrites`;
- scale proxies horizontally without moving existing connections;
- keep capture and source forwarding ordered correctly during rebalance, shutdown, and Kafka
  failure; and
- alarm loudly when capture develops a gap.

### 1.2 Accepted behavior

The design accepts:

- a complete request may be captured even though it never reaches the source;
- manifest M may omit a connection without affecting an observation for that connection stamped
  M+1 when the connection first became active after M's boundary; M is not applicable to that
  connection lifecycle;
- once an applicable manifest omits a connection, that connection identity is retired and any
  later observation for it is a protocol violation;
- an incomplete accumulation from a hard-crashed proxy may remain retained when no applicable exact
  manifest, terminal self `NoMoreWrites`, or skew-valid later partition record can settle it;
- hard process death may prevent a final proxy-completion record;
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

This ordering is what makes a manifest-cycle reset safe:

- if the source completed the request, Kafka already contains the complete request;
- if Kafka contains only an incomplete request, strict mode could not have completed it at the
  source;
- after Netty reports a connection closed, the kernel and Netty deliver no more network traffic for
  that connection;
- the connection's event-loop owner submits the terminal connection observation after every earlier
  observation in the connection-local Kafka submission chain; and
- only after Kafka acknowledges the terminal observation and every earlier observation does the
  event-loop owner remove the connection from the active set. An applicable omitting manifest is
  therefore prepared after all valid observations for that connection are durable.

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
meaning. The replayer never interprets its `writerNodeId` as a traffic writer identity, manifest
owner, or source of connection state.

### 2.3 Captured client connection

Every accepted connection has a `connectionId` that is unique within its immutable
`writerNodeId`. The proxy records its chosen traffic partition when the connection is accepted:

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
  continue forwarding without capture, the process alarms continuously, and capture never resumes.

A definite transient failure proven not to have compromised capture may retry without invoking
either terminal policy.

---

## 3. Kafka membership and horizontal scaling

### 3.1 Membership is load balancing only

The proxy group distributes Kafka partitions for newly opened source connections. Membership does
not prove capture health, producer health, proxy death, or writer completion, and it does not
authorize peer `NoMoreWrites` records.

Each member publishes enough assignment metadata for every member to compute the same routing
table. The leader uses a custom cooperative assignor so partitions can move gradually.

Every member completes the Kafka capability probe before it joins the group. Before the first
usable assignment, the startup gate also requires:

```text
current group member count >= minimumActiveProxyCount
```

The configured threshold is a startup barrier only. After the first usable assignment, later group
size or membership health does not close a capture or connection-acceptance gate.

### 3.2 Assignment and connection routing

After a process completes §3.3, it joins the group. Before it has ever received a usable
assignment, it cannot accept a captured source connection because it has no Kafka partition for
that connection. A startup failure or attempted source connection before the first usable
assignment invokes the process-wide `--capture-failure-policy`. After either terminal policy is
applied, that process does not later resume capture when membership supplies an assignment.

The first assignment becomes usable only after:

1. the assignment is installed;
2. the startup group-member count satisfies `minimumActiveProxyCount`;
3. the proxy's capture-authoritative mode remains valid; and
4. the initial complete manifests for that assignment's writer identity and usable partitions are
   acknowledged.

Afterward, the proxy retains the last usable assignment until Kafka supplies a replacement.
Revocation, partition-loss callbacks, stale or failed polling, empty polls, delayed heartbeats,
coordinator outages, and group departure do not invalidate it. The proxy continues assigning new
connections with the last usable assignment.

Kafka may temporarily give another proxy the same partition after considering the stale member
gone. This affects load distribution only. The proxies have distinct writer identities, so capture
and replay remain unambiguous.

When a replacement assignment arrives, the proxy creates its next assignment-scoped writer
identity and acknowledges that identity's initial manifests before using the replacement for new
connections. Connections opened earlier retain their original writer identity and partition.
There is no membership-health deadline.

### 3.3 Startup capability probing

A process must not join the proxy group until it has demonstrated that its producer can complete an
acknowledged Kafka write.

While in `PROBING`, it:

1. refreshes metadata for the traffic topic;
2. chooses one representative traffic partition for every distinct current leader broker;
3. emits a semantically inert `CaptureCapabilityProbe` carrying
   `writerNodeId = captureActivationId + ":PROBE"` to each representative partition;
4. waits for all probe acknowledgements; and
5. refreshes metadata again before joining the group.

`CaptureCapabilityProbe` is not replay input. If the replayer later encounters the Kafka record, it
performs no replay action and allows the record to become commit-eligible through ordinary
whole-record accounting.

This probe qualifies current producer reachability. It does not guarantee future availability.
Normal runtime failures are handled by §5 and §7.

### 3.4 Scale up

When member B joins:

1. B completes §3.3 and joins the group.
2. The cooperative assignment transfers new-connection ownership.
3. Every member increments its `assignmentSequence` and creates the new assignment's
   `writerNodeId`.
4. Existing member A stops opening connections under its preceding writer identity when the
   replacement assignment becomes usable. Existing connections keep that preceding identity and
   their original partitions.
5. Each member acknowledges an initial complete manifest for its new writer identity on every
   partition that the assignment permits it to use before accepting a connection there.
6. A continues traffic and periodic manifests for every older writer identity that still has
   connections.
7. After an older `(writerNodeId, partition)` has no connections, A acknowledges its final empty
   manifest and self `NoMoreWrites`, permanently retiring that identity on that partition.

Existing connections never migrate between processes or partitions.

### 3.5 Scale down

A planned process retirement first becomes `DRAINING`:

1. it stops accepting new connections;
2. another member becomes the member accepting new captured client connections for its partitions;
3. existing connections continue in place;
4. the proxy continues traffic and manifest publication for every draining writer identity;
5. after each `(writerNodeId, partition)` connection set is empty, it follows §4.3 and emits
   terminal self `NoMoreWrites` for that identity and partition; and
6. it leaves the group and exits after those records are acknowledged.

This orderly retirement is performed only while capture and Kafka publication remain trustworthy.
Its default completion target is five minutes. Missing the target emits a high-severity diagnostic
and retirement continues through final empty manifests and `NoMoreWrites`. The proxy gives up only
when the most recently acknowledged complete manifest reaches the configured manifest expiration
interval `E`. This timing policy is not used for capture-compromise or unstable-process failures
described in §7.

### 3.6 Later use of a partition

A live proxy process may become eligible to accept new connections on a partition that it used in
an earlier assignment. Those new connections use the last usable assignment's `writerNodeId`. A
replacement identity acknowledges its own initial complete manifest before
accepting its first connection on that partition.

The earlier writer identity never resumes. Empty manifests for an active identity remain ordinary
heartbeats; they do not end or reset its broker-time baseline. After the earlier identity's
connections drain, its final empty manifest and self `NoMoreWrites` permanently retire that
`(writerNodeId, partition)`.

Rapid rebalances may leave several writer identities draining on the same Kafka partition while the
last usable assignment's identity accepts new connections there. Every connection registry, manifest
cycle, publisher lane, broker-time baseline, and retirement state is therefore keyed by
`(writerNodeId, partition)`, not by one process-global current identity.

### 3.7 Kafka 4 and assignment-protocol rollout

The initial implementation uses Kafka's Classic group protocol because the required custom
cooperative assignment metadata is under application control.

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
    observations[] {
        manifestCycle
        connectionObservationSequence
        captureTimestamp
        ...
    }
    ...
}

LivenessSnapshotChunk {
    writerNodeId
    partition
    manifestCycle
    chunkIndex
    chunkCount
    emittedAt
    connectionIds[]
}

NoMoreWrites {
    partition
}

CaptureCapabilityProbe {
    writerNodeId = captureActivationId + ":PROBE"
    probeId
    captureSessionId?  // required for controller-managed mode; absent only in unmanaged mode
}
```

The Kafka record header for `NoMoreWrites` carries the authoritative `writerNodeId`. A proxy may
publish the record only through that writer identity's publisher lane. A missing or malformed
writer header makes the record inert: the replayer warns and may commit the inert record, but it
does not retire any writer or connection state.

`manifestCycle` is a monotonically increasing value scoped to one
`(writerNodeId, partition)`. It is the logical boundary described in §5.1. It is not a wall-clock
timestamp, Kafka offset, group generation, or Kafka producer epoch.

Every `TrafficObservation` carries the cycle that was current when the proxy accepted it. A
`TrafficRecord` remains scoped to one
`(writerNodeId, partition, connectionId)`, but its observations may carry different manifest
cycles. The replayer creates exactly one deterministic child obligation for each observation. A
manifest decision may satisfy the incomplete-accumulator WorkClaims it covers while transaction or
other WorkClaims beneath the same child remain unresolved. The parent Kafka offset becomes
commit-eligible only after every child obligation is terminal `Satisfied`; any
`RetainRequired` or unresolved child prevents parent commit.

The publisher may flush a batch when `manifestCycle` changes because that simplifies diagnostics
and reduces the number of child fates within one record. That is an optimization, not a correctness
invariant. Flushed and mixed batching must preserve the same per-observation semantics and
WorkClaim decisions without loss or duplication, but may produce different parent-record
identities, commit timing, and redelivery granularity.

`connectionObservationSequence` is a monotonically increasing connection-local value assigned by
the connection's single event-loop owner. It validates the order of `TrafficObservation` values for
that captured client connection. It is not shared across connections and needs no
contended atomic increment. Because the event-loop owner reads an atomic `manifestCycle` that only
increments, `manifestCycle` must be nondecreasing as
`connectionObservationSequence` increases. A later sequence value carrying a lower cycle is a
protocol violation.

Reconstruction-relevant records for one connection enter the producer through a connection-local
submission chain in contiguous sequence order. Other connections and manifest chunks may interleave
freely; there is no global packet lock. Producer idempotence preserves the order in which that
connection's records enter `send`. A missing sequence, repeated sequence with different content, or
regression is a protocol failure that closes capture or causes the replayer to retain the record,
emit high-severity diagnostics, and terminate; the replayer does not wait indefinitely for a lower
sequence. Truly incidental packet diagnostics that cannot affect HTTP
request completion, expiration, source forwarding, or target replay are outside this sequence and
may be best effort.

Netty's terminal connection observation is the final sequence value for a connection. Once the
replayer consumes it, any later `TrafficObservation` for that connection is a protocol violation,
including a contiguous next sequence value. A listing manifest may still list the connection
because it was prepared before acknowledged active-set removal; that listing is valid but cannot
reopen the connection's
traffic-observation state.

### 4.2 Publisher ordering and acknowledgement

Kafka producer ordering is still required within one writer identity and partition, especially for
terminal self `NoMoreWrites`. It is **not** used to force every ordinary packet, connection
lifecycle change, and manifest callback through one global proxy lock. The logical ordering needed
for manifest omission comes from `manifestCycle`, not from assuming that Kafka append order matches
concurrent proxy execution order.

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

The current single producer and serialized publisher lane are sufficient. If publication is later
scaled across multiple producers, then for each `(writerNodeId, partition)`:

- exactly one producer and serialized publisher-lane owner publishes observations and manifests;
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

- `OPEN` accepts existing-connection traffic and ordinary periodic manifests. The separate routing
  and acceptance gate determines whether a new connection may be opened.
- `RETIRING` is entered only after all connections have been fully retired and the exact registry
  is empty. It rejects ordinary manifests. Only the retirement barrier
  may submit the final empty manifest and terminal self `NoMoreWrites`.
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
remain valid even if a scanner discovers `NoMoreWrites` first. Any traffic or manifest record above
K violates the protocol. Only duplicate self `NoMoreWrites` is idempotent.

Before emitting it for partition P, the proxy:

1. stops accepting new connections for P under this `writerNodeId` using the routing and acceptance
   gate. The publisher lane remains `OPEN` for existing-connection observations and periodic
   manifests;
2. disconnects every Netty connection using P and fully retires each connection under §5.1,
   including Kafka acknowledgement of its terminal observation and every preceding observation;
3. verifies that P's exact connection registry is empty;
4. atomically moves P's publisher lane from `OPEN` to `RETIRING` and asynchronously quiesces the
   periodic manifest publisher: no future run may start, and any
   callback already running must either have entered the lane before `RETIRING` or finish without
   submitting. The quiescence completion gate must not block a Netty event loop;
5. waits for every remaining send previously accepted by P's publisher to succeed and treats any failed
   or ambiguous send as a capture failure under §7;
6. emits and acknowledges a final empty manifest through the retirement barrier and then submits
   and acknowledges `NoMoreWrites`; and
7. moves the publisher lane to `RETIRED` before reporting completion.

The `RETIRING` transition and manifest-publisher quiescence ensure that no delayed periodic task can
submit an ordinary manifest after terminal `NoMoreWrites`. Multiple retirement requests share the
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
The replayer may still settle a known incomplete accumulator through an applicable complete
manifest already published by the identity or through the skew-adjusted broker-time rule in §6.3.
Without either fact, the affected accumulation remains retained. Group departure does not
substitute for completion.

---

## 5. Exact manifests and the pre-forward liveness gate

### 5.1 Exact registry and the manifest-cycle boundary

For every `(writerNodeId, partition)`, the proxy maintains an exact registry of **all** open
captured connections under that identity, including idle connections.

Each such registry has a cache-friendly atomic `manifestCycle` counter. Ordinary connection
activity reads that counter; it does not increment it. Once per periodic manifest cycle, the
manifest task increments it. The lifecycle operations that add or remove a connection and the
manifest task's copy-and-increment boundary are linearized with one small writer-partition-local
coordination construct:

```text
prepareManifest(writer W, partition P):
    enter (W, P)'s lifecycle boundary
    M = manifestCycle[W, P]
    S = exact copy of (W, P)'s active captured connections
    manifestCycle[W, P] = M + 1
    leave (W, P)'s lifecycle boundary
    publish complete manifest {cycle=M, connections=S}
```

Creating a captured client connection linearizes its addition to the exact registry **before** the
proxy accepts its first `TrafficObservation`.

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
5. Only after those acknowledgements does the event-loop owner remove the connection from the exact
   active set under `(writerNodeId, P)`'s lifecycle boundary.

A manifest prepared before step 5 lists the connection. A manifest prepared after step 5 may omit
it. If any required Kafka acknowledgement fails or becomes ambiguous, the connection is not
removed under this protocol; capture fails closed instead of publishing an authoritative omission.

Ordinary packet capture does not take the partition lifecycle boundary. Preparing manifest M
linearizes only the exact-registry copy and counter increment shown above.

Every `TrafficObservation` reads the current `manifestCycle[writerNodeId, P]` when the proxy accepts
it for publication and carries that value to Kafka. Kafka records may arrive in a different order
from the proxy-side lifecycle operations. The cycle value, rather than Kafka position alone,
resolves the race:

- if the connection was added before M's lifecycle boundary and not removed before that boundary,
  M lists the connection;
- if the connection was added after the boundary, its observations carry a cycle greater than M,
  so omission by
  M cannot reset the accumulation begun by those observations;
- if the connection was removed before M's boundary, M omits it;
- if the connection was removed after the boundary, M still lists it and a later manifest may omit
  it.

Manifest omission is evaluated separately for the connection's current incomplete request
accumulator and current incomplete source-response accumulator. For either accumulator, use the
cycle carried by the earliest `TrafficObservation` contributing to that specific accumulator:

- If that cycle is greater than manifest M, M predates the accumulator and does not expire it.
- If that cycle is no greater than M and M omits the connection, M expires that incomplete
  accumulator.

The connection-opening observation does not decide applicability after it is no longer part of an
incomplete accumulator. Manifest omission does not complete or cancel independent target replay or
durable tuple work.

Two Kafka-order inversions remain valid:

1. An observation accepted before listing manifest M may be appended after M because the connection
   remained in the active set at M's boundary.
2. An observation accepted after M's boundary may be appended before M. Its cycle is greater than
   M, so an omission by M does not cover a connection lifecycle that began after M.

An applicable omitting manifest is different. Connection retirement waits for the terminal
observation and every earlier observation to be acknowledged before active-set removal. The
applicable omission is prepared only after removal, so every valid observation for the connection
precedes chunk 0 of that manifest in the Kafka partition. Any observation for that connection at or
after the first chunk offset is a protocol violation.

This is the complete linearization requirement. The implementation must not claim or require a
stop-the-world ordering across ordinary packets.

### 5.2 Manifest completeness

Every current or still-draining `(writerNodeId, partition)` with an active publisher emits a
complete manifest at `manifestInterval`, with a default operational target of 30 seconds.

Manifest publication is sequenced per partition. A later cycle is not prepared or submitted until
the previous cycle's complete chunk set has been acknowledged. Overlapping periodic callbacks
coalesce into the next run; they cannot submit M+1 before M. A failed or ambiguous manifest closes
the capture gate, so no later cycle is submitted.

The manifest may be chunked. All chunks share one `manifestCycle`, `chunkCount`, `writerNodeId`,
partition, and diagnostic emission time. Chunks are numbered exactly `0..chunkCount-1` and are
submitted through the partition lane in increasing `chunkIndex` order. Unrelated traffic records
may interleave, but the Kafka offsets of this manifest's chunks must increase with `chunkIndex`.

The replayer uses a manifest only after every chunk is present exactly once. A partial,
inconsistent, duplicate-index, or index/offset-reordered manifest is non-authoritative and produces
an alarm.

An empty complete manifest is a positive statement that this writer identity currently has no open
connections on that partition. While the identity remains active, an empty manifest is an ordinary
heartbeat and updates the identity's existing broker-time baseline when timely. It never ends or
resets that baseline.

For a chunked applicable omission, chunk 0's offset—the lowest Kafka offset occupied by the
manifest—is the connection-retirement cutoff. The omission becomes usable only after chunk
`chunkCount-1`—the manifest's highest chunk offset—and every intervening offset have been scanned
and validated. Every valid observation for an omitted connection must have an offset lower than
chunk 0. An observation for that connection interleaved between manifest chunks is therefore a
protocol violation, not pre-completion traffic.

### 5.3 Initial manifest

For the first assignment and each replacement assignment, before accepting the first captured
client connection for a partition permitted by that assignment, the proxy must increment
`assignmentSequence`, create the assignment's `writerNodeId`, and acknowledge an initial complete
manifest for that identity and partition. This establishes that
`(writerNodeId, partition)`'s cycle baseline and first accepted broker-time baseline. It does not
require later packet capture to share a publisher lock with the manifest.

### 5.4 Acknowledged manifest broker time

Kafka must use `LogAppendTime` for the traffic topic. The proxy tracks, for each
`(writerNodeId, partition)`:

```text
lastAcceptedManifestLogAppendTime
```

For a chunked manifest:

```text
manifestLogAppendTime = max(LogAppendTime of every chunk)
```

The initial complete manifest establishes `lastAcceptedManifestLogAppendTime`. That baseline is
continuous for the lifetime of the `(writerNodeId, partition)`. Empty manifests, inactivity, and
later assignments do not reset it.

Let `E` be the configured manifest expiration interval. The proxy and replayer must receive the
same `E` and `S` values for one capture-and-replay run; the managed orchestration requirement is
defined in
[Managed Fleet Capture Recovery](managedFleetCaptureRecovery.md).
Unmanaged deployment configuration must preserve the same agreement.

A later complete manifest updates the value only when:

```text
manifestLogAppendTime - lastAcceptedManifestLogAppendTime < E
```

A failed, incomplete, or late manifest does not update the value. When the difference is greater
than or equal to `E`, the proxy irreversibly enters its compromised state and immediately follows
the process-wide `--capture-failure-policy` in §7. That process never becomes
capture-authoritative again.

This conclusion is enforced at the proxy rather than reconstructed downstream. Manifest
publication is serialized as specified in §5.2: the next manifest is not prepared or submitted
until the current complete manifest is acknowledged and its timestamp is accepted. A late
acknowledged manifest closes the capture gate before any later manifest can be submitted. Tests
must prove this ordering. The replayer does not maintain a separate sticky-lapse state.

### 5.5 Pre-forward liveness check

For every Critical Mutation Traffic request:

1. capture and acknowledge every `TrafficObservation` needed to reconstruct the complete request;
2. obtain the Kafka `LogAppendTime` of the `TrafficRecord` containing the observation that permits
   the request to take effect;
3. immediately before submitting the corresponding source bytes, read the current capture state
   and `lastAcceptedManifestLogAppendTime`;
4. permit source submission only when capture remains authoritative and:

   ```text
   observationLogAppendTime - lastAcceptedManifestLogAppendTime < E
   ```

5. otherwise close the connection and irreversibly enter the compromised state.

The check and source-forwarding decision must share a one-way local capture gate. Once the gate
closes, no thread may newly pass the check. This avoids a simple check-then-transition race inside
the process.

No local check can fence a thread that has already entered an uninterruptible source syscall.
That does not violate the central invariant: the request was completely acknowledged to Kafka
before that syscall was allowed.

### 5.6 Timing relationship

The configured manifest publication interval remains lower than `E`, with margin for scheduling,
Kafka publication, acknowledgement latency, retries, and the fleet's operational skew threshold:

```text
manifestInterval < E
```

`manifestCycle` remains the logical lifecycle boundary. Broker timestamps do not replace it.

---

## 6. Replayer interpretation

### 6.1 Complete requests

A request that the capture parser already recognized as complete and detached from its connection
before an applicable reset is processed normally regardless of later manifests, `NoMoreWrites`, or
proxy health.

Reset and terminal completion apply only to incomplete per-connection HTTP accumulation. They must
never discard an already detached complete request merely because its proxy later became silent.

### 6.2 Manifest-cycle reset and terminal completion

For one captured client connection emitted by writer identity X on partition P:

| Observation | Replayer action |
|---|---|
| normal terminal connection-close record | Finish the connection through the ordinary close path and reject every later `TrafficObservation` for that identity; a listing manifest prepared before active-set removal remains valid but cannot reopen it |
| complete manifest M from X on P lists the connection | Preserve its current per-connection HTTP accumulation |
| complete manifest M omits the connection and an incomplete request or source-response accumulator's earliest contributing observation is stamped M or earlier | Settle only that incomplete accumulator and terminally retire that connection identity; independent target and tuple work remain pending |
| complete manifest M omits the connection but an incomplete accumulator's earliest contributing observation is stamped after M | Do nothing to that accumulator; M predates it |
| scan-ahead reconstructs an applicable omitting M and scans every offset through M's last chunk | Satisfy only the covered incomplete-accumulator WorkClaims; preserve ordinary processing of valid observations below M's first chunk offset |
| observation for the connection at or above the first chunk offset of its applicable omitting manifest | Retain the containing record, emit high-severity diagnostics, alarm, and terminate the replayer process |
| valid `NoMoreWrites` whose Kafka header identifies X, on P | Settle all incomplete state already known for X on P and permanently retire that writer-partition identity |
| traffic or manifest from X on P at a Kafka offset above terminal cutoff K | Retain the containing record, emit high-severity diagnostics, alarm, and terminate the replayer process; do not treat it as a new activation |
| exact duplicate valid `NoMoreWrites` for X on P | Treat idempotently as the same terminal retirement |
| `NoMoreWrites` with a missing or malformed writer header | Warn, treat the record as inert, and do not retire any state |

The terminal connection observation is already a traffic-observation cutoff for the connection.
Complete manifests may still list it while its acknowledged removal is pending, but those listings
do not reopen it. An applicable omission is the later exact-registry retirement fact for the
connection. `connectionId`
is unique within its `writerNodeId` and is not reused by that identity. A greater-cycle observation
after an omission is valid only when the affected incomplete accumulator's earliest contributing
observation was stamped after that manifest. Once an applicable omission retires the connection,
any later observation or listing manifest for that identity is a protocol violation.

Kafka record composition does not weaken this rule. Replay intake splits each mixed-cycle record
into observation children before sealing the parent record obligation. A manifest satisfies only
the incomplete-accumulator WorkClaims it resolves. A transaction claim beneath the same child, or
another child's claims, may remain pending. The parent Kafka offset is proposed for commit only
when every child is terminal `Satisfied`. Applicability is based on the earliest observation in the
specific incomplete accumulator, not the connection-opening observation and not every observation
whose parent Kafka record remains uncommitted.

Only a valid `NoMoreWrites` published by that writer identity's terminal publisher lane permanently
retires `(writerNodeId, partition)`. A hard crash emits no such record.

If scan-ahead discovers self `NoMoreWrites` before normal replay reaches its cutoff K, it may settle
only a blocker whose scan covered every intervening offset through K without finding the required
follow-up. Other accumulations wait until the replay cursor reaches K; then all lower records have
been processed and the remaining incomplete state may settle.

### 6.3 Skew-adjusted broker-time expiration

The proof requires the proxy, replayer, and fleet clock monitor to use the same configured `E` and
`S` values for the run. Let:

- `E` be the agreed manifest expiration interval;
- `S` be the maximum permitted backward movement between Kafka `LogAppendTime` values at increasing
  offsets in one partition;
- `M` be a writer and partition's `lastAcceptedManifestLogAppendTime`; and
- `R` be the `LogAppendTime` of any later record at a higher offset in that partition, including a
  record from a different writer.

The replayer may expire incomplete request and source-response accumulators that it already knows
for that writer and partition when:

```text
R - M >= E + S
```

For any subsequently appended observation from that writer with timestamp `O`, the skew bound
requires `O >= R - S`; therefore `O - M >= E`. The proxy's §5.5 check rejects that observation
before the corresponding Critical Mutation Traffic can reach the source.

Expiration releases only the accumulators known at the qualifying offset. It does not retire the
writer or partition. Legitimate connections opened after a later assignment use that assignment's
new `writerNodeId` and independently established baseline.

A later first observation for a connection that the replayer does not currently know creates fresh
initialized state, exactly as it would after a replayer restart at that cursor. It never joins an
expired accumulator. This rule also applies if a rogue record from an old identity appears. If the
new state remains incomplete without a timely accepted manifest, a later broker-time horizon
expires it. Correctness does not depend on the replayer remembering that the proxy previously
observed a late manifest; §5.4 requires the proxy to stop all later authoritative publication.

The affected Kafka records become commit-eligible only after every independent operation associated
with each record has finished. An already-started target transaction continues, and a
reconstituted request still requires durable tuple output with its source response represented as
complete, expired, or unavailable according to tuple policy.

Consumer wall-clock time is not commit authority. A quiet partition provides no later record and
therefore no expiration horizon. If the declared `S` bound is violated or cannot be attested as
healthy, the replayer stops making these expiration decisions, retains the affected records, and
raises a high-severity alarm.

### 6.4 Long gaps

A connection with no traffic for hours remains represented because complete manifests continue to
list it. No application-idle timeout is inferred from that silence.

### 6.5 Timestamp domains

The protocol intentionally contains independent values with different meanings:

- `manifestCycle` is a per-writer-identity, per-partition logical sequence used only for the
  lifecycle rule in §5.1;
- `TrafficObservation.ts` is source event time for replay pacing;
- manifest `emittedAtMillis`, which remains diagnostic only;
- Kafka `LogAppendTime` is used only for the skew-adjusted proxy and replayer expiration proof in
  §§5.4–6.3; and
- Kafka offsets order records within a partition but do not replace `manifestCycle` when concurrent
  proxy publication reorders a manifest and observation.

No conversion between these domains is part of manifest omission correctness.

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

Manifest staleness, an ambiguous producer outcome, or any other compromise of trustworthy capture
closes the local capture gate permanently for that process.

Capture never resumes within the same compromised process. A new assignment cannot restore
capture authority to that process by creating another `writerNodeId`.

A definite transient failure known not to have compromised capture may use a bounded retry path and
return to capture-authoritative operation after producer reachability and every capture invariant
are revalidated.
Manifest lapse, ambiguous producer outcome, or any other compromise never uses that path; recovery
requires a fresh process and fresh `captureActivationId`.

The process emits a high-severity alarm containing at least:

- `processId`, `captureActivationId`, and every affected `writerNodeId`;
- mode;
- affected partitions;
- last acknowledged manifest submission age from the proxy's local monotonic clock;
- last acknowledged traffic offset when available;
- producer error or stale-gate reason; and
- whether source forwarding continued.

### 7.2 `fail-closed`

After capture is compromised:

- the request currently waiting at the pre-forward check is not sent to the source;
- the process emits high-severity diagnostics; and
- the process terminates immediately with a distinct nonzero capture-failure exit status.

It does not attempt connection retirement, writer retirement, a final empty manifest, or
`NoMoreWrites`. Those operations require trustworthy Kafka acknowledgements. The replayer handles
the termination as a crash and does not fabricate completion.

### 7.3 `fail-open`

After the gate closes:

- Kafka capture submission remains permanently closed;
- existing TCP connections remain open and switch to uncaptured forwarding;
- new TCP connections use uncaptured forwarding;
- the process emits a loud, persistent capture-gap alarm; and
- it does not claim orderly connection or writer retirement for the compromised capture
  activation.

The process must not resume manifests or generate captured traffic under the same or a new
`writerNodeId`, even if Kafka later recovers. Recovery requires retiring the process and starting a
fresh capture-authoritative process.

### 7.4 Process suspension

A process may be suspended after Kafka acknowledgement and before source submission. If it resumes:

- in strict mode, the pre-forward stale-manifest check prevents execution once the threshold is
  exceeded;
- if suspension occurred after the source-forwarding decision, the request was already completely
  captured and remains replayable; and
- in pass-through mode, the capture gap is an accepted and alarmed policy outcome.

This is the meaningful containment boundary without broker transactions or an external controller.

### 7.5 Membership observations

A missing group member, failed health check, or control-plane declaration of node death should
alarm operators and may trigger process replacement. It does not cause replay settlement and does
not cause another proxy to write completion on behalf of the missing proxy activation.

After the first usable assignment, membership polling and callbacks do not control capture or
connection acceptance. The proxy continues using its last usable assignment until Kafka supplies a
replacement. A replacement assignment creates a new writer identity for subsequently opened
connections after its initial manifests are acknowledged. Existing connections remain unchanged.

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

For the current Kubernetes round, the controller marks the failed capture resource terminal and
does not restore capture or authorize a replacement snapshot within that resource. A fresh workflow
may proceed only after the managed-fleet addendum's Kafka-input-isolation, old-workload retirement,
and source-quiescence preconditions are satisfied. If that fresh workflow reuses the same Kafka
topic, its complete session-fenced reset package is required now. Automatic same-resource reset,
regrant, and coverage restoration remain future work.

---

## 9. Implementation deltas

| Area | Required change |
|---|---|
| Group assignor | Distribute partitions for new connections, retain the last usable assignment until a replacement is installed, and publish the assignment metadata needed for consistent routing. |
| Startup | Add out-of-group capability probes using `writerNodeId = captureActivationId + ":PROBE"` before joining the group; probes remain inert to replay. |
| Routing | Keep the last usable assignment until a replacement is initialized; increment `assignmentSequence` and create a new assignment-scoped `writerNodeId` for that replacement; persist each connection's immutable writer identity and partition. |
| Registry | Maintain exact all-open connection sets and one atomic `manifestCycle` per `(writerNodeId, partition)`; retain older registries while their connections drain; remove a closed connection only after its terminal and all earlier observations are acknowledged. |
| Publisher | Stamp every `TrafficObservation` with its current `manifestCycle`; permit mixed-cycle records; compute each complete manifest's timestamp as the maximum chunk `LogAppendTime`; preserve connection-local acknowledgement and terminal self-`NoMoreWrites` ordering without globally serializing packet capture. Keep one producer and serialized lane owner per writer and partition; drain all submissions and callbacks before handoff. |
| Source forwarding | Establish an acknowledged initial manifest baseline; reject late manifests; compare acknowledged observation `LogAppendTime` against `lastAcceptedManifestLogAppendTime`; make compromise irreversible. |
| Failure mode | Make capture abandonment irreversible per process; implement strict exit and permanent pass-through transitions. |
| Record validation | Accept terminal self `NoMoreWrites` idempotently; reject peer completion; retain the poison record, log and metric at least once, alarm, and terminate the replayer process on protocol violations. |
| Replayer | Apply omission only to the specific incomplete request or source-response accumulator whose earliest contributing observation is covered. Use the `E + S` broker-time proof to expire only already-known accumulators; treat a future first observation as fresh initialized state. |
| Observability | Emit manifest-cycle reset, accepted manifest `LogAppendTime`, skew health, broker-time expiration, capture-gate, retained-incomplete-state, and capture-gap metrics. |

### 9.1 Required status

Each proxy should expose:

- process identity, `captureActivationId`, current `assignmentSequence`, current
  `writerNodeId`, and every older writer identity still draining;
- whether the first usable assignment has been received, the startup member-count threshold, the
  last usable assignment, and any replacement assignment being initialized;
- capture mode and whether the capture gate is open;
- current connections by `(writerNodeId, partition)`;
- pending connection retirements and the oldest unacknowledged terminal-or-earlier observation;
- `lastAcceptedManifestLogAppendTime` and the latest rejected late-manifest time by
  `(writerNodeId, partition)`;
- oldest unacknowledged publisher work;
- whether the process has permanently abandoned capture; and
- capture-gap alarm state.

The replayer should expose:

- incomplete per-connection HTTP accumulations by writer identity and partition;
- latest complete `manifestCycle` and the Kafka offsets containing its chunks;
- accepted manifest broker time, `E`, `S`, and broker-time expiration counts;
- whether the fleet currently attests the clock-skew bound;
- terminal-disposition counts by normal close, manifest-cycle reset, and terminal self
  `NoMoreWrites`;
- unresolved parent-record, observation/control-child, and WorkClaim counts;
- post-applicable-omission protocol violations;
- retained state for which no sound terminal disposition exists;
- invalid peer-completion records; and
- the oldest commit-blocking state.

---

## 10. Integration issues to resolve during implementation

### 10.1 Manifest-cycle schema and atomicity

Before implementation is declared complete:

- every `TrafficObservation` carries `manifestCycle`;
- every manifest chunk carries that same field and no competing `manifestId` or
  `manifestSequence` is used for lifecycle semantics;
- `manifestCycle` is scoped to `(writerNodeId, partition)` and never reused by that writer
  identity;
- connection add, connection remove, and manifest copy-and-increment use the same partition-local
  lifecycle boundary;
- ordinary packet capture only reads the counter and is not serialized by that boundary;
- Netty's terminal connection observation is the final contiguous
  `connectionObservationSequence` value for the connection;
- any later `TrafficObservation` for that connection is rejected even when its sequence is
  contiguous; a listing manifest prepared before active-set removal remains valid but cannot reopen
  the connection;
- the connection remains in the exact active set until Kafka acknowledges that terminal observation
  and every earlier observation for it;
- a `TrafficRecord` may contain observations from multiple manifest cycles, and replay intake
  creates complete, disjoint observation children plus attributable WorkClaims before the parent
  record obligation is sealed;
- manifest completeness is validated before omission has any effect; and
- tests cover both valid Kafka-order inversions from §5.1 and reject any observation at or above the
  first chunk offset of an applicable omitting manifest.

An implementation may use a stronger local serialization mechanism if measurement justifies it,
but correctness claims and interfaces must depend only on the narrow boundary above.

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

### 10.4 Existing expiration code

The existing `--packet-timeout-seconds` path does not implement §6.3 merely because it waits for a
duration. Replayer wall-clock time remains non-authoritative.

The production path must instead track the accepted manifest broker-time baseline, derive complete
manifest time from the maximum chunk `LogAppendTime`, observe a later higher-offset partition
record, apply the `E + S` inequality, and verify that the fleet's skew-bound attestation remains
healthy. It expires only already-known incomplete accumulators. It does not retire the writer or
partition, and a future first observation initializes fresh state.

### 10.5 Proxy-completion schema

The `NoMoreWrites` protobuf body contains the partition only. The Kafka record header supplies the
authoritative `writerNodeId`. A missing or malformed writer header makes the record inert and cannot
settle state.

### 10.6 Managed-fleet addendum

The managed-fleet addendum is realigned with this protocol. Its current-round Kubernetes rule is
terminal: after bounded retry reaches a capture failure, the resource remains incomplete and the
workflow starts over. Every managed fresh run already requires a controller-issued session, trusted
replay-boundary plan, and session-aware checkpoints. The addendum documents future automatic
orchestration around:

- terminal self-only `NoMoreWrites`;
- exact manifest-cycle resets and explicit retention when neither reset nor self completion exists;
- the irreversible local capture gate;
- explicit new-run recovery after a capture gap;
- those session identities fencing delayed old records;
- separate old capture-activation retirement and Kubernetes traffic retirement; and
- immutable pre-grant replay start offsets—reset-derived for a same-topic run or sampled after setup
  for a distinct-topic run—that remain conservative across the new source snapshot.

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
- The initial complete manifest establishes `lastAcceptedManifestLogAppendTime` before a source
  connection is accepted.
- A chunked manifest uses the maximum chunk `LogAppendTime` as its manifest time.
- A later manifest whose broker-time difference is greater than or equal to `E` does not update the
  baseline and irreversibly compromises the proxy.
- An acknowledged observation whose difference from the accepted manifest baseline is greater than
  or equal to `E` is not forwarded to the source.
- Closing the capture gate races safely with many source-forwarding threads.
- A source-forwarding operation allowed before gate closure has a complete Kafka representation.
- Failed or partial manifest publication does not refresh proxy-local acknowledged-manifest
  freshness.
- Publisher initialization failure closes its producer and executor; repeated failed starts leave
  no publisher threads behind.
- A connection added before manifest M's lifecycle boundary and still registered at that boundary
  is listed by M.
- Registry addition precedes acceptance of the connection's first `TrafficObservation`.
- A connection added after M's boundary stamps observations with a cycle greater than M.
- Netty close caused by remote closure, local closure, or channel failure produces exactly one
  terminal connection observation after every earlier observation in the connection's event-loop submission
  chain.
- The kernel and Netty produce no later network traffic for the connection after Netty reports
  closure.
- Netty's terminal observation immediately becomes the replayer's traffic-observation cutoff for
  that connection;
  a later contiguous sequence value is retained, alarmed, and terminates the replayer process.
- The connection remains in the active set until Kafka acknowledges its terminal observation and every earlier
  `TrafficObservation`.
- A manifest prepared while those acknowledgements are pending lists the connection; a manifest
  prepared after acknowledged connection retirement may omit it.
- A listing manifest consumed after the terminal observation is valid when prepared before removal,
  but it does not reopen the connection.
- An open observation accepted before M but appended after M continues normally when M lists the
  connection.
- An open observation accepted after M but appended before M is protected from M's omission by its
  greater `manifestCycle`.
- An omission by M does not expire an incomplete accumulator whose earliest contributing
  `TrafficObservation` is stamped M+1.
- An applicable omission retires the connection identity, and any observation at or above that manifest's first chunk
  offset is retained, alarmed, and terminates the replayer process, including an observation
  between chunks.
- Mixed-cycle Kafka records are split into observation children. A manifest may satisfy a covered
  incomplete-accumulator WorkClaim while a transaction claim beneath the same child remains
  pending, and the parent offset commits only after every child is terminal `Satisfied`.
- Flushing a traffic batch at a manifest-cycle boundary and not flushing it preserve the same
  per-observation semantics and WorkClaim decisions without loss or duplication. Parent-record
  identity, commit timing, and redelivery granularity may differ; flushing is only a batching
  optimization.
- The connection-local submission chain preserves same-connection Kafka order, and
  `connectionObservationSequence` detects a gap, regression, or conflicting duplicate rather than
  asking the replayer to reorder records.
- `manifestCycle` is nondecreasing as `connectionObservationSequence` increases; a later sequence
  carrying a lower cycle closes capture or causes the replayer to retain the record and terminate.
- Repeated periodic callbacks never submit manifest M+1 before M is completely acknowledged.
- Ordinary packets continue concurrently while add, remove, and manifest copy-and-increment are
  repeatedly raced.
- Removing a closed connection from the active set is impossible until Kafka acknowledges its
  terminal observation and every earlier `TrafficObservation`.
- Terminal self `NoMoreWrites` closes the new-connection gate first, fully retires all related
  Netty connections, verifies the registry is empty, enters `RETIRING`, quiesces periodic
  manifests, waits for remaining accepted sends, and then acknowledges the final empty manifest
  followed by `NoMoreWrites`.
- A periodic manifest callback racing permanent `(writerNodeId, partition)` retirement either entered the lane
  before `RETIRING` and completes or exits without submission; it never publishes after terminal
  `NoMoreWrites`.
- Producer configuration cannot disable idempotence, weaken `acks=all`, or set
  `max.in.flight.requests.per.connection` above the ordering-preserving limit.
- Startup fails closed when the effective producer settings cannot preserve same-partition lane
  order across retries.
- Multiple producers are allowed only with one non-overlapping producer and serialized lane owner
  per writer and partition, and handoff waits for every previous submission and callback.
- Pass-through never resumes capture.
- Strict mode stops new source execution and exits.

### 11.2 Manifest and replayer tests

- Complete manifests list active and idle connections.
- Chunk loss, conflicting metadata, duplicate indexes, or chunk offsets that do not increase with
  `chunkIndex` make a manifest non-authoritative.
- A listing manifest preserves an idle connection's current per-connection HTTP accumulation.
- Manifest M omitting a connection settles only an incomplete request or source-response
  accumulator whose earliest contributing observation is stamped M or earlier, then terminally
  retires that connection identity.
- Manifest M has no effect on an incomplete accumulator whose earliest contributing observation is
  stamped M+1; the connection-opening observation and unrelated uncommitted records do not decide
  applicability.
- Any observation at or above the first chunk offset of an applicable omitting manifest is retained
  and terminates the replayer after high-severity diagnostics and an alarm, including an
  observation interleaved between chunks.
- A `TrafficObservation` after the connection's terminal observation is also a protocol violation;
  a later listing manifest prepared before active-set removal remains valid but cannot reopen it.
- One Kafka record containing observations from multiple manifest cycles creates exactly one child
  obligation per observation. An incomplete-accumulator WorkClaim may become `Satisfied` while a
  transaction claim beneath the same child remains pending, and the parent offset remains
  uncommitted until every child is terminal `Satisfied`.
- Terminal self `NoMoreWrites` settles prior incomplete state and retires the
  `(writerNodeId, partition)`.
- Scan-ahead discovery of self `NoMoreWrites` records its Kafka cutoff; traffic below the cutoff
  remains valid. Every traffic or manifest record above it violates the protocol; only duplicate
  self `NoMoreWrites` is idempotent.
- Later traffic or manifests after terminal self `NoMoreWrites` are retained, alarmed, and
  terminate the replayer process; duplicate self completion is idempotent.
- A `NoMoreWrites` record with a missing or malformed writer header is inert and does not settle
  state.
- A later record from any writer on the partition may establish `R - M >= E + S` and expire only
  the silent writer's already-known incomplete accumulators.
- A later assignment may create fresh initialized connection state on the same partition under its
  new `writerNodeId`; expiration never joins that state to an older expired accumulator.
- A later first observation for an unknown connection under an old writer identity also starts
  isolated state and may expire independently. The proxy invariant prevents a compromised process
  from publishing a later manifest that restores freshness; the replayer adds no sticky-lapse
  state.
- A violated or unattested `S` bound disables broker-time expiration and retains the affected
  records.
- Cycle-boundary batch flushing is optional. It does not change per-observation semantics or
  WorkClaim decisions, but it may change parent-record boundaries, commit timing, and redelivery
  granularity.
- `manifestCycle`, source event time, proxy monotonic time, Kafka offset, and Kafka timestamp remain
  separate domains.
- A complete request is replayed even when an applicable later manifest retires its connection.
- Every terminal path settles its process-local accounting exactly once; only contiguous
  `Commit` dispositions advance Kafka's commit watermark.

### 11.3 Membership tests

- A process completes leader-broker probes before joining.
- Every capability probe uses `writerNodeId = captureActivationId + ":PROBE"` and creates no writer baseline,
  manifest state, or connection state in the replayer.
- A process joins the group only after its capability probes are acknowledged.
- Scale-up moves eligibility to accept new captured client connections in one cooperative
  assignment while the existing proxy
  connection set drains in place.
- Every new assignment increments `assignmentSequence` and creates a new `writerNodeId` for new
  connections.
- Existing connections keep their original writer identities across rebalance.
- Every new assignment identity acknowledges an initial complete manifest before accepting a
  connection on a permitted partition.
- An older writer identity emits periodic manifests until its connections drain, then emits a final
  empty manifest and self `NoMoreWrites`.
- Rapid rebalances may leave several writer identities draining concurrently without sharing
  registries, cycles, publisher lanes, or broker-time baselines.
- An empty manifest for an active identity is an ordinary heartbeat and never resets its baseline.
- `minimumActiveProxyCount` is enforced only before the first usable assignment.
- Revocation, partition loss, empty polls, coordinator outage, and failed membership polling leave
  the last usable assignment available for new connections until a replacement assignment is
  installed.
- A stale proxy and its replacement may temporarily assign new connections to the same partition;
  their distinct writer identities keep capture unambiguous.
- Mixed incompatible group protocols are rejected as an in-place rollout.

### 11.4 Failure tests

- Kill a proxy with open idle and active connections and no later omission or self completion;
  verify the incomplete Kafka obligation remains retained and alarms.
- Suspend a proxy beyond the stale threshold and resume it; verify strict source execution is
  blocked after complete Kafka capture.
  blocked after complete Kafka capture.
- Suspend it after source forwarding was allowed; verify the request was already completely
  captured.
- Fail Kafka during manifest publication in strict and pass-through modes.
- Create an ambiguous producer send and verify the capture gate closes.
- In `fail-closed`, verify that Kafka or capture compromise terminates immediately without orderly
  retirement.
- In `fail-open`, verify that existing and new TCP connections forward without capture and that
  Kafka recovery does not resume capture.
- Verify that orderly trustworthy shutdown emits a high-severity diagnostic after its five-minute
  target, continues trying to publish the final empty manifest and `NoMoreWrites`, and gives up only
  when the most recently acknowledged complete manifest reaches `E`. Capture-compromise and
  unstable-process failures do not enter retirement.

### 11.5 Acceptance criteria

The design is complete when:

1. every source-completable mutating request is completely acknowledged to Kafka first;
2. exact manifests preserve open idle connections;
3. manifest-cycle reset and terminal self completion release only the state they are authorized to
   settle;
4. no peer observation authorizes replay settlement;
5. later use of a partition occurs under the new assignment's writer identity, while an old
   identity never resumes after its final empty manifest and terminal `NoMoreWrites`;
6. strict suspension recovery cannot create an uncaptured completed request;
7. pass-through creates a loud, explicit capture gap and never resumes capture;
8. manifest cycle, Kafka offset, source event time, and Kafka `LogAppendTime` retain their distinct
   meanings; and
9. every accepted observation belongs to exactly one child obligation, every semantic dependency
   has one attributable WorkClaim, partial claim or child settlement never advances the parent
   Kafka offset, and all terminal paths settle their local obligations through the normal accounting
   path, while
   retained offsets continue to block Kafka commit progress.

---

## 12. Decisions

- Kafka membership only load-balances new connections. Before the first assignment it is a startup
  prerequisite; afterward the proxy retains its last usable assignment through membership loss,
  revocation, and polling failure until a replacement assignment is installed.
- Manifests list all open connections and close a precisely defined `manifestCycle`.
- Only connection add, connection remove, and manifest copy-and-increment are linearized; ordinary
  packet capture remains concurrent.
- Kafka append order may differ from proxy lifecycle order, so observations carry
  `manifestCycle`.
- A complete manifest omission applies separately to each incomplete request or source-response
  accumulator when its earliest contributing observation is no later than that manifest cycle. It
  settles only that accumulator and does not complete independent target or tuple work.
- A greater-cycle observation may validly survive an earlier omission only when the connection
  first became active after that earlier manifest boundary; it is not a resurrection after
  applicable retirement.
- Mixed-cycle records use observation children and attributable WorkClaims; parent Kafka commit
  waits for every child to become terminal `Satisfied`.
- The initial complete manifest establishes `lastAcceptedManifestLogAppendTime`; later complete
  manifests use the maximum chunk `LogAppendTime` and update the baseline only while their
  difference is less than `E`.
- Capture-before-forward is the primary completeness guarantee.
- Capture-before-forward in this round assumes non-streaming source execution; chunked wire framing
  is allowed only when the source waits for the complete request.
- Hardening mutating-request classification beyond the existing HTTP-method predicate is deferred.
- The proxy compares an acknowledged observation's `LogAppendTime` with the accepted manifest
  baseline after Kafka acknowledgement and before source execution.
- `NoMoreWrites` is self-emitted, partition-scoped, ordered, and terminal for that
  `(writerNodeId, partition)`.
- Kafka producer idempotence and ordering-preserving retry settings are mandatory and cannot be
  weakened by deployment configuration.
- Every new assignment increments `assignmentSequence` and creates an assignment-scoped
  `writerNodeId`; existing connections retain their original identity, and new connections use the
  last usable assignment's identity until the replacement becomes usable.
- Empty manifests remain ordinary heartbeats for an active identity and never reset its continuous
  broker-time baseline.
- After an old writer identity's connections drain, a final empty manifest and self
  `NoMoreWrites` permanently retire that identity and partition.
- A later record from any writer may establish the skew-adjusted `E + S` broker-time horizon that
  expires only already-known incomplete state for a silent writer. A quiet partition has no such
  evidence and remains retained.
- Expiration does not retire a writer or partition. A future first observation starts fresh
  initialized state, but only a new assignment-scoped writer identity may legitimately accept a
  new connection after rebalance.
- Long inactivity is safe because manifests continue to list the connection.
- `--capture-failure-policy=fail-closed` terminates immediately after capture compromise and does
  not attempt orderly retirement.
- `--capture-failure-policy=fail-open` switches the whole process, including existing and new TCP
  connections, to uncaptured forwarding, alarms loudly, and never resumes capture.
- Peer departure only causes group rebalance; it has no replay meaning.
- The maximum whole-connection lifetime defaults to 60 minutes.
- Orderly shutdown while capture and Kafka remain trustworthy uses a five-minute default retirement
  bound; capture-compromise and unstable-process failures do not use that retirement path.
- Recovery after a capture gap starts a new capture and replay run.
- In the current managed Kubernetes round, a terminal capture failure terminally fails the whole
  capture workflow; same-resource reset, regrant, and replacement-snapshot automation are deferred.
- Replayer wall-clock time is not commit authority. Kafka `LogAppendTime` is authoritative only
  through the `E + S` proof while the managed fleet attests the `S` bound.
