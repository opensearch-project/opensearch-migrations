# Managed Fleet Capture Awareness and Future Recovery

**Status: current terminal-failure contract plus future recovery design (2026-09-10).** The
standalone contract is
[Proxy Horizontal Scaling, Capture Liveness, and Writer Completion](proxyHorizontalScalingAndNodeDeath.md).
That contract now uses exact positive manifests, terminal self-only `NoMoreWrites` for permanent
writer-partition retirement, configured expiration of incomplete state, and an irreversible local
capture gate. It has no designated witnesses, peer completion, or peer terminal cutoff.

This addendum extends those rules for a controller-managed Kubernetes fleet:

- a writer node id is never reused after capture is abandoned;
- temporary partition drain uses an acknowledged empty manifest;
- `NoMoreWrites` is partition-scoped, self-emitted after full connection/publisher teardown, and
  terminal for that writer-partition identity; publisher teardown includes asynchronous quiescence
  of periodic manifest callbacks before the final empty manifest;
- configured expiration settles only incomplete reconstruction state;
- `lastPositiveLivenessBrokerTime`, `scannedThroughBrokerTime`, and every replayer liveness or
  expiration timestamp use Kafka `LogAppendTime` exclusively;
- valid recognized capability probes advance only `scannedThroughBrokerTime`; they create no replay
  work and refresh no connection's `lastPositiveLivenessBrokerTime`;
- within the standalone design's non-streaming source-execution scope, strict mode captures a
  complete request before source execution and checks acknowledged-manifest freshness before
  forwarding;
- mutating-request classification hardening remains deferred as documented by the standalone
  design;
- producer configuration enforces idempotence, `acks=all`, and an ordering-preserving
  `max.in.flight.requests.per.connection`; deployment configuration cannot weaken those settings;
- exact-registry mutation, manifest copy and lane admission, and connection traffic use the
  standalone design's publisher-owned linearization construct;
- pass-through permanently abandons capture and raises a persistent gap alarm; and
- an uncaptured source interval starts a new capture and replay run with a fresh source snapshot.

For the current round, any terminal capture failure after bounded retry terminates the entire
capture workflow. The controller durably marks the resource incomplete and terminal before
authorizing pass-through, never grants capture again under that resource UID, and never returns its
coverage condition to true. Recovery means starting a fresh capture, snapshot, and replay workflow
from the beginning. The controller may keep failed-run pass-through capacity alive for availability,
but that capacity cannot participate in the replacement capture run.

The capture-session reset, source-side snapshot barrier, and automatic return to complete fleet
coverage described later in this document are future work. Their unresolved decisions do not block
the current terminal-on-error behavior.

## 1. Goals

The current managed extension adds three capabilities:

1. Durably mark capture incomplete before intentionally forwarding uncaptured source traffic.
2. Determine whether every process still capable of affecting the source is capture-only.
3. Make controller failure conservative: stall, remain incomplete, or exit, but never silently
   assert complete capture.

Future recovery work would add two more capabilities:

1. Recover a fleet automatically after an uncaptured interval without confusing delayed records
   from the abandoned capture interval with new capture.
2. Bind a replacement source snapshot and replayer run to one authoritative capture session.

Neither scope promises global ordering across connections or partitions or makes missing traffic
replayable.

## 2. Why the base protocol is insufficient for automation

The base protocol deliberately exposes local facts rather than one fleet verdict. That leaves
several automation gaps:

- A proxy can enter autonomous pass-through before any external system records the compromise.
- A process removed from the load balancer may retain an existing connection and continue mutating
  the source for an hour or more.
- Killing a producer prevents new sends but does not retract a Kafka request already transmitted
  to a broker. Client delivery and blocking timeouts are not broker fencing.
- A delayed record from an old writer can first appear on a partition after a new fleet boundary.
  An opaque, previously unseen writer node id is insufficient to classify it.
- A replayer starting after Kafka retention removed an earlier boundary record cannot safely infer
  the current capture generation from the first traffic record it sees.
- Simultaneous loss of the relevant writers and their final manifests has no ordinary positive
  settlement path. Automated recovery must explicitly abandon the incomplete interval rather than
  infer that replacement capacity observed the old writers.

These gaps require an external authority plus an explicit data-plane session identity. A process
timeout alone is not that authority.

## 3. Terminology and identities

The managed protocol uses distinct identities for distinct purposes.

### 3.1 `processId`

Identifies one running proxy process to the controller and deployment platform. It is fresh on
every process start.

### 3.2 `writerNodeId`

Identifies one capture activation for membership, manifests, traffic attribution, and ordinary
`NoMoreWrites` handling. It remains opaque to the replayer and is never reused after terminal
self-completion or local capture-authority loss.

### 3.3 `recoveryId`

Identifies one idempotent controller recovery workflow. It is composed from the Capture Replay
resource identity and a monotonically increasing controller sequence. It prevents stale control
commands from reactivating an older workflow.

