# Capture and Replay Architecture

**Status:** top-level design contract

This document defines the observable protocol shared by the capture proxy, Kafka, and the traffic
replayer. It states what each component guarantees, which failures are tolerated, when the replayer
may commit a Kafka record, and why the protocol is safe under its stated assumptions.

This document intentionally does not define implementation classes, thread names, callback graphs,
or migration history. Companion documents provide the lower-level proxy algorithms, replayer
behavior, replayer asynchronous-work accounting, and managed-fleet orchestration.

Capture and replay provides representative source traffic for target validation, load, and mutation
replay. It does not promise that two distributed clusters will produce identical internal execution
or responses for concurrent traffic. The protocol minimizes avoidable differences by preserving
captured request bytes, source-connection ordering, and replay timing where those requirements do
not conflict.

## 1. Scope and unresolved managed-fleet work

The protocol in this document is complete for:

- routing newly opened source connections across a horizontally scaled proxy fleet;
- capturing source traffic into Kafka;
- preserving the connection-local order needed to reconstruct HTTP;
- detecting clean connection and proxy completion;
- handling proxy scale changes, shutdown, hard failure, and long inactivity;
- reconstructing requests and source responses;
- replaying requests against a target;
- requiring durable result-tuple output before committing Kafka records for a reconstituted
  request;
- committing or retaining whole Kafka records; and
- stopping safely during replayer rebalance, shutdown, or internal process failure.

Managed-fleet recovery after an interval of uncaptured source traffic is not fully designed. The
settled requirements are:

1. Missing source traffic cannot be reconstructed later.
2. A replay run must never silently cross an uncaptured interval.
3. A new run after an uncaptured interval requires a fresh source snapshot.
4. The new run requires a durable Kafka input boundary fixed before proxies may publish captured
   traffic for that run.
5. Before the fresh snapshot is accepted, old proxy processes and requests they previously
   forwarded must be proven unable to complete additional mutations against the source service.

Same-topic recovery, the proposed `FleetCaptureReset` record, the proposed
`CaptureCoverageEstablished` record, automatic proof that old source operations have stopped, and
same-resource recovery remain unresolved. The managed-fleet companion document may describe
possible mechanisms, but no implementation may depend on them as settled protocol or contradict
the requirements above.

## 2. System model

```mermaid
flowchart LR
    Client["Source client"]
    Proxy["Capture proxy"]
    Source["Source service"]
    Kafka["Kafka traffic topic"]
    Replayer["Traffic replayer"]
    Target["Target service"]
    Tuples["Durable result tuples"]

    Client -->|"HTTP connection"| Proxy
    Proxy -->|"Forwarded source traffic"| Source
    Proxy -->|"TrafficRecord, LivenessSnapshotChunk,<br/>NoMoreWrites"| Kafka
    Kafka -->|"Partition records"| Replayer
    Replayer -->|"Reconstituted HTTP request"| Target
    Replayer -->|"Source and target results"| Tuples
```

The **source** is the service receiving traffic during capture. The **target** is the service
receiving reconstructed requests during replay.

The proxy sits on the source traffic path. When source clients use TLS, the proxy terminates TLS and
captures the resulting decrypted HTTP traffic before forwarding it to the source. It publishes the
captured representation to Kafka.

The replayer reads Kafka partitions, reconstructs HTTP requests and source responses, sends each
reconstituted request to the target, and writes a durable tuple describing the source and target
results.

Kafka provides ordered records within each partition and assigns partitions to replayer group
members. Kafka does not provide ordering across partitions.

### 2.1 Identities

`captureActivationId` identifies one capture-authoritative lifetime of a proxy process.
`assignmentSequence` is a process-local, strictly increasing value. The proxy increments it when
Kafka supplies a replacement group assignment, before accepting any connection under that
assignment. The writer identity used for newly opened connections is:

```text
writerNodeId = captureActivationId + ":" + assignmentSequence
```

Every new assignment therefore creates a writer identity that cannot be reused within the
`captureActivationId`. Existing connections permanently retain the `writerNodeId` under which they
opened. Rapid rebalances may leave several older writer identities draining concurrently.

The out-of-group Kafka reachability probe runs before a consumer-group generation exists. Its
writer field is:

```text
writerNodeId = captureActivationId + ":PROBE"
```

`CaptureCapabilityProbe` confirms only that the proxy can publish to Kafka. It has no replay
meaning. If the replayer encounters one, it allows the Kafka record to become commit-eligible
without creating traffic, manifest, connection, writer-baseline, or expiration state.

`connectionId` needs to be unique only within one `writerNodeId` and must not be reused by that
writer. The complete connection identity is:

```text
(writerNodeId, connectionId)
```

Every protocol decision about a connection, including manifest inclusion, omission, expiration,
terminal validation, and metrics, uses the complete identity. A bare `connectionId` is never enough
to identify a connection across proxy writers.

Every source connection is assigned one Kafka partition when it opens. That mapping never changes
for the life of the connection:

```text
(writerNodeId, connectionId) -> Kafka partition
```

A proxy may publish connections to a partition, later drain all of them, and receive new-connection
eligibility for that partition in a future assignment. The future connections use the future
assignment's `writerNodeId`; the earlier identity never resumes. After `NoMoreWrites` retires a
`(writerNodeId, partition)`, that writer identity may never use that partition again.

Every exact connection registry, manifest cycle, publisher lane, broker-time baseline, and
`NoMoreWrites` lifecycle is keyed by `(writerNodeId, partition)`, not by one process-global current
writer identity.

### 2.2 Stable protocol records

The protocol uses these protobuf message names:

- `TrafficRecord` contains one or more `TrafficObservation` values for one connection.
- `TrafficObservation` records captured network or connection-lifecycle activity. Each observation
  carries the manifest cycle and connection-local sequence needed by this protocol.
- `LivenessSnapshotChunk` carries one chunk of an exact open-connection manifest for one
  `writerNodeId` and Kafka partition.
- `NoMoreWrites` permanently states that one `writerNodeId` will publish no more records to one
  Kafka partition. Its Kafka record carries `writerNodeId` in a required header rather than as a
  separately claimed writer value inside the protobuf body.