### 3.4 `captureSessionId`

Future automatic recovery uses this identity for the complete-capture interval to which data-plane
records belong. It is derived from, or equal to, the recovery id that established that interval.

`captureSessionId` is the load-bearing wire identity that prevents delayed old traffic from being
accepted after fleet recovery. It is not Kafka's producer epoch, is not an assignment revision,
and is not inferred from `writerNodeId`.

### 3.5 Traffic-affecting process

A process is traffic-affecting if it:

- can receive a new source connection;
- owns an existing client or source-side connection;
- may still forward a previously accepted source operation; or
- is unreachable and has not been durably retired or fenced.

Fleet completeness is evaluated over this set, not just load-balancer endpoints or desired
replicas.

### 3.6 Kubernetes workload identity

The controller identifies a running process by immutable workload identity, not by Pod name:

```text
cluster identity
namespace
Pod UID
container ID
node UID
processId
writerNodeId
```

A replacement Pod with the same Deployment, StatefulSet ordinal, labels, IP address, or name is a
different process. The grant ledger retains the old identity until its retirement proof is durable.

## 4. Required proxy changes

### 4.1 Configuration

Controller-managed processes use:

```
suppressCaptureByDefault = true
captureFailureRetryTimeout
captureFailureDisposition = CONTROLLER_AUTHORIZED_PASS_THROUGH
controllerAuthorizationTimeoutAction = CONTINUE_BLOCKING | EXIT
controllerPassThroughHandoff = SAME_PROCESS | REPLACEMENT_PROCESS
nonCapturingConnectionDrainTimeout
```

`suppressCaptureByDefault=true` is stable deployment configuration. The controller does not toggle
it during each incident. Every replacement therefore boots suppressed even if it missed prior
commands.

The proxy may retry Kafka while in `CAPTURE_RETRYING`. All affected source forwarding remains
capture-before-forward during that interval. Once retry is exhausted, the writer activation is
permanently closed and the process enters `CAPTURE_FAILURE_PENDING`.

### 4.2 Local states

The managed outer lifecycle is:

```
SUPPRESSED
  -> PROBING
  -> PROBATIONARY
  -> ACTIVE
  -> CAPTURE_RETRYING
       -> ACTIVE                         // Kafka recovered before failure decision
       -> CAPTURE_FAILURE_PENDING        // writer activation permanently closed
            -> PASS_THROUGH_COMPROMISED  // only after controller authorization
            -> process exit
```

A healthy controller-requested suppression has a separate path:

```
ACTIVE -> SUPPRESSING -> CAPTURE_SUPPRESSED_AND_QUIESCENT
```

Only the healthy suppression path may later create a fresh writer activation in the same process.
A process that entered `CAPTURE_FAILURE_PENDING` or `PASS_THROUGH_COMPROMISED` because of a send
failure or ambiguous producer outcome never captures again. It must terminate before the recovery
can become complete.

### 4.3 Status and control interface

Every proxy exposes a port that is not used for source forwarding. Read-only status includes:

```
processId
writerNodeId, if one is active
podUid
captureReplayResourceUid
recoveryId
captureSessionId
captureActivationNumber
captureOperatingState
captureCapability and latestProbeResult
captureFailureCause and captureFailureRetryAge
controllerAuthorizationWaitAge
membershipPhase
forwardingReady
sourceListenerAccepting
captureReady
captureAdmissionSuppressed
publisherQuiescent
capturingConnectionCount
nonCapturingConnectionCount
oldestNonCapturingConnectionAge
protocolVersion
binaryVersion
```

Authenticated, authorized, idempotent mutation endpoints include:

```
suppressCapture(recoveryId)
authorizeCompromisedPassThrough(recoveryId)
grantInitialCapture(recoveryId, captureActivationNumber)
forceCloseNonCapturingConnections(recoveryId)
```

Future same-resource recovery additionally requires:

```
grantCapture(recoveryId, captureSessionId, captureActivationNumber)
emitFleetCaptureReset(recoveryId, captureSessionId)
emitCaptureCoverageEstablished(recoveryId, captureSessionId)
```

Every mutation also carries a common command envelope:

```
targetPodUid
targetProcessId
captureReplayResourceUid
recoveryId
expectedCaptureActivationNumber
commandId
```

Session-changing commands additionally carry `captureSessionId` and the new activation number. The
proxy compares the target Pod UID and process id with its immutable local identity and rejects a
command for a reused Pod name, IP address, or earlier process. Once bound, it rejects a different
resource UID and rejects stale recovery, session, or activation values. It handles an exact
duplicate `commandId` idempotently.

Readiness probes cannot call mutation endpoints. Network policy, transport authentication, and
request authorization restrict mutations to the workflow controller.

### 4.4 Capture capability

`captureCapability=true` must mean more than metadata connectivity. Before implementation, the
probe contract must specify:

- whether it performs an acknowledged Kafka write;
- which traffic partitions and broker leaders it covers;
- the producer settings and timeouts it validates;
- how probe records are distinguished from traffic; and
- how the controller prevents a partial probe from authorizing capture.

Until that contract exists, capability is advisory and cannot authorize a capture grant. A
metadata lookup or successful TCP connection alone is insufficient.

## 5. Future recovery: data-plane record changes

Every controller-managed writer-scoped record carries the capture session of the subject writer:

```
TrafficRecord {
    writerNodeId
    captureSessionId
    ...
}

OpenConnectionManifest {
    writerNodeId
    captureSessionId
    ...
}

NoMoreWrites {
    writerNodeId
    writerCaptureSessionId
    partition
    emitterNodeId
}
```

The session field is optional only for the unmanaged legacy mode. A controller-managed process
fails startup if the configured wire version cannot carry it.

The managed protocol adds two partition-scoped control records:

```
FleetCaptureReset {
    recoveryId
    captureSessionId
}

CaptureCoverageEstablished {
    recoveryId
    captureSessionId
}
```

Each control record is written independently to every traffic partition and acknowledged on every
partition before the controller advances. Timestamps may be included for diagnostics, but they do
not establish ordering or authority.

`FleetCaptureReset` and `CaptureCoverageEstablished` are record names. “Reset marker” and “coverage
marker” are informal shorthand and should not appear in protocol APIs.

Adding the session field and control records is a wire-level change. Per the base document's
rollout rule, incompatible binaries never coexist as capture-group members. Deployment first enters
an explicit no-capture interval, empties the old group, rolls all binaries, and then starts a new
cold cohort.

## 6. Controller source of truth

### 6.1 Capture Replay status

The Capture Replay custom resource keeps current truth:

```
status:
  conditions:
    - type: CaptureCoverageComplete
      status: "True" | "False" | "Unknown"
      reason: ...
      lastTransitionTime: ...
    - type: CaptureWorkflowTerminal
      status: "True" | "False"
      reason: ...
      lastTransitionTime: ...
  recoveryId: ...
  captureSessionId: ...
  nonCapturingSince: ...
  captureRecoveryTimestamp: ...
  completenessCertificateRevision: ...
  unresolvedGrantCount: ...
  trafficAffectingWorkloadCount: ...
```

The status is not an unbounded event history. Logs and metrics retain diagnostic history; the
resource answers whether capture is complete now, whether the workflow may ever capture again, and
identifies the current recovery. Once `CaptureWorkflowTerminal=True`, it is immutable for that
resource UID and `CaptureCoverageComplete` can never return to true.
`captureSessionId` and `captureRecoveryTimestamp` remain unset in the current terminal-on-error
round; they are reserved for future same-resource recovery.
`lastTransitionTime`, `nonCapturingSince`, and `captureRecoveryTimestamp` are control-plane audit
times. They never enter broker-time liveness or expiration calculations.

### 6.2 Grant ledger

The controller durably records every capture grant:

```
captureReplayResourceUid
processId
writerNodeId
podUid
containerId
nodeUid
recoveryId
captureSessionId
captureActivationNumber
grantTime
lastObservedState
retirementState
```

The ledger is the inventory of writer activations that must be retired before recovery. Replica
count, current group membership, Pod objects, and load-balancer endpoints cannot reconstruct this
history after failures. Its durable storage cannot depend solely on the lifecycle of the Capture
Replay custom resource. The ledger or an equivalent unresolved-grant tombstone must survive
resource deletion, forced finalizer removal, and recreation with the same name.

### 6.3 Completeness population

For the current round, `CaptureCoverageComplete=True` requires:

- `CaptureWorkflowTerminal=False`;
- every traffic-affecting live process reports the current resource UID, recovery, and activation;
- every process able to forward source traffic is capture-only;
- every non-capturing connection count is zero;
- every older grant is durably retired or belongs to a workload that cannot affect source traffic;
  and
- every required protocol and binary version is compatible.

Because terminal status is immutable, this transition is available only for initial healthy startup
and controlled replacement that never crossed the terminal failure boundary.

Future same-resource recovery additionally requires:

- every old writer activation has durable capture-retirement proof under §8.1 or §8.2;
- every old traffic-affecting workload has durable traffic-retirement proof under §8.3;
- every traffic-affecting live process reports the current recovery and session;
- no previous-session process can still forward a source operation;
- the source-side retirement condition in §8.4 is satisfied for every old process; and
- `CaptureCoverageEstablished` has been acknowledged on every partition.

A process removed from the load balancer but retaining one existing connection remains in the
population. An unreachable process is never assumed healthy.

Capacity thresholds such as `minimumStrictReadyPerAz` decide whether availability requires
pass-through capacity. No threshold below 100% means capture is complete.