The component documents define their exact fields and wire-compatibility plan. They may not change
the observable meanings defined here.

### 2.3 Imported capture archives

Bring-your-own captured traffic is supported only when the archive was produced by the same capture
protocol version that the replayer accepts. The archive is a versioned representation of Kafka
application records, not a file containing protobuf payloads alone.

[`BringYourOwnCapturedTraffic.md`](BringYourOwnCapturedTraffic.md) defines the archive and
orchestration details.

For every archived record, the exporter preserves:

- the source partition and offset;
- the original Kafka timestamp and timestamp type;
- the binary key and value, including null values;
- every Kafka header, preserving duplicate names and order; and
- the record's position within its source partition.

The archive also preserves the protocol/build version, source partition count, fixed exported
offset range for every partition, capture parameters `E` and `S`, and integrity information that
detects omitted, duplicated, reordered, or corrupted records. Export reads exactly the recorded
partition ranges. It does not use a timeout as an implicit successful end boundary.

Import creates one Kafka application record for every archived record, writes it to the archived
partition, preserves partition-local order, and restores its binary key, value, and headers.
Imported offsets and broker leader epochs are newly assigned Kafka transport identities; the
replayer uses the imported offsets for commit accounting. The original offsets remain archive
integrity and diagnostic data and do not participate in imported-topic commits.

The timestamp policy has two modes:

- **`preserve`**, the default, imports each archived original `LogAppendTime` as the record timestamp
  on a dedicated bring-your-own topic configured to preserve producer-supplied timestamps. The user
  asserts that the original broker clock-skew bound `S` was healthy. The replayer may apply the
  ordinary `E + S` expiration proof using the archived timestamp and archived `E` and `S`.
- **`rebase-without-expiration`**, an expert mode, accepts newly assigned import-broker timestamps
  and automatically disables broker-time expiration for that input. Applicable manifests, terminal
  connection observations, and valid `NoMoreWrites` records still resolve incomplete state.

No mode may use newly assigned import-broker time to perform the original capture run's `E + S`
expiration proof. An archive ending at an open writer or incomplete connection is not completion
evidence merely because the file ended.

## 3. Proxy group membership and connection routing

Kafka consumer-group membership is used only to load-balance newly opened source connections across
Kafka partitions. Membership state is not capture-health evidence and has no replay meaning.

Before a proxy has received its first usable assignment, it has no partition on which it can publish
a new connection. The proxy completes its Kafka capability probes before joining the group. It may
begin accepting source connections only after:

- it has received its first assignment;
- the initial startup group size satisfies `minimumActiveProxyCount`, including the local proxy;
- it has proved that it can publish acknowledged records to Kafka; and
- the initial exact manifest required by §5 has been acknowledged for every partition it may use.

If startup fails before any assignment is received, or a source connection arrives while no first
usable assignment exists, the proxy follows the process-wide `--capture-failure-policy` defined in
§12.4. It does not later resume capture in that process after applying either terminal policy.

After the first usable assignment is installed, the proxy retains it until Kafka supplies a
replacement assignment. Revocation, partition-loss callbacks, failed or stale membership polling,
empty polls, delayed heartbeats, coordinator outages, and group departure do not close a capture or
connection-acceptance gate. The proxy continues assigning new connections with its last usable
assignment. Kafka may temporarily assign the same partition to another proxy after considering the
old member gone. That overlap affects load distribution only: each proxy uses a distinct
`writerNodeId`, so captured records remain unambiguous.

When Kafka supplies a replacement assignment, the proxy:

1. increments `assignmentSequence` and creates the replacement assignment's `writerNodeId`;
2. publishes and receives acknowledgement for that identity's initial complete manifests on every
   partition it may use;
3. begins assigning new source connections with the replacement assignment only after those
   acknowledgements; and
4. continues manifests and connection retirement independently for every older writer identity
   that still has connections.

Connections accepted before the replacement retain their original proxy, writer identity, and
Kafka partition. There is no membership-health deadline and no application retry or failure policy
driven by membership after startup.

The initial manifest establishes the new assignment-scoped writer identity's broker-time baseline
before that identity accepts a source connection. An empty manifest for a current identity is an
ordinary heartbeat and updates that same continuous baseline when timely; it never resets the
baseline.

After an older identity's connections on a partition drain, it publishes a final empty manifest
and self `NoMoreWrites`, permanently retiring that identity and partition.

There is no peer witness protocol, peer completion declaration, partition-footprint advertisement,
or group-stability timer in this design.

## 4. Capture-before-forward

For every request classified as Critical Mutation Traffic:

```text
If the source can apply the request,
then Kafka has already acknowledged every TrafficObservation
needed to reconstruct that complete request.
```

The converse is intentionally false. Kafka may contain a complete request that the proxy ultimately
does not send to the source.

Kafka must assign record timestamps using `LogAppendTime`. For each `(writerNodeId, partition)`, the
proxy tracks `lastAcceptedManifestLogAppendTime`, initially established by the acknowledged initial
complete manifest required by §3. For a chunked manifest, its `manifestLogAppendTime` is the maximum
Kafka `LogAppendTime` across all of its chunks.

The baseline is continuous for the lifetime of that writer identity and partition. Neither an empty
manifest, inactivity, nor a later assignment resets it.

Let `E` be the configured proxy manifest expiration interval. The proxy and replayer must receive
the same `E` and `S` values for one capture-and-replay run; `S` is defined in §8. A managed
orchestration layer supplies the agreed values to all participating processes. In an unmanaged
deployment, maintaining that agreement is an operator requirement. The timestamp proof in §8 is
invalid when the processes are configured with different values.

A subsequent complete manifest
updates `lastAcceptedManifestLogAppendTime` only when:

```text
manifestLogAppendTime - lastAcceptedManifestLogAppendTime < E
```

If the difference is greater than or equal to `E`, the manifest is late. The proxy does not update
the baseline and irreversibly enters its compromised state. Manifest publication is serialized:
the proxy does not prepare or submit a later manifest until the current complete manifest is
acknowledged and accepted. Therefore a proxy that observes a late manifest cannot publish a later
manifest that appears to restore freshness. This is a proxy invariant; the replayer does not add a
separate sticky-lapse state.