The transition to `True` is write-proof-first. The controller first persists an immutable
completeness certificate containing the resource UID, recovery, grant-ledger revision,
traffic-affecting workload inventory, and retirement-evidence references. A future recovery
certificate also contains the capture session, source-side retirement proof, and acknowledged
coverage-establishment offsets. The controller then uses an object-version precondition to write
the certificate revision and `CaptureCoverageComplete=True` to status. A crash before the status
write leaves the condition false or unknown. On failover, a true condition whose referenced
certificate is missing or fails revalidation is immediately treated as unknown.

## 7. Capture-compromise transition

### 7.1 Planned admission of pass-through capacity

Before intentionally routing any pass-through process, the controller:

1. allocates the next recovery id;
2. durably sets `CaptureCoverageComplete=False`, `CaptureWorkflowTerminal=True`, `recoveryId`, and
   `nonCapturingSince`;
3. verifies that the status update succeeded; and
4. only then admits the minimum pass-through capacity required by availability policy.

If the status update fails or is ambiguous, pass-through is not admitted.

### 7.2 Active proxy capture failure

Terminal capture failure includes retry exhaustion, an ambiguous producer outcome, or unexpected
loss of an `ACTIVE` capture process. If the failed process cannot report its own transition, the
controller durably marks the resource incomplete and terminal from its workload and grant-ledger
evidence. It does not infer that capture remained complete merely because the process disappeared.

After `captureFailureRetryTimeout`, an otherwise-healthy proxy reports
`CAPTURE_FAILURE_PENDING` and keeps uncaptured forwarding blocked. The controller chooses:

**Same-process handoff**

1. Durably set the incomplete and terminal conditions and recovery id.
2. Send `authorizeCompromisedPassThrough(recoveryId)`.
3. The proxy validates the id, closes the writer activation permanently, leaves membership,
   converts existing connections to non-capturing, and opens the pass-through gate.
4. The process remains forwarding-only and must later terminate.

**Replacement-process handoff**

1. Durably set the incomplete and terminal conditions and recovery id.
2. Start or select a process that booted suppressed.
3. Admit that process as pass-through.
4. Remove and terminate the failed writer.

Updating the resource to incomplete and then failing to admit pass-through is conservative. Opening
pass-through before the durable update is forbidden. If controller authorization times out, the
proxy remains blocked or exits according to configuration.

For the current round, either handoff makes the capture resource terminal. It may continue reporting
and controlling failed-run pass-through capacity, but it cannot issue another capture grant, emit a
fleet reset, establish coverage, or authorize a source snapshot. A fresh workflow uses a new
resource UID and new run identity.

The fresh workflow cannot declare capture ready while a failed-run pass-through workload can still
receive or forward source traffic. Those workloads must drain, terminate, or be fenced under §8.3.
Establishing that the source is ready for the fresh workflow's normal snapshot procedure remains an
external restart precondition in this round.

### 7.3 Controlled replacement without capture failure

A controlled scale-down or clean replacement does not compromise capture if:

- no terminal capture failure occurred;
- it never forwarded uncaptured traffic;
- it is removed from routing or proven terminated;
- every remaining traffic-affecting process is strict; and
- sufficient strict capacity exists without admitting pass-through.

This is ordinary strict-capacity replacement. It does not require a new capture session or source
snapshot.

## 8. Suppression and old-writer retirement

### 8.1 Healthy suppression

A proxy may acknowledge `CAPTURE_SUPPRESSED_AND_QUIESCENT` only when:

1. capture admission for the old writer is irreversibly closed;
2. no new traffic or manifest submission can enter its publisher;
3. every previously accepted producer send completed successfully;
4. the ordered publisher lane and producer accumulator are empty;
5. the producer is closed;
6. every connection is permanently non-capturing or closed; and
7. the writer left membership and will never rejoin under that writer node id.

This is a strong local proof and may allow a healthy process to receive a later capture grant with
a fresh writer node id and capture session.

### 8.2 Failed or ambiguous producer

A process with a failed or ambiguous send cannot acknowledge healthy suppression. Stopping new
submissions is not proof that all old Kafka requests disappeared.

For fleet reset, the controller may treat the old writer activation as capture-retired only after:

- the one-way writer latch is irreversibly closed;
- no new capture submission can enter locally;
- the writer left membership;
- the grant ledger forbids another grant to that process activation; and
- every later old-session Kafka record will be rejected by the session gate.

An otherwise-healthy process may remain alive as authorized pass-through while coverage is
incomplete. If the process cannot reliably report or enforce the closed latch, the controller
terminates the actual process, container, or host and confirms termination rather than merely
observing API-object deletion.

Process death proves that no new application sends originate afterward. It does not retract an old
Kafka request already outside the process. `producerMaxBlockTime`, `producerDeliveryTimeout`, and a
safety margin may be useful operational waits, but they are not the correctness proof for rejecting
late Kafka records.

The capture-session rule supplies that proof: after reset to session S, every record carrying an
older session is discarded and alarmed, even when its writer node id was never previously observed
on that partition.

### 8.3 Kubernetes traffic retirement and fencing

Capture retirement and traffic retirement are separate obligations. Sections 8.1 and 8.2 retire an
old writer activation so that it cannot contribute accepted records to the new session. This
section proves that the associated Kubernetes workload can no longer forward source traffic from
the old interval. Traffic retirement gates coverage establishment and the source snapshot; it does
not need to delay the per-partition reset once every old writer activation is capture-retired.

Kubernetes routing and object-lifecycle signals are not traffic-retirement proof:

- removing a Pod from a Service or making it unready stops ordinary new routing but does not close
  existing connections;
- a Pod deletion timestamp, terminating EndpointSlice entry, replacement Pod, or absent Pod object
  does not by itself prove that the old process stopped;
- force deletion is never accepted as process-termination evidence; and
- a `NotReady` or unreachable node is not assumed dead after a timeout.

The controller may mark one workload traffic-retired only after recording one of:

1. **Trusted local quiescence after healthy suppression:** the authenticated process with the exact
   Pod UID, `processId`, resource UID, recovery, session, and activation reports its one-way
   old-writer latch closed, source listener closed, no source-affecting connections or queued
   submissions, publisher quiescent, and old membership left. The controller binds that report to
   the container ID and node UID already recorded in the grant ledger. The process may remain alive
   and later receive a fresh activation as permitted by §4.2.
2. **Runtime-confirmed termination:** fresh evidence from the kubelet or container runtime for the
   exact Pod UID and container ID on a reachable node confirms that the container exited.
3. **Infrastructure fencing:** the node or underlying compute instance is powered off, terminated,
   or fenced by a mechanism that terminates existing connectivity and prevents the old process from
   reaching either Kafka or the source.

A process that entered `CAPTURE_FAILURE_PENDING` or `PASS_THROUGH_COMPROMISED` cannot use local
quiescence to become eligible for capture again. Under §4.2 it must have runtime-confirmed
termination or infrastructure fencing before a fresh workflow can declare capture ready. Future
same-resource recovery would require the same proof before returning coverage to true.

Pod-name reuse, ReplicaSet convergence, load-balancer removal, graceful-deletion timeout, and
controller observation timeout satisfy none of these cases. If the node or runtime is unreachable,
the workload remains traffic-affecting and blocks fresh-workflow readiness until infrastructure
fencing or equivalent trusted evidence succeeds.

The durable grant ledger records the evidence type, workload identity, observation time, and
controller recovery that accepted it. Deleting and recreating the Capture Replay resource with the
same name cannot reuse an older ledger: recovery identity includes the resource UID. A finalizer
normally retains the resource while unretired grants remain, but it is not the durable proof store.
If an administrator removes the finalizer, the surviving ledger or tombstone keeps those grants
unresolved. A recreated resource starts with coverage false or unknown and cannot adopt or discard
the old grants by name.

Controller leader election is an availability mechanism, not the command fence. Every mutation
to Capture Replay status uses an object-version precondition. Every proxy mutation carries resource
UID, target Pod UID and process id, `recoveryId`, `captureSessionId`, and capture activation number.
A paused former leader may resume, but its stale status write conflicts and its stale proxy command
is rejected.

### 8.4 Future recovery: source-side quiescence requirement

Kafka session fencing does not prove that a source request transmitted before process death cannot
finish later. Before capture coverage can become complete or a source snapshot can begin, the
workflow must define a conservative source-side retirement condition for:

- a request fully transmitted to the source but not yet completed;
- a source response in flight when the proxy is killed; and
- force-closed long-lived connections.

Acceptable proof must be source-specific, for example:

- a source-side barrier whose completion is ordered after every earlier operation from the retired
  proxy population;
- source-visible request or session identities plus an authoritative query proving that no old
  operation remains executable; or
- a source-enforced maximum operation lifetime followed by the complete enforced interval after
  the last possible old-process send.

Proxy connection closure, Pod termination, a `preStop` hook, `terminationGracePeriodSeconds`, or a
convenient controller timeout is not sufficient. If the source protocol or proxy cannot prove
completion, cancellation, or an enforced bound, recovery remains incomplete.

This proof is required before future automatic recovery may return coverage to true. It is not part
of the current round because the failed workflow never returns to capture and never authorizes a
replacement snapshot. Starting the next full workflow is an external restart boundary whose source
readiness procedure remains unchanged in this round.

## 9. Future recovery: fleet reset and coverage establishment

### 9.1 Reset

After every prior writer activation is capture-retired under §8.1 or §8.2 and the controller has
frozen the old grant ledger:

1. allocate the new `captureSessionId`;
2. select a healthy Kafka-capable control-record producer;
3. write `FleetCaptureReset{recoveryId, captureSessionId}` once to every traffic partition;
4. retain and retry every incomplete partition obligation and durably record each acknowledged
   reset offset; and
5. issue no new capture grant until every reset copy is acknowledged.

The reset establishes the accepted session for each partition. It does not reconstruct source
traffic from the abandoned interval.

A delayed record from an old session that lands after the reset is discarded and alarmed. This
applies even if the old `writerNodeId` had never appeared on that partition.

Authorized pass-through processes and their existing connections may still be forwarding while the
condition is false. They do not block reset once their old capture activation is permanently
closed, because every one of their source mutations remains part of the incomplete interval. They
do block coverage establishment and the next source snapshot.

### 9.2 New capture grants

After reset fan-out completes, the controller grants the new session to eligible processes. Each
process creates a fresh writer node id and follows the base PROBATIONARY-to-ACTIVE admission
protocol, including capability probing and assignment admission.

New connections use strict capture only after activation. Existing pass-through connections never
upgrade in place; they drain or are force-closed.

### 9.3 Coverage establishment

Only after every remaining traffic-affecting process is strict in the current session, every old
workload is traffic-retired under §8.3, and all non-capturing connections are gone may the
controller write
`CaptureCoverageEstablished{recoveryId, captureSessionId}` to every partition.

This record settles no connection and repairs no missing traffic. It states that the controller
had already established complete capture for the named session before that partition offset.

The custom-resource condition becomes true only after:

- every partition copy is acknowledged;
- the recovery and session remain current;
- the complete population is revalidated; and
- no new compromise occurred during fan-out.

Partial fan-out remains incomplete and retries idempotently.

## 10. Future recovery: replayer rules and bootstrap

### 10.1 Session gate

A managed replayer is created with one expected `captureSessionId`. For every writer-scoped record:

- matching session: process under the base replayer rules;
- older or otherwise different session: discard, advance, and emit a high-severity alarm;
- missing session in a managed run: fail closed as protocol-incompatible.

The replayer does not parse writer node ids or compare numeric epochs.

### 10.2 Reset encountered by an existing replay run

A replay run based on a snapshot from session S1 must not silently cross
`FleetCaptureReset{captureSessionId=S2}`. The uncaptured interval between sessions requires a new
source snapshot.

Therefore an existing S1 replay run that encounters the S2 reset terminates as superseded or
invalidated. It does not settle old state and continue into S2.

### 10.3 Trusted replay plan

The snapshot workflow durably creates:

```
snapshotId
captureSessionId
trafficTopic
resetOffsetByPartition
partitionStartOffsets
coverageEstablishedOffsetByPartition
completenessCertificateRevision
recoveryId
```

For each partition, `partitionStartOffsets[P]` is the first offset after that partition's
acknowledged `FleetCaptureReset`, derived as `resetOffsetByPartition[P] + 1`. It is fixed before any
new-session capture grant is issued and is never moved forward to snapshot start or completion.
This deliberately replays every matching-session request captured after reset, including requests
whose effects may already appear in the source snapshot. Such duplication is compatible with the
system's at-least-once contract; moving the offset forward could omit a request captured before the
snapshot cut but forwarded to the source afterward.

The replayer receives this plan as trusted bootstrap. It need not have personally consumed the
earlier reset or coverage-establishment records when its configured start offsets are later.

The replayer must not adopt the first capture session it sees. A delayed old record could be first.
If neither a trusted replay plan nor an observed authoritative reset establishes the session, the
replayer fails closed.

Replayer checkpoints persist the expected capture session. Restart never re-infers it from retained
traffic.

### 10.4 Kafka retention

Traffic-topic retention may delete old reset and coverage-establishment records before a new
replayer starts. The durable replay plan is therefore required for long-lived snapshots and
restarts. Periodically re-emitting a boundary record is diagnostic redundancy, not a substitute for
trusted snapshot metadata.

## 11. Future recovery: snapshot workflow

A source snapshot is usable only when:

1. `CaptureCoverageComplete=True`;
2. the workflow records the current recovery and capture session;
3. the complete-population check succeeds immediately before snapshot start;
4. the condition and session remain unchanged throughout the snapshot; and
5. the complete-population check succeeds again after snapshot completion.

If capture becomes false or unknown, the recovery/session changes, any granted process becomes
unreachable, or the traffic-affecting inventory changes ambiguously, reject the snapshot.

The accepted snapshot manifest stores the replay plan from §10.3. That manifest, rather than the
current custom-resource condition at some later time, identifies the session the replayer may
consume.

The replay plan always starts immediately after the reset barriers, not at a Kafka position sampled
during snapshot creation. Therefore new-session requests racing the snapshot are replayed rather
than omitted. The snapshot workflow may create duplicates, but it cannot create a hole by advancing
the replay start past already captured work.

## 12. Future automatic recovery workflow

For an incident that actually forwarded uncaptured traffic:

1. A proxy enters `CAPTURE_RETRYING`; forwarding remains strict and blocked as needed.
2. Retry is exhausted; the proxy enters `CAPTURE_FAILURE_PENDING`.
3. The controller allocates recovery R and durably sets coverage false.
4. The controller authorizes same-process or replacement-process pass-through if availability
   policy requires it.
5. Strict replacement capacity is started and probed.
6. Every previous writer activation is capture-retired by healthy suppression or the permanent
   failed-writer rules under §8.1 and §8.2.
7. The controller creates session S and fans `FleetCaptureReset{R,S}` to every partition.
8. After all reset acknowledgements, the controller grants S to eligible proxies.
9. Proxies become ACTIVE under the base capability and assignment-admission protocol.
10. Remaining pass-through processes and off-load-balancer connections become traffic-retired
    under §8.3.
11. Source-side in-flight retirement is established under §8.4.
12. The controller fans `CaptureCoverageEstablished{R,S}` to every partition.
13. After all acknowledgements and revalidation, the resource becomes complete.
14. A new source snapshot is taken and bound to S.
15. A new replayer run starts from that snapshot's trusted replay plan.

At every stage, failure leaves the condition false or unknown. No timeout skips a proof.

## 13. Implementation deltas

The terminal-on-error controller behavior, immutable Kubernetes identity, command fencing, and
durable failed condition belong to the current round. Session reset, coverage re-establishment,
trusted replay plans, and replacement-snapshot automation are future deltas.

| Change | Scope | Required behavior |
|---|---|---|
| Managed configuration | Current | Add `suppressCaptureByDefault`, controller-authorized fallback, retry, authorization, drain, and handoff settings. |
| Separate status/control interface | Current | Expose local state and authenticated idempotent commands without sharing the source listener. |
| Capture Replay status | Current | Add complete/false/unknown coverage, immutable terminal-workflow status, recovery identity, and transition timestamps. |
| Durable grant ledger | Current | Persist every process, writer, grant, and retirement state across controller failover. |
| Completeness certificate | Current | Persist immutable evidence before atomically publishing its revision with `CaptureCoverageComplete=True`; failover treats missing or invalid evidence as unknown. |
| Traffic-affecting inventory | Current | Track load-balanced processes, draining processes, off-LB live connections, and unreachable grants. |
| Kubernetes workload identity | Current | Track Pod UID, container ID, node UID, and process identity; never retire by Pod name or replacement readiness. |
| Kubernetes fencing | Current | Prove traffic retirement through trusted healthy quiescence, runtime-confirmed termination, or infrastructure fencing that cuts existing connectivity; force deletion and node timeout are insufficient. |
| Controller command fence | Current | Target immutable Pod and process identity and reject stale resource, recovery, and activation commands independently of leader election. |
| Record schema | Future recovery | Carry `captureSessionId` on traffic, manifests, and writer completion; add reset and coverage-establishment records. |
| Proxy state machine | Current | Implement retry, pending authorization, same-process pass-through, healthy suppression, and permanent failed-activation rules. |
| Session-aware replayer | Future recovery | Enforce the expected session, reject late old-session records, persist bootstrap, and invalidate a run on a later reset. |
| Snapshot replay plan | Future recovery | Persist the accepted session and per-partition offsets with the source snapshot. |
| Capability probe | Current | Define and implement a real acknowledged capability proof rather than metadata connectivity. |
| Source retirement proof | Future recovery | Define how forced termination excludes source operations completing after the recovery boundary. |
| Security | Current | Authenticate and authorize every mutation and control-record emission. |
| Observability | Current and future | Export terminal failure, inventory, compromise, suppression, drain, and rejection metrics now; add session, fan-out, and replacement-snapshot metrics with future recovery. |

## 14. Failure boundaries

The terminal resource, Kubernetes identity, and stale-command boundaries below apply now. The
reset, session, and replay-plan boundaries apply only to future automatic recovery.

- Controller unavailability never authorizes pass-through or capture grants.
- Existing strict proxies may continue their already granted session while the controller is down.
- New processes remain suppressed without a grant.
- Unexpected loss of any `ACTIVE` capture process terminally fails the current workflow even when no
  uncaptured forwarding has yet been observed.
- A stale command cannot reactivate an older recovery or session.
- `CaptureWorkflowTerminal=True` is immutable; every later grant, reset, or coverage-establishment
  command for that resource UID is rejected.
- Load-balancer removal and Pod deletion are not process termination.
- Force-deleted Pods and unreachable nodes remain traffic-affecting until runtime evidence or
  infrastructure fencing is recorded.
- A fresh workflow cannot declare capture ready while failed-run pass-through capacity remains
  traffic-affecting.
- A stale controller that lost leadership cannot issue an accepted command for an older resource,
  recovery, session, or activation.