For every Critical Mutation Traffic request, the proxy enforces the guarantee in this order:

1. Capture the request observations.
2. Publish every observation required to reconstruct the complete request.
3. Wait for Kafka acknowledgement.
4. Read the Kafka `LogAppendTime` of the `TrafficRecord` containing the observation that permits the
   request to take effect.
5. Compare that time with `lastAcceptedManifestLogAppendTime`.
6. Continue only when the difference is less than `E`.
7. Only then submit the source traffic that permits the request to take effect.
8. Otherwise, close the connection and irreversibly enter the compromised state.

The comparison and forwarding decision share a one-way local state transition. Once the proxy is
compromised, no request may newly pass this check and forward Critical Mutation Traffic as captured.
The proxy immediately applies `--capture-failure-policy`: `fail-closed` terminates without orderly
retirement, while `fail-open` switches existing and new TCP connections to uncaptured forwarding.
Neither policy permits capture-authoritative operation to resume in that process.

This proof assumes that the source cannot mutate state before receiving the complete request.
Deployments whose source handlers apply mutations while an incomplete request body is still
arriving require full request buffering or another separately reviewed protocol.

The broker timestamp is not a manifest-cycle value and is not used to order HTTP observations.
`manifestCycle` still resolves proxy lifecycle races; Kafka `LogAppendTime` supplies only the
broker-time expiration proof in §8.

### 4.1 Denial-of-service bounds for incomplete requests

The proxy places hard bounds on incomplete requests so that a client cannot retain unbounded proxy
or replayer state by sending one byte at a time.

At minimum, the proxy enforces:

- an absolute maximum request-assembly duration measured from the first request byte and never reset
  by later progress; and
- bounded request and header bytes while the request remains incomplete.

The proxy also enforces a separately configurable maximum lifetime for the client connection,
defaulting to 60 minutes. This limit bounds how long one connection can delay a planned proxy
connection-set drain even when its requests individually remain within the request-assembly limits.
A deployment may explicitly configure a different lifetime when required.

The two time limits are independent:

- the connection-lifetime timer starts when Netty accepts the connection and closes the connection
  when that lifetime expires; and
- the request-assembly timer starts with the first byte of each request and protects incomplete
  HTTP assembly.

When either bound is exceeded, the proxy stops forwarding that request, closes the affected
connection, and publishes the terminal connection `TrafficObservation` after all earlier
observations for the connection. An incomplete Critical Mutation Traffic request never receives the
source traffic that would permit it to take effect.

These are proxy denial-of-service limits. They are distinct from replayer expiration after missed
connection manifests.

## 5. Exact connection manifests

For every `(writerNodeId, partition)`, the proxy maintains:

- the exact set of open source connections using that partition; and
- a monotonically increasing `manifestCycle`.

The proxy uses one short partition-local ordering boundary for only three operations:

1. adding a newly opened connection to the exact set;
2. removing a closed connection after the acknowledgement requirements in §6; and
3. copying the exact set and advancing `manifestCycle` to prepare a manifest.

Packet processing remains globally concurrent across connections and does not take this boundary.
Within one connection, however, the proxy assigns a contiguous
`connectionObservationSequence` and submits `TrafficObservation` values to Kafka in that order.
Kafka preserves that connection-local order across its records. The replayer processes partition
records in Kafka order and never sorts or reorders records to reconstruct a request or response.

Every `TrafficObservation` reads the current `manifestCycle` when the proxy accepts that observation
for publication. Manifest preparation copies the exact connection set for the current cycle and
then advances the counter. The complete set is published as one or more
`LivenessSnapshotChunk` records.

Kafka acknowledgement means that all in-sync replicas required by the topic's durability policy
have accepted the record. The producer must preserve submission order across retries and prevent a
retry from creating a conflicting duplicate. These are protocol requirements, not optional tuning.

The proxy does not prepare the next manifest cycle until every chunk of the current cycle has been
acknowledged. Chunks are submitted in increasing `chunkIndex`, so chunk 0 has the lowest Kafka
offset occupied by that manifest and offsets increase with chunk index. Records for unrelated
connections may appear between chunks.

All chunks for one manifest carry the same:

- `writerNodeId`;
- partition;
- `manifestCycle`;
- chunk count; and
- diagnostic emission time.

Kafka independently assigns each chunk a `LogAppendTime`. The manifest's
`manifestLogAppendTime` is the maximum of those values. The proxy and replayer use that same
deterministic value for the broker-time rules in §§4 and 8.

Chunks have unique consecutive indexes. A manifest has no effect until the replayer has obtained
and validated the complete chunk set.

Each syntactically valid chunk record becomes eligible for commit after the replayer reads it and
stores it in the process-local partial set. A partial set has no protocol meaning. If the replayer
restarts before the complete set arrives, the already committed chunks count as though they never
happened. Chunks from that abandoned cycle cannot combine with a different cycle; a later complete
manifest is interpreted independently. When a newly read chunk completes the set, applying the
complete manifest is part of processing that chunk before it becomes eligible for commit.

A merely incomplete set is not a protocol violation. Conflicting content, a duplicate index with
different content, an impossible index, or Kafka offsets that do not increase with `chunkIndex` are
protocol violations.

An empty complete manifest is an exact statement that the proxy's open-connection set for that
partition was empty at that manifest boundary.

### 5.1 Producer ownership

The current implementation may use one Kafka producer and one serialized publisher lane for all
partitions. Scaling that publisher is safe only when, for each `(writerNodeId, partition)`:

- exactly one producer and serialized publisher-lane owner publishes its manifests and
  observations;
- ownership never overlaps; and
- a handoff waits for every submission and callback from the previous owner to finish before the
  next owner begins.

Different proxy writers may concurrently publish existing connections to the same Kafka partition.
The single-owner requirement is within one writer and partition.

This preserves one submission order for that writer's connection observations, manifest chunks,
and `NoMoreWrites`. Non-overlapping ownership and a fully drained handoff prevent a previous
producer from publishing a late record after its successor begins.