- A percentage below 100% can authorize capacity decisions but never complete capture.
- Simultaneous writer and final-manifest loss is not retroactively repaired. The current workflow
  explicitly abandons that incomplete interval and starts a fresh capture and snapshot workflow.

Future automatic recovery additionally requires:

- A reset acknowledged on only some partitions establishes nothing globally.
- Coverage establishment acknowledged on only some partitions keeps the resource incomplete.
- Late records from an old session are discarded and alarmed, including the first record from a
  previously unseen writer.
- A replay run never crosses from one capture session to another without a new snapshot.

## 15. Validation plan

Current implementation requires the terminal-failure, immutable-identity, stale-command,
force-deletion, and old-workload-retirement cases. Session reset, same-resource coverage recovery,
and replacement-snapshot cases are future tests.

### 15.1 Deterministic tests

Current round:

- stale, duplicate, and out-of-order controller commands;
- a paused former controller leader resuming after a newer recovery has started;
- Capture Replay deletion and recreation with the same name but a different resource UID;
- forced finalizer removal followed by reconstruction from the surviving unresolved-grant ledger;
- Pod IP reuse receiving a delayed command addressed to the previous Pod UID and process id;
- controller failure after certificate persistence but before the status compare-and-swap;
- failover observing a true condition whose referenced certificate is missing or invalid;
- retry success returning to ACTIVE before the failure decision;
- permanent writer closure after retry exhaustion;
- unexpected active-Pod loss terminally failing the workflow;
- terminal status remaining immutable after retry exhaustion;
- every same-resource grant, reset, and coverage-establishment command being rejected after terminal
  failure;
- a fresh resource remaining unready while failed-run pass-through capacity is traffic-affecting;
- same-process and replacement-process pass-through only after durable incomplete and terminal
  status;
- controller timeout causing block or exit, never autonomous degradation;
- healthy suppression acknowledgement only after every producer future succeeds;
- failed or ambiguous sends preventing healthy suppression;
- off-load-balancer live connections blocking completeness;
- unreachable processes producing false or unknown, never true.

Future automatic recovery:

- reset and coverage fan-out idempotence and partial failure;
- reset offsets becoming the immutable per-partition replay start offsets;
- session mismatch rejection for known and previously unseen writers;
- replayer bootstrap before and after retained boundary records;
- checkpoint restart preserving expected session; and
- later reset invalidating an older snapshot/replay run.

### 15.2 Future recovery: Testcontainers Kafka

- an old producer request appended after `FleetCaptureReset` and rejected by session;
- reset from a different producer overtaking an ambiguous old send;
- controller failover during every fan-out stage;
- broker restart during suppression, reset, activation, and coverage establishment;
- duplicate control records and independent partition barriers;
- all relevant writers and final manifests disappearing through the configured boundary;
- a request captured before snapshot start but forwarded after the snapshot cut remaining included
  because replay starts immediately after reset; and
- protocol-version rejection during the wire-format rollout.

### 15.3 Live deployment tests

Current round:

- controlled strict-proxy replacement with sufficient remaining capacity and no terminal capture
  failure;
- AZ capacity falling below policy and authoritative pass-through admission;
- same-process pass-through preserving existing connections;
- replacement-process handoff requiring client reconnect;
- an off-load-balancer hour-long connection blocking recovery until forced close;
- Pod deletion without actual process death;
- force deletion leaving the old process running;
- a node partition leaving the Pod and existing connections alive;
- Pod-name and StatefulSet-ordinal reuse while the old grant remains unretired;
- node or host fencing when process termination cannot be confirmed;
- controller restart with grant-ledger reconstruction.

Future automatic recovery:

- snapshot rejection when recovery changes mid-snapshot; and
- full recovery followed by a replayer launched after reset records have aged out, using only the
  durable replay plan.

## 16. Current implementation requirements and future recovery decisions

The current terminal-on-error Kubernetes implementation still requires:

1. Define the exact Kafka capability probe and its partition coverage.
2. Define durable terminal-status and grant-ledger storage plus controller failover semantics.
3. Finalize the Kubernetes termination-evidence adapters for the supported runtimes, durable
   unresolved-grant storage outside the custom-resource lifecycle, and the infrastructure-fencing
   operation for unreachable nodes.

Future automatic recovery within a failed workflow additionally requires:

1. Define the source-side in-flight retirement proof after forced process or host termination.
2. Finalize the `captureSessionId` wire fields and compatibility rollout.
3. Define replay-plan storage, retention, and checkpoint integration.
4. Decide whether unmanaged operators may manually provide a `captureSessionId`; if so, document
   that this is a manual new-run boundary rather than automatic recovery.

Until those decisions are complete, a terminal capture failure permanently leaves that resource
incomplete and terminal. The controller must not emit a reset, grant a replacement session, return
coverage to true, or authorize a new source snapshot under that resource. The operator or workflow
starts over with a fresh capture run.