### 5.2 Why manifest cycles are necessary

Proxy event loops and the manifest publisher run concurrently. Kafka append order may therefore
differ from the order in which the proxy accepted an observation or copied the connection set.
Kafka offsets alone cannot decide whether a manifest applies to an observation.

Consider manifest cycle 42 and connection identity `(writer-7, connection-9)`:

- If the connection was already in the exact set when cycle 42 was copied, the cycle-42 manifest
  lists it. Its observations may appear before or after the manifest records in Kafka.
- If the connection opened only after the cycle-42 copy, its first observation carries cycle 43.
  The cycle-42 manifest may omit it, but that omission does not apply to a connection that began
  after the manifest boundary.
- Cycle 43 is the first manifest that can state whether that connection remained open across its
  boundary.

```mermaid
sequenceDiagram
    participant EventLoop as Connection event loop
    participant Manifest as Manifest publisher
    participant Kafka

    Manifest->>Manifest: Copy exact set for cycle 42
    Manifest->>Manifest: Advance current cycle to 43
    EventLoop->>EventLoop: Open connection-9
    EventLoop->>EventLoop: Add connection-9 to exact set
    EventLoop->>Kafka: TrafficObservation(cycle 43)
    Manifest->>Kafka: LivenessSnapshotChunk(cycle 42, omits connection-9)
    Note over Kafka: Either Kafka append order is valid.<br/>Cycle 42 does not apply to connection-9.
```

Later joins, proxy group rebalances, and unrelated connections do not alter these per-writer,
per-partition cycle relationships.

## 6. Connection opening and retirement

### 6.1 Opening

The proxy adds a newly opened connection to the exact set before accepting its first
`TrafficObservation` for publication. The first observation carries the current manifest cycle.

### 6.2 Clean connection retirement

Connection retirement follows this order:

1. Netty reports that the connection closed because of remote closure, local closure, or channel
   failure.
2. The kernel and Netty provide no further network traffic for that connection.
3. The connection's event-loop owner submits its terminal `TrafficObservation` after every earlier
   observation in that connection's publication order.
4. The event-loop owner waits for Kafka to acknowledge the terminal observation and every earlier
   `TrafficObservation` for that connection.
5. Only after those acknowledgements does the event-loop owner remove the connection from the
   exact open-connection set.

A manifest prepared before step 5 lists the connection. A manifest prepared after step 5 may omit
it.

If an acknowledgement fails or its outcome is ambiguous, the proxy does not publish an
authoritative omission for that connection. The proxy closes capture and follows its configured
failure behavior.

The terminal `TrafficObservation` establishes a replayer validation cutoff:

> After the replayer processes a connection's terminal `TrafficObservation`, any subsequent
> `TrafficObservation` for the same `(writerNodeId, connectionId)` is a protocol violation and
> causes the containing Kafka record to be retained, high-severity diagnostics and an alarm, and
> termination of the replayer process.

This is a replayer validation rule, not Kafka producer fencing.

A terminal connection `TrafficObservation` unambiguously states that Netty has ended that
connection. The lower-level protocol maps remote closure, local closure, and channel failure to the
corresponding protobuf observation. When the replayer processes it, the replayer:

- expires the connection's current incomplete request assembly;
- marks an incomplete source response as expired;
- allows an already-started target transaction to reach its normal outcome;
- requires durable tuple output for every request that was reconstituted;
- requires no tuple when no request was reconstituted; and
- makes the affected Kafka records eligible for commit after all associated processing finishes.

### 6.3 Applicable manifest omission

Manifest omission applicability decides only whether the omission expires a specific incomplete
HTTP request or source-response accumulator. For either accumulator, let its first cycle be the
`manifestCycle` carried by the earliest `TrafficObservation` contributing to that accumulator. An
omitting manifest applies to that accumulator only when its cycle is greater than or equal to the
accumulator's first cycle. The connection-opening observation is irrelevant when it is no longer
part of an incomplete accumulator.

When an applicable manifest omits a connection:

- the replayer expires each incomplete HTTP request accumulator to which the manifest applies;
- the replayer expires each incomplete source-response accumulator to which the manifest applies;
- the connection identity becomes terminally retired; and
- any later `TrafficObservation` for that identity is a protocol violation.

The omission releases only the affected accumulator's processing obligation. It does not resolve
every uncommitted record carrying an earlier observation and does not cancel or complete a target
HTTP transaction already in progress. Target replay, its target response, durable tuple output, and
other independent processing continue normally.

For every reconstituted request, the replayer still writes a durable result tuple. If the source
response expired, the tuple records the source response as expired rather than complete. Once the
target operation reaches its required outcome and the tuple is durable, the processing associated
with those observations finishes and their Kafka records become eligible for commit.

Let M be the newest complete manifest cycle applicable to an incomplete accumulator. If M omits the
connection, every valid observation contributing to that accumulator must have a Kafka offset below
chunk 0 of manifest cycle M. An observation for the retired connection at or above that offset is a
protocol violation, including an observation interleaved between chunks.

## 7. `NoMoreWrites` and writer-partition retirement

`NoMoreWrites` permanently retires one `(writerNodeId, partition)`. The required Kafka record header
identifies the writer. A correctly wired proxy publishes `NoMoreWrites` only for itself.

Before publishing `NoMoreWrites`, the proxy:

1. stops accepting new connections using the routing and acceptance gate;
2. disconnects and fully retires existing connections, including Kafka acknowledgement of every
   terminal observation and every preceding observation;
3. verifies that the exact connection registry is empty;
4. moves the publisher lane to `RETIRING` and quiesces periodic manifests;
5. waits for every remaining previously accepted send;
6. publishes and receives acknowledgement for the final empty manifest and then
   `NoMoreWrites`; and
7. moves the publisher lane to `RETIRED`.

The Kafka offset occupied by `NoMoreWrites` is the terminal cutoff. The replayer may apply
`NoMoreWrites` without first reconstructing the final empty manifest. It supplies the terminal
outcome for remaining incomplete state already known for that writer and partition. Each affected
whole Kafka record becomes eligible for commit only after every other operation associated with
that record has also finished. An exact duplicate `NoMoreWrites` is inert and eligible for commit.

The replayer treats the required header as the authoritative writer identity. If the header is
missing or malformed, the replayer logs a warning, treats the `NoMoreWrites` record as inert, and
permits its commit. It does not retire any writer or connection state.

No traffic or manifest record may appear at a Kafka offset above the valid `NoMoreWrites` cutoff for
that `(writerNodeId, partition)`. Such a record is a protocol violation that causes retain,
high-severity diagnostics and an alarm, and termination of the replayer process.

A hard crash may emit neither the final manifest nor `NoMoreWrites`. Group departure does not
replace them.

Unexpected death of any proxy event loop terminates the proxy process. The process is considered
unstable, and no cross-thread recovery transfers ownership of its connections or publication state.
Logging, metrics, and cleanup are best effort; process termination must not wait for orderly local
recovery.

## 8. Broker-time expiration of known connection state

Each complete `LivenessSnapshotChunk` set is a periodic exact statement of the connections still
open for one `(writerNodeId, partition)`. A complete manifest that lists a connection refreshes that
connection's liveness even when the connection has carried no request or response bytes for hours.

The replayer derives the same `manifestLogAppendTime` defined in §5 and tracks
`lastAcceptedManifestLogAppendTime` for each writer and partition. A complete manifest updates the
value only when the difference from the previous accepted value is less than `E`. The proxy
invariant in §4 guarantees that after it acknowledges a late manifest it publishes no subsequent
manifest for that compromised process. The replayer does not maintain an additional sticky-lapse
state.

Let:

- `E` be the manifest expiration interval configured identically in the proxy and replayer;
- `S` be the maximum permitted backward movement between Kafka `LogAppendTime` values at increasing
  offsets in one partition and configured identically in the proxy, replayer, and fleet clock
  monitor;
- `M` be a writer and partition's `lastAcceptedManifestLogAppendTime`; and
- `R` be the `LogAppendTime` of any later record at a higher offset in that partition, regardless of
  which proxy wrote it.

The replayer may expire the incomplete connection state that it already knows for that writer and
partition when:

```text
R - M >= E + S
```

Suppose a later observation from the expired writer has broker timestamp `O`. The clock-skew bound
requires:

```text
O >= R - S
```

Therefore:

```text
O - M >= E
```

The proxy's forwarding rule in §4 rejects that observation and enters the compromised state before
the corresponding Critical Mutation Traffic can reach the source. It is therefore safe for the
replayer to release the incomplete state known at offset R.

Expiration:

- ends each currently known incomplete request accumulator for that writer and partition;
- marks each currently known incomplete source-response accumulator as expired;
- allows a target HTTP transaction already in progress to continue;
- still requires a durable tuple for every request that was reconstituted; and
- requires no tuple when no request was reconstituted.

Expiration releases only those known accumulators. It does not retire the writer, partition, or
future connection state. Legitimate connections opened after a rebalance carry the new
assignment-scoped `writerNodeId` and its independently established baseline. The first later
`TrafficObservation` for any connection not currently known to the replayer is handled exactly as
it would be after a replayer restart whose cursor begins at that observation: it creates fresh
initialized state. It is never combined with an expired accumulator. If the later observations form
another incomplete accumulator and no accepted manifest refreshes it, a later broker-time horizon
expires that accumulator in the same way. A compromised proxy may not resume accepting connections
or publish a later authoritative manifest under any identity in the same process.

Expiration is the required final outcome for the affected accumulators. The replayer commits the
Kafka records holding those observations after every other independent operation associated with
each whole record has also finished. Leaving an expired accumulator's records permanently
uncommitted would create a poison pill.

Kafka backlog does not itself cause expiration because the rule uses durable broker timestamps
rather than consumer wall-clock time. If the declared `S` bound is violated or the fleet cannot
attest that it is healthy, the timestamp proof is unavailable. The replayer stops making affected
expiration decisions, retains the unresolved records, and raises a high-severity alarm. Ordinary
record processing that does not depend on this timestamp proof may continue.

## 9. Replayer processing and whole-record commit

One `TrafficRecord` may contain several ordered `TrafficObservation` values, including observations
carrying different manifest cycles.

For the replayer, it can only commit the whole record.

The replayer processes each observation in the record's order while tracking that the observations
belong to one indivisible Kafka record. It does not wait to validate the future semantic effect of
every later observation before processing an earlier valid observation:

```mermaid
flowchart TD
    Record["One TrafficRecord at one Kafka offset"]
    O1["Apply TrafficObservation 1"]
    O2["Apply TrafficObservation 2"]
    O3["Apply TrafficObservation 3"]
    Join{"Has all required processing<br/>for every observation finished?"}
    Commit["Whole-record Commit"]
    Retain["Whole-record Retain"]
    Wait["Keep record unresolved"]

    Record --> O1
    O1 --> O2
    O2 --> O3
    O3 --> Join
    Join -->|"All finished successfully"| Commit
    Join -->|"Any requires redelivery"| Retain
    Join -->|"Anything unfinished"| Wait
```

Applying the observations in order does not require waiting for asynchronous target, source-response,
or tuple work started by one observation to finish before applying the next observation. Those
operations may overlap, but the observations that create or update them are applied in Kafka order.

`Commit` and `Retain` describe only whole Kafka records:

- `Commit` means the replayer may advance the Kafka partition's committed offset past the record.
- `Retain` means the replayer deliberately leaves the record uncommitted and eligible for
  redelivery.

Processing associated with an observation can include:

- incomplete HTTP request assembly;
- a reconstituted request sent to the target;
- source-response assembly;
- durable tuple output;
- connection expiration or terminal validation; and
- cleanup of resources created by those operations.

When an applicable manifest omits a connection, the replayer marks only the applicable incomplete
request or source-response accumulator as expired. It does not finish independent processing merely
because the supporting Kafka record remains uncommitted, and it does not complete or cancel a
target HTTP transaction already running for a reconstituted request.

A record becomes eligible for `Commit` only after every required operation associated with all of
its observations has reached its required final state. If any operation requires redelivery, the
whole record is `Retain`, even when other observations in the same record finished successfully.

Every `TrafficRecord` whose observations contribute to one or more reconstituted requests remains
unresolved until every required tuple for those requests is durable. An observation belonging only
to an incomplete request that expires without producing a reconstituted request needs no tuple and
finishes through the expiration rule in §8.

A later invalid observation does not retroactively undo a target request already sent from earlier
valid observations. The invalid containing record is retained and replay halts according to §13.

`LivenessSnapshotChunk` records follow the partial-manifest rule in §5. A valid `NoMoreWrites`
record finishes after its terminal effect in §7 has been applied. These control records are whole
Kafka records and follow the same contiguous-offset commit rule.

Retaining one record prevents the replayer from committing a later Kafka offset past it. This
preserves redelivery of the retained record.

## 10. Target replay and durable tuples

When the replayer recognizes a complete captured request, it may begin the target HTTP transaction
without waiting for the captured source response or connection closure.

For a positive configured `speedupFactor`, the nominal replay time is:

```text
replayStart + (sourceObservationTime - firstSourceObservationTime) / speedupFactor
```

Requests captured on the same source connection retain their captured order. That ordering takes
priority over exact pacing: if the target is slower than the source, later requests on that
connection wait rather than overtake the request ahead of them. Independent source connections may
continue concurrently. The replayer reuses the corresponding target connection across requests
while its unchanged target-connection policy permits that connection to remain open.

The target result and captured source response may finish in either order.

Before the Kafka records supporting a reconstituted request may be committed, the replayer writes a
durable tuple for that request. The tuple records the available source and target outcomes,
including a source response that is complete or expired.

The Kafka processing associated with that request cannot finish until:

- the target operation has reached the outcome required by replay policy;
- the source response has become complete, expired, or otherwise reached its defined final state;
- the tuple has been written durably; and
- all resources owned by those operations have been released.

Manifest omission and connection expiration may finalize the source-response side as expired.
They do not erase the request, cancel the target transaction, or waive durable tuple output.
A missing or expired source response does not prove that the source mutation failed; the tuple must
represent the source-response outcome without inferring an unobserved source result.

The system is at-least-once. A process may crash after sending the target request or writing the
tuple but before committing Kafka. Redelivery may therefore send the target request again and write
a duplicate tuple. This protocol accepts those duplicates and introduces no deduplication
mechanism.

## 11. Replayer partition reassignment

Kafka assigns partitions to replayer group members. Records themselves are not assigned; a
replayer polls records from its currently assigned partitions.

For one partition, a **partition generation** is one uninterrupted period during which the replayer
owns that partition. When Kafka revokes the partition, the replayer:

1. stops accepting additional records from the revoked partition generation;
2. cancels every unfinished operation associated with records already polled from that generation,
   including incomplete HTTP assembly, target work, source-response assembly, tuple output,
   manifest processing, timers, and their owned resources;
3. treats cancellation as requiring redelivery rather than as successful processing;
4. waits for the cancelled operations and their owned resources to reach their required final
   states; and
5. does not process records from a newer generation of that partition until the old generation's
   in-process work and cleanup are finished.

The completion rule is:

> After every record and operation from the revoked partition generation reaches its required final
> state, the replayer removes that generation's process-local bookkeeping. Kafka records that have
> not been committed are implicitly retained and remain eligible for redelivery by a future
> partition generation.

A commit rejected because the replayer no longer owns the partition is not retried under the old
generation. If ownership is lost while a previously submitted commit has an unknown broker outcome,
the replayer records the attempt as `ownership lost; broker outcome unknown`. It neither retries
the commit nor waits indefinitely for its callback. It cleans up the old assignment's process-local
state and lets Kafka's next assigned offset determine whether redelivery occurs. A late callback is
ignored except for logging and metrics.

Partitions are independent. A partition with no unfinished prior generation may begin processing
immediately even while another partition is still cleaning up its revoked generation.

```mermaid
sequenceDiagram
    participant Kafka
    participant Old as Old partition generation
    participant Work as Replay operations
    participant New as New partition generation

    Kafka->>Old: Revoke partition
    Old->>Old: Stop accepting old-assignment records
    Old->>Work: Cancel unfinished operations
    Work-->>Old: Required outcomes and cleanup finish
    Old->>Old: Remove process-local bookkeeping
    Note over Old,Kafka: Uncommitted records remain eligible for redelivery
    New->>New: Begin processing records for the new generation
```

No timeout permits the replayer to discard old in-process state and continue as though cleanup
succeeded. A timeout may terminate the process.

## 12. Process failure and shutdown

### 12.1 Replayer event-loop death

Unexpected death of a replayer event loop terminates the replayer process.

The process is unstable because the sole owner of mutable connection, target, timer, and replay
state no longer exists. The system does not transfer that ownership to another thread or construct
successful completion from partial cleanup.

After fatal event-loop death is detected, the replayer emits a best-effort
`replayFatalFailures{reason=event_loop_terminated}` metric and an ERROR diagnostic, synchronously
flushes Log4j and standard error, and immediately invokes `Runtime.halt(80)`. Metric export is not
guaranteed before termination. The reason-specific halt code is distinct from the replayer's normal
`System.exit` codes.

The replayer does not initiate cleanup, completion, or Kafka commit coordination because of
event-loop death. Any concurrent external operation may or may not complete. Kafka's committed
offset determines the durable outcome after restart.

Correctness relies on Kafka redelivering records whose offsets were not committed, just as it would
after an out-of-memory failure or hard process kill.

### 12.2 Normal replayer shutdown

Normal shutdown is ordered:

1. stop accepting new Kafka records;
2. cancel every unfinished operation associated with accepted Kafka records;
3. mark their whole records for redelivery unless they had already reached a committable final
   state;
4. wait for operation cleanup and resource release;
5. complete any Kafka commits that remain valid while the partition is still owned; and
6. close Kafka, tuple output, transformation resources, and event loops.

Cancellation never causes a Kafka commit.

### 12.3 Orderly proxy shutdown

An orderly shutdown is a planned operation performed while capture and Kafka publication remain
trustworthy, such as `SIGTERM`, a planned rollout, fleet-directed replacement, or another deliberate
administrative shutdown. It follows the connection and writer-retirement ordering in §§6–7. The
default bound for completing that orderly retirement is five minutes.

The orderly-retirement bound is not a general failure response. Capture-compromise and unstable
process failures follow §12.4 instead.

### 12.4 Proxy capture failure modes

An unexpected proxy event-loop death, an out-of-memory-like failure, or corrupted internal
ownership always terminates the process immediately.

Failure to publish a Kafka record required for authoritative capture, a manifest lapse, an
ambiguous producer outcome, or another failure that compromises capture closes the proxy's one-way
capture state and applies `--capture-failure-policy` to the whole process:

- `fail-closed` emits high-severity diagnostics and terminates immediately. It does not attempt
  connection or writer retirement because Kafka acknowledgements are no longer trustworthy.
- `fail-open` makes a one-way transition to process-wide pass-through. Existing TCP connections
  remain open and continue forwarding without capture; new TCP connections also forward without
  capture. The process permanently stops authoritative capture, emits a persistent high-severity
  capture-gap alarm, and never returns to capture.

The previous per-request behavior in which one request could forward after a capture failure and
the process could then resume authoritative capture is not permitted.

A definite transient failure proven not to have compromised capture may retry normally. Membership
polling and rebalance events are not capture failures after startup and never invoke this policy.

A suspended proxy that resumes after its acknowledged manifest has become stale cannot forward new
Critical Mutation Traffic as captured; the capture-health check in §4 applies the configured
process-wide policy before those source bytes are submitted.

## 13. Protocol violations

The following are protocol violations:

- a `TrafficObservation` after the terminal observation for the same connection identity;
- a `TrafficObservation` at or above chunk 0 of the newest complete applicable manifest cycle that
  omits the connection;
- a traffic or manifest record after `NoMoreWrites` for the same writer and partition;
- a connection-local observation sequence that regresses, conflicts, or has an unexplained gap;
- a manifest whose chunks conflict or claim impossible indexes;
- a later observation whose `manifestCycle` is lower than an earlier connection-local observation;
- malformed input for which the replayer cannot determine the required processing safely.

The explicitly inert cases are an exact duplicate `NoMoreWrites`, a partial but otherwise valid
manifest chunk set, and a `NoMoreWrites` record with a missing or malformed writer header as
specified in §7. For other protocol violations, the replayer response is:

1. immediately mark the whole Kafka record `Retain`, making it permanently ineligible for commit
   in this process;
2. block Kafka commits at that offset and pause Kafka intake;
3. emit a high-severity log and metric containing the available writer, connection, partition, and
   offset identities; and
4. raise an operator-visible alarm;
5. perform a bounded drain of target replay and tuple work that was already admitted; and
6. terminate the replayer process.

The bounded drain completes already-started side effects and releases their resources. It does not
decide the record disposition: `Retain` was already selected by the violation. If admitted work
does not finish within the bound, the replayer terminates without waiting longer.

There is no keep-running mode for a protocol violation. The poison record remains uncommitted and
eligible for redelivery. The replayer must not guess, silently discard required work, or turn an
unknown case into a commit.

Failures indicating that the process itself is unstable—including event-loop death, an OOM-like
failure, or corrupted internal ownership—do not use this bounded protocol-violation drain. They
terminate the process immediately, leaving every uncommitted record eligible for redelivery.

## 14. Safety arguments

### 14.1 A source mutation is replayable

For Critical Mutation Traffic, the proxy waits for acknowledgement of the complete request
representation before allowing the request to take effect at the source. Therefore a source
mutation cannot be absent from Kafka merely because the proxy failed immediately afterward.

This argument depends on the source not mutating from an incomplete request body.

### 14.2 Manifest omission cannot hide valid earlier traffic

The proxy removes a closed connection from the exact set only after its terminal observation and
every earlier observation are acknowledged. An applicable omitting manifest is prepared only after
that removal. Therefore all valid observations for the retired connection precede chunk 0 of the
applicable omitting manifest cycle in the Kafka partition.

### 14.3 Concurrent publication does not confuse manifest applicability

Every observation carries the proxy-side manifest cycle from when it was accepted. A manifest
cannot expire an incomplete accumulator whose earliest contributing observation belongs to a later
cycle. Kafka position alone is never used to infer that proxy-side relationship.

### 14.4 Mixed records cannot be partially committed

The replayer may process observations independently, but it waits for every observation and
associated operation before deciding the whole record. Kafka's indivisible offset is therefore
preserved even when one record crosses manifest cycles or contributes to several asynchronous
operations.

### 14.5 Source-response expiration does not lose a replay result

Every reconstituted request still produces a durable tuple. Expiration changes the source-response
status recorded in that tuple; it does not erase the request or waive target and tuple processing.

An incomplete request that never became a reconstituted request requires no tuple. Expiration still
finishes its observation processing and permits its Kafka records to commit, preventing a permanent
poison pill.

### 14.6 Reassignment cannot commit cancelled work

Partition revocation stops new old-assignment intake before cancellation. Cancelled work does not
authorize commit. The replayer removes process-local state only after old operations reach their
required final states, and any uncommitted records remain available to a later partition
assignment.

### 14.7 Broker-time expiration cannot hide a later source mutation

At a qualifying later offset, the skew-adjusted broker-time inequality proves that every
subsequently appended observation from the silent writer is at or beyond the proxy's rejection
threshold. The proxy therefore cannot use it to forward Critical Mutation Traffic. Releasing only
the replayer's currently known incomplete accumulators cannot hide a future source mutation.

The expiration does not retire the writer or partition. A future first observation creates fresh
state. Legitimate newly opened connections after rebalance use the new assignment's
`writerNodeId`; older identities continue only their already-open connections until final empty
manifest and `NoMoreWrites`.

### 14.8 Hard failure is conservative

A hard proxy crash may leave incomplete connection state until a later partition record establishes
the broker-time horizon in §8. A completely quiet partition has no such evidence and remains
unresolved. A hard replayer crash leaves uncommitted Kafka records eligible for redelivery. Neither
component manufactures successful completion from consumer wall-clock silence.

### 14.9 Partial manifests are harmless

No omission or listing decision is applied from a partial `LivenessSnapshotChunk` set. Committing
the individual chunk records cannot create a false connection decision. After a restart, the
replayer waits for the next complete manifest cycle.

## 15. Verification requirements

Every guarantee above requires deterministic tests and, where Kafka behavior is involved,
real-Kafka tests.

| Guarantee | Deterministic tests | Real-system tests |
|---|---|---|
| Capability probe is inert | `writerNodeId = captureActivationId + ":PROBE"` never creates writer, manifest, baseline, connection, or replay state | Testcontainers probes before first group generation |
| Group membership only load-balances new connections | Startup requires a first usable assignment; the last usable assignment remains active through revocation, loss, and polling failure; replacement assignments create new writer identities; existing connections keep their identity | Testcontainers coordinator outage, revocation/loss callbacks, overlapping stale and replacement assignments, and live proxy scale-up and scale-down |
| Capture-before-forward | Initial manifest baseline; max chunk `LogAppendTime`; observation threshold; irreversible compromise | Kafka delay, timestamp, and failure injection; live source verification |
| Incomplete-request denial-of-service limits | Absolute duration is not reset by progress; request/header limits close the connection; whole connections default to a 60-minute maximum lifetime | Slow one-byte-at-a-time request, oversized-header, and maximum-connection-lifetime tests |
| Connection-local ordering | Sequence assignment and publication remain ordered while other connections run concurrently | Testcontainers interleaving across connections without replayer reordering |
| Exact manifest semantics | Add, retire, copy, cycle, chunk, and ordering models | Testcontainers observation/manifest order inversions |
| Clean connection retirement | Terminal observation is last; removal waits for acknowledgements | Producer retry and ambiguous-send tests |
| Mixed-cycle `TrafficRecord` | Separate observation processing; whole-record commit only after all finish | Testcontainers redelivery of an uncommitted mixed record |
| Connection expiration | Agreed `E` and `S`; broker-time model; expire only known accumulators; future first observations start fresh; skew-bound failure retains | Testcontainers multi-writer partition progress, leadership changes, configuration agreement, and delayed observations |
| `NoMoreWrites` | Final empty manifest and prior sends acknowledged first; duplicates and records with missing or malformed writer headers are inert | Testcontainers terminal ordering, duplicate delivery, and header validation |
| Replayer reassignment | Cancel and clean one partition generation before processing its successor; unrelated partitions proceed | Testcontainers partition transfer during target and tuple operations |
| Event-loop death | Fatal signal and no ownership transfer | Process-level fault injection; live container restart |
| Process-wide capture failure policy | Required Kafka publication failure or capture compromise causes immediate `fail-closed` termination or irreversible `fail-open` pass-through for existing and new TCP connections; membership events do neither after startup | Producer failure and ambiguous-outcome fault injection in both modes |
| Protocol violations | Immediate `Retain`, intake pause, bounded drain of admitted target/tuple work, and process termination | Corrupt and out-of-order Kafka records with in-flight target and tuple work; redelivery after exit |
| Bring-your-own archive fidelity | Version, partition ranges, binary key/value, ordered headers, source offsets, original timestamps, `E`, `S`, checksums, and timestamp mode | Export/import round trip with chunked manifests, `NoMoreWrites` headers, mixed-cycle records, multiple partitions, corruption, and missing-record injection |

The full acceptance suite must also prove:

- a connection may remain idle across many manifests without expiration;
- a proxy may stop using a partition for a long time and later place a newly opened connection on
  it under the last usable assignment's `writerNodeId`;
- a partial manifest's chunks may commit without applying a partial connection list, and a later
  complete cycle restores manifest interpretation after restart;
- manifest-cycle flushing and mixed-cycle batching produce the same observable processing;
- an incomplete request expires and commits without requiring a tuple;
- a source response expiring before or after target completion still requires durable tuple output;
- a writer may drain a partition, remain silent, and later create fresh connection state there after
  rebalance under a new assignment-scoped `writerNodeId` without reviving an expired accumulator;
- a late manifest irreversibly compromises the proxy, and serialized manifest publication prevents
  any later manifest from appearing to restore freshness;
- rapid rebalances may leave several `(writerNodeId, partition)` registries and publisher lanes
  draining independently;
- a record from another writer can establish `E + S` progress for a silent writer on the same
  partition;
- violating the declared clock-skew bound disables broker-time expiration and retains affected
  records;
- a crash after target execution or tuple output but before Kafka commit may produce accepted
  at-least-once duplicates;
- cancellation never creates a Kafka commit;
- retained records prevent committing later offsets past them;
- a semantic violation in a later record does not prevent an earlier complete request from reaching
  the target; and
- a `NoMoreWrites` record with a missing or malformed writer header is inert, warned, and
  committed;
- a `preserve` archive replay produces the same protocol decisions as the original partition logs
  while using newly assigned imported offsets for commits;
- `rebase-without-expiration` never applies broker-time expiration; and
- archive end-of-file alone never completes an open writer or incomplete connection.

## 16. Companion document boundaries

The final design set contains this document and three progressively detailed companions:

1. [**Proxy Capture Protocol**](proxyCaptureProtocol.md): group assignment, capability checks,
   manifest generation, connection retirement, publication ordering, failure handling, and proxy
   tests.
2. [**Replayer Processing and Commit Architecture**](replayerProcessingAndCommitArchitecture.md):
   record handling, HTTP assembly, target replay, tuple output, expiration, commit accounting,
   rebalance, shutdown, asynchronous ownership, cancellation, cleanup, and owner-affinity checks.
3. [**Managed Fleet Capture Recovery**](managedFleetCaptureRecovery.md): controller
   responsibilities, durable state, snapshot boundaries, and explicitly unresolved recovery
   mechanisms.

Child documents may add implementation detail but may not weaken or redefine this document's
observable guarantees.
