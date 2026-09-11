# Managed Fleet Capture Recovery

**Status: current terminal-failure contract plus future recovery design (2026-09-11).** The
standalone contract is
[Proxy Capture Protocol](proxyCaptureProtocol.md).
That contract now uses exact manifest-cycle boundaries, terminal self-only `NoMoreWrites` for
permanent `(writerNodeId, partition)` retirement, explicit retention when neither fact exists, and an
irreversible local capture gate. It has no designated witnesses, peer completion, peer terminal
cutoff, or partition-footprint dissemination.

This addendum extends those rules for a controller-managed Kubernetes fleet:

- `captureActivationId` identifies one capture-authoritative process lifetime, while every new
  Kafka group assignment increments `assignmentSequence` and creates the `writerNodeId` used by
  newly opened connections;
- Kafka membership only load-balances new connections; after the first usable assignment, the
  proxy retains its last usable assignment through revocation, partition loss, coordinator outage,
  and membership-poll failure until Kafka supplies a replacement;
- existing connections retain their original `writerNodeId`; old writer identities continue
  manifests while draining and finish with a final empty manifest plus self `NoMoreWrites`;
- `NoMoreWrites` is partition-scoped, self-emitted after the connection registry for that
  `(writerNodeId, partition)` is empty and the
  publisher retirement barrier has quiesced periodic manifest callbacks and acknowledged the final
  empty manifest, and terminal for that `(writerNodeId, partition)`; each closed connection completes
  the base connection-retirement protocol first, and the producer closes only after
  `NoMoreWrites` is acknowledged;
- the base protocol may expire already-known incomplete connection state from a skew-adjusted Kafka
  `LogAppendTime` horizon only while the fleet's declared clock-skew bound is healthy;
- every `TrafficObservation` and complete manifest carries the base protocol's
  per-writer-identity, per-partition `manifestCycle`;
- within the standalone design's non-streaming source-execution scope, strict mode captures a
  complete request before source execution and checks acknowledged-manifest freshness before
  forwarding;
- mutating-request classification hardening remains deferred as documented by the standalone
  design;
- producer configuration enforces idempotence, `acks=all`, and an ordering-preserving
  `max.in.flight.requests.per.connection`; deployment configuration cannot weaken those settings;
- only exact-registry add, remove, and manifest copy-and-increment use the standalone design's
  narrow partition-local lifecycle boundary; ordinary packet capture remains concurrent;
- `--capture-failure-policy=fail-closed` terminates immediately after Kafka publication or capture
  is compromised, without attempting orderly retirement;
- `--capture-failure-policy=fail-open` permanently abandons capture for the whole process, keeps
  existing TCP connections open, accepts new TCP connections without capture, and raises a
  persistent gap alarm;
- fatal replayer termination is alarmed from Kubernetes-observed container state and the
  reason-specific halt code; the replayer's final in-process OpenTelemetry metric remains
  best-effort; and
- an uncaptured source interval starts a new capture and replay run with a fresh source snapshot.

For the current round, any terminal capture failure after a permitted retry terminates the entire
capture workflow. A `fail-closed` proxy terminates immediately. A `fail-open` proxy immediately
makes its irreversible whole-process transition to uncaptured forwarding; it does not wait for
controller authorization. The controller durably marks the resource incomplete and terminal as
soon as it observes either outcome, never grants capture again under that resource UID, and never
returns its coverage condition to true. Recovery means starting a fresh capture, snapshot, and
replay workflow from an explicit isolated run boundary. The controller may keep failed-run
pass-through capacity alive for availability, but that capacity cannot participate in the
replacement capture run.

Automatic return to complete fleet coverage is future work. Session identity, a trusted replay plan,
and session-aware checkpoints are current requirements for every managed fresh run; per-partition
reset records are additionally current if the fresh workflow reuses the failed workflow's Kafka
topic. The source-side snapshot barrier is a current restart precondition either way. These
unresolved decisions do not block terminal-on-error behavior, but they do block automatic fresh-run
startup. Section 7.2 records the choice explicitly.

## 1. Goals

The current managed contract adds five capabilities:

1. Durably mark capture incomplete before intentionally forwarding uncaptured source traffic.
2. Determine whether every process still capable of affecting the source is capture-only.
3. Make controller failure conservative: stall, remain incomplete, or exit, but never silently
   assert complete capture.
4. Bind every managed fresh run to one `captureSessionId`, one immutable Kafka input boundary, one
   trusted replay-boundary plan, and session-aware checkpoints.
5. When a fresh run reuses a Kafka topic, establish that boundary with one immutable reset intent
   acknowledged on every partition.

Future recovery work would add two automation capabilities:

1. Drive a fleet automatically through retirement, fresh-session activation, source-side
   quiescence, snapshot creation, and replay startup after an uncaptured interval.
2. Restore and publish complete fleet coverage automatically, including optional same-resource
   recovery if a reviewed nonterminal resource state is introduced.

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
- A delayed record from an old capture activation can first appear on a partition after a new fleet
  boundary. An opaque, previously unseen writer node id is insufficient to classify it.
- A replayer starting after Kafka retention removed an earlier boundary record cannot safely infer
  the current capture generation from the first traffic record it sees.
- Simultaneous loss of the relevant capture activations and their final manifests has no ordinary
  settlement path. Automated recovery must explicitly abandon the incomplete interval rather than
  infer that replacement capacity observed the old activations.

These gaps require an external authority plus an explicit data-plane session identity. A process
timeout alone is not that authority.

### 2.1 Kafka broker clock-skew requirement

The base protocol's broker-time expiration proof depends on an enforced bound `S`: the maximum
permitted backward movement between Kafka `LogAppendTime` values at increasing offsets in one
partition. This is an operational safety dependency, not merely a monitoring preference.

The orchestration layer supplies one agreed manifest expiration interval `E` and one agreed
clock-skew bound `S` to every proxy and replayer in the run, and configures the broker-node clock
monitor against that same `S`. The timestamp proof is valid only while those process parameters
agree.

Every Kafka broker node must run a node-level clock monitor, preferably Node Problem Detector or an
equivalent DaemonSet. The deployment must enforce all of the following:

- Kafka broker startup is prohibited while the node clock is unhealthy.
- The alarm and local broker-termination threshold is lower than `S`, leaving margin for detection
  and shutdown before the declared bound can be violated.
- Excessive clock skew stops the broker locally without waiting for control-plane reconciliation.
- The node reports a custom `ClockSkew` condition and is cordoned, tainted, and replaced.
- The monitor does not overwrite the kubelet-owned `Ready` condition.
- An optional Kafka-pod sidecar may provide faster local broker termination, but node-health
  reporting remains the node-level monitor's responsibility.

If `N` is the maximum pairwise broker skew, use `S = N`. If every broker clock may independently
differ from trusted time by `±N`, use `S = 2N`.

If the fleet cannot attest that the bound is healthy, or observes that it was violated, the
replayer stops making broker-time expiration decisions for the affected Kafka input, retains the
affected incomplete records, and raises a high-severity alarm. It must not continue committing
incomplete state using an invalid timestamp proof. Replay processing that does not depend on that
proof may continue.

### 2.2 Replayer fatal-termination alarming

The replayer cannot guarantee export of a final OpenTelemetry metric before
`Runtime.halt()`. Updating an in-process metric only records the point in the local telemetry SDK;
an exporter or external scraper may not observe it before the process disappears. The fatal
handler still attempts
`replayFatalFailures{reason=event_loop_terminated}` before logging and termination, but fleet
alarming does not depend upon that attempt succeeding or being exported.

Fatal event-loop-owner loss uses `Runtime.halt(80)`. Code `80` is reserved for that reason and is
distinct from the replayer's normal `System.exit` codes. Kubernetes observes this as a container
termination. For a workload whose Pod restart policy restarts the container, the Pod normally
remains while the kubelet records the terminated container state and increments its restart count;
this is a container termination and restart, not necessarily Pod deletion.

Every managed deployment runs kube-state-metrics or an equivalent independent observer and exports:

- `kube_pod_container_status_restarts_total`;
- `kube_pod_container_status_last_terminated_exitcode`; and
- when available, `kube_pod_container_status_last_terminated_timestamp`.

The last-termination metrics are version-dependent kube-state-metrics interfaces and must be
verified during deployment qualification. The stable, general alarm is any unexpected replayer
container restart. A second reason-specific alarm correlates a recent restart with exit code `80`.
For example:

```promql
(
  increase(kube_pod_container_status_restarts_total{
    namespace="migrations",
    container="traffic-replayer"
  }[5m]) > 0
)
and on (namespace, pod, container)
(
  kube_pod_container_status_last_terminated_exitcode{
    namespace="migrations",
    container="traffic-replayer"
  } == 80
)
```

The alert has no waiting period and remains firing long enough for operators and automation to
observe it after the five-minute query window. Namespace, workload, and container selectors are
deployment parameters rather than protocol constants.

This removes dependence on the dying JVM, but a pull-based metrics system still cannot guarantee
that every termination is observed if the monitoring system is unavailable or the Pod object
disappears before collection. A strict no-loss requirement needs an external controller that
watches Kubernetes container-status transitions and persists each termination event to durable
storage. Whether the managed-fleet controller must provide that durable event journal, and the
storage and acknowledgement contract for it, remain unresolved; the metrics alarms do not claim to
provide that guarantee.

## 3. Terminology and identities

The managed protocol uses distinct identities for distinct purposes.

### 3.1 `processId`

Identifies one running proxy process to the controller and deployment platform. It is fresh on
every process start.

### 3.2 Capture activation and writer identities

`captureActivationId` identifies one capture-authoritative lifetime of a proxy process. It is fresh
for every new capture activation and is never reused after capture authority is abandoned.

`assignmentSequence` is a process-local, strictly increasing value. The proxy increments it for
every new Kafka group assignment before accepting a connection under that assignment. Newly opened
connections use:

```text
writerNodeId = captureActivationId + ":" + assignmentSequence
```

Every new assignment creates a `writerNodeId` that cannot be reused within the
`captureActivationId`. Existing connections permanently retain their original `writerNodeId`.
Rapid rebalances may therefore leave several writer identities draining concurrently in one process.
Every connection registry, manifest cycle, publisher lane, broker-time baseline, and
`NoMoreWrites` lifecycle is keyed by `(writerNodeId, partition)`.

Each `(writerNodeId, partition)` has one continuous accepted-manifest broker-time baseline. Empty
manifests for an active identity remain ordinary heartbeats and update that baseline when timely.
They never end or reset it. After the identity's connections drain, its final empty manifest and
valid `NoMoreWrites` permanently retire that identity and partition. A late manifest permanently
compromises the process; serialized manifest publication prevents a later manifest from being
submitted to restore freshness.

The Kafka reachability probe runs before a group generation exists and uses:

```text
writerNodeId = captureActivationId + ":PROBE"
```

`CaptureCapabilityProbe` confirms only that the proxy can publish to Kafka. It has no replay
meaning. The replayer never treats its `writerNodeId` as a traffic identity, manifest owner, or
source of connection state.

### 3.3 `captureDomainId`

Identifies one source dataset and its capture-stream lineage across Capture Replay resource
deletion, recreation, and Kafka topic changes. It is a globally unique opaque value minted exactly
once by a linearizable capture-domain authority and stored outside the custom-resource lifecycle.
That authority maintains a compare-and-set-protected one-to-one binding from the deployment's
canonical `sourceIdentity` to `captureDomainId`. A resource name, topic name, endpoint alias, or
new controller instance cannot create a second active domain for the same source lineage.

If a deployment cannot define a canonical source identity and enforce that unique durable binding,
managed fresh-run recovery is not authorized: separate resources could otherwise grant capture
concurrently while each believed it owned a different domain.

### 3.4 `recoveryId`

Identifies one idempotent controller recovery workflow. It is composed from the Capture Replay
resource identity and a durably allocated, monotonically increasing
`captureDomainRecoverySequence`. The sequence is allocated with a compare-and-set in the
capture-domain authority store, so it is ordered across resource deletion and recreation. It
prevents stale control commands from reactivating a superseded workflow.

### 3.5 `captureSessionId`

Session-fenced recovery uses this opaque identity for one Kafka record namespace/run. The session
begins before fleet coverage is established and may include an initial incomplete period, so it is
not called a “complete-capture interval.” It is derived from, or equal to, the recovery id that
established that run. It is required for every controller-managed run, including a run that uses a
distinct immutable Kafka topic. Topic isolation can remove the need for reset offsets; it does not
create a second sessionless managed profile.

`captureSessionId` is the load-bearing wire identity that prevents delayed old traffic from being
accepted after fleet recovery. It is not Kafka's producer epoch, is not an assignment revision,
and is not inferred from `writerNodeId`.

### 3.6 `captureDomainFenceToken`

Fences controller authority within one capture domain, including two controller leaders acting on
the same resource, recovery, session, and activation. The linearizable capture-domain authority
allocates a strictly increasing token whenever control authority is acquired or transferred. Every
external mutation carries that token and a reference to an immutable command intent durably
recorded under the token.

The receiving proxy or control-record publisher validates the intent and token with a linearizable
read from the current domain authority before performing the mutation. If the authority is
unavailable, the mutation remains blocked. The receiver rejects a lower token and accepts a higher
token only after that read; merely hearing from a controller that claims a higher token is
insufficient. It also persists the highest validated token as a local defense. This closes the
paused-former-leader hole even when both controllers use the same recovery and session values. An
exact retry of an already authorized immutable intent remains idempotent.

### 3.7 Traffic-affecting process

A process is traffic-affecting if it:

- can receive a new source connection;
- owns an existing client or source-side connection;
- may still forward a previously accepted source operation; or
- is unreachable and has not been durably retired or fenced.

Fleet completeness is evaluated over this set, not just load-balancer endpoints or desired
replicas.

### 3.8 Kubernetes workload identity

The controller identifies a running process by immutable workload identity, not by Pod name:

```text
cluster identity
namespace
Pod UID
container ID
node UID
processId
captureActivationId
```

A replacement Pod with the same Deployment, StatefulSet ordinal, labels, IP address, or name is a
different process. Generation-scoped writer identities are data-plane identities owned by this
workload; rapid rebalances may give one workload several draining writer identities. The grant
ledger retains the old workload identity and all of its writer identities until their retirement
proof is durable.

## 4. Required proxy changes

### 4.1 Configuration

Controller-managed processes use:

```
suppressCaptureByDefault = true
captureFailureRetryTimeout
--capture-failure-policy = fail-closed | fail-open
```

`suppressCaptureByDefault=true` is stable deployment configuration. The controller does not toggle
it during each incident. Every replacement therefore boots suppressed even if it missed prior
commands.

The maximum whole-connection lifetime defaults to 60 minutes. Planned retirement performed while
capture and Kafka publication remain trustworthy has a five-minute default completion bound.

The proxy may retry Kafka while in `CAPTURE_RETRYING` only for a definite transient failure known
not to have compromised capture. All affected source forwarding remains capture-before-forward
during that interval. The proxy may return to `CAPTURE_AUTHORITATIVE` only after the base capability
probe succeeds again and every capture and publisher-health invariant is revalidated.

A manifest lapse, ambiguous producer outcome, inability to publish a record required for capture,
or any other compromise closes the capture activation immediately and applies the configured
process-wide capture failure policy. That process may never return to
`CAPTURE_AUTHORITATIVE`. Recovery requires a fresh process and fresh `captureActivationId`. Retry
exhaustion likewise permanently closes the activation.

### 4.2 Local states

The managed outer lifecycle is:

```
SUPPRESSED
  -> PROBING
  -> CAPTURE_AUTHORITATIVE
  -> CAPTURE_RETRYING
       -> CAPTURE_AUTHORITATIVE          // only a definite uncompromised transient failure recovered
       -> process exit                   // fail-closed after capture compromise
       -> PASS_THROUGH_COMPROMISED       // fail-open after capture compromise
```

A healthy controller-requested suppression has a separate path:

```
CAPTURE_AUTHORITATIVE -> SUPPRESSING -> CAPTURE_SUPPRESSED_AND_QUIESCENT
```

`CAPTURE_AUTHORITATIVE` is a local process state, not Kafka group subscription metadata. After
capability probing succeeds, the process joins the group and uses its first completed assignment.
After that, revocation, partition-loss callbacks, empty polls, coordinator outages, delayed
heartbeats, and polling failures do not change capture state or stop connection acceptance. The
proxy retains its last usable assignment until Kafka supplies a replacement.

Only the healthy suppression path may later create a fresh capture activation in the same process.
A process that entered `PASS_THROUGH_COMPROMISED` because of manifest lapse, ambiguous producer
outcome, or another compromise never captures again. It must terminate before recovery can become
complete.

### 4.3 Status and control interface

Every proxy exposes a port that is not used for source forwarding. Read-only status includes:

```
processId
captureActivationId, if capture authority has been created
assignmentSequence, if an assignment has been received
currentWriterNodeId, if the last usable assignment may accept connections
drainingWriterNodeIds
podUid
captureReplayResourceUid
captureDomainId
recoveryId
captureDomainRecoverySequence
captureDomainFenceToken
captureSessionId
trafficKafkaClusterId
trafficTopicId
replayBoundaryPlanId
captureActivationNumber
captureOperatingState
captureCapability and latestProbeResult
captureFailureCause and captureFailureRetryAge
forwardingReady
sourceListenerAccepting
captureReady
newConnectionCaptureSuppressed
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
grantInitialCapture(recoveryId, captureActivationNumber)
forceCloseNonCapturingConnections(recoveryId)
```

Same-topic fresh-run reset requires the reset emitter. Future automatic coverage restoration and
same-resource regrant additionally require the other endpoints:

```
grantCapture(recoveryId, captureSessionId, captureActivationNumber)
emitFleetCaptureReset(resetIntentId, exactPersistedPayload)
emitCaptureCoverageEstablished(coverageIntentId, exactPersistedPayload)
```

Every mutation also carries a common command envelope:

```
targetPodUid
targetProcessId
captureReplayResourceUid
captureDomainId
recoveryId
captureDomainRecoverySequence
captureDomainFenceToken
expectedCaptureActivationNumber
commandId
commandIntentId
commandIntentHash
```

Session-changing commands additionally carry `captureSessionId`, immutable Kafka cluster/topic
IDs, `replayBoundaryPlanId`, and the new activation number. The proxy compares the target Pod UID
and process id with its immutable local identity and rejects a command for a reused Pod name, IP
address, or earlier process. Once bound, it rejects a different resource UID and rejects stale
recovery, session, activation, or domain-fence values. Before an irreversible mutation, it
validates that the immutable command intent and hash are authorized by the current
`captureDomainFenceToken` in the domain authority. It handles an exact duplicate `commandId`
idempotently.

The control-record endpoints never construct a reset or coverage payload from a few caller-supplied
ids. They accept only the exact bytes and hash from an immutable persisted intent, validate that
intent's current domain authorization, and retry those same bytes after an ambiguous send. A
leadership change may claim the remaining partition obligations under a newer fence token, but it
does not alter the persisted wire payload.

Readiness probes cannot call mutation endpoints. Network policy, transport authentication, and
request authorization restrict mutations to the workflow controller.

### 4.4 Capture capability

The managed fleet uses the exact base capability probe rather than defining a second meaning:

1. refresh traffic-topic metadata;
2. choose one representative partition for every distinct current leader broker;
3. publish a semantically inert `CaptureCapabilityProbe` carrying
   `writerNodeId = captureActivationId + ":PROBE"` to each representative;
4. require acknowledgement from every probe;
5. validate the effective producer idempotence, `acks`, and in-flight settings; and
6. refresh metadata again before joining the proxy group.

`captureCapability=true` means that complete sequence succeeded for the reported activation and
metadata view. A metadata lookup or TCP connection alone is insufficient. The probe is a necessary
precondition for capture eligibility, not for the controller's earlier session binding and probe
authorization. It is not a durability promise: normal publication failure still closes the capture
gate. The probe creates no replayer writer baseline, manifest state, or connection state. After a
group assignment completes, before accepting a captured client connection on an assigned
partition, the proxy also satisfies the base initial-manifest acknowledgement rule for the current
generation's `writerNodeId` and that partition.

## 5. Managed session fencing: data-plane record changes

Every session-fenced controller-managed record carries the capture session of the emitting capture
activation:

```
TrafficRecord {
    writerNodeId
    captureSessionId
    connectionId
    partition
    observations[] {
        connectionObservationSequence
        manifestCycle
        ...
    }
}

LivenessSnapshotChunk {
    writerNodeId
    captureSessionId
    partition
    manifestCycle
    chunkIndex
    chunkCount
    connectionIds[]
}

NoMoreWrites {
    captureSessionId
    partition
}

CaptureCapabilityProbe {
    writerNodeId = captureActivationId + ":PROBE"
    captureSessionId
    probeId
}
```

The Kafka record header for `NoMoreWrites` carries its authoritative assignment-scoped
`writerNodeId`. A missing or malformed header makes the record inert; a compatibility field in the
protobuf body cannot override it.

The session field is optional only for unmanaged legacy mode. A controller-managed process fails
startup if the configured wire version cannot carry it. `manifestCycle` keeps the exact meaning
defined by the base protocol; `captureSessionId` does not replace or reset it. One traffic record is
homogeneous in writer identity, session, partition, and connection, but its observations may carry
different manifest cycles. Replay intake creates one deterministic child obligation per
observation after fully decoding and validating the parent. Manifest decisions satisfy only
covered incomplete-accumulator WorkClaims; transaction or other claims beneath the same child may
remain pending. The parent Kafka offset becomes commit-eligible only after every child is terminal
`Satisfied`. Flushing when the cycle changes is an optional batching optimization, not a
correctness requirement. Flushed and mixed batching preserve per-observation semantics and
WorkClaim decisions without loss or duplication, but may have different parent-record identities,
commit timing, and redelivery granularity.

A fresh run that reuses a Kafka topic additionally requires the partition-scoped
`FleetCaptureReset` record. Future automatic coverage restoration also adds
`CaptureCoverageEstablished`:

```
FleetCaptureReset {
    captureDomainId
    recoveryId
    captureDomainRecoverySequence
    captureSessionId
    resetIntentId
}

CaptureCoverageEstablished {
    captureDomainId
    recoveryId
    captureDomainRecoverySequence
    captureSessionId
    coverageIntentId
}
```

Each control record is written independently to every traffic partition and acknowledged on every
partition before the controller advances. Timestamps may be included for diagnostics, but they do
not establish ordering or authority. On normal replay, each valid control record creates exactly
one control child. That child becomes `Satisfied` only after the record's validation and semantic
effect complete; the parent Kafka offset remains indivisible and follows the ordinary parent
disposition path.

`FleetCaptureReset` and `CaptureCoverageEstablished` are record names. “Reset marker” and “coverage
marker” are informal shorthand and should not appear in protocol APIs.

Adding the session field or control records is a wire-level change. Per the base document's rollout
rule, incompatible binaries never coexist as capture-group members. Deployment first enters an
explicit no-capture interval, empties the old group, rolls all binaries, and then starts a new cold
cohort.

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
  captureDomainId: ...
  recoveryId: ...
  captureDomainRecoverySequence: ...
  captureDomainFenceToken: ...
  captureSessionId: ...
  trafficKafkaClusterId: ...
  trafficTopicId: ...
  replayBoundaryPlanId: ...
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
The failed terminal resource may leave `captureSessionId` and `captureRecoveryTimestamp` unset if it
never established a managed capture run. Every fresh managed resource sets a new
`captureSessionId` before granting capture, regardless of whether it uses the same or a distinct
Kafka topic.
`lastTransitionTime`, `nonCapturingSince`, and `captureRecoveryTimestamp` are control-plane audit
times. They never enter broker-time liveness or expiration calculations.

The future same-resource workflow in §12 is not compatible with
`CaptureWorkflowTerminal=True` remaining immutable. Before implementing that workflow, choose and
document one resource model:

1. every recovery creates a new resource and leaves the failed resource terminal forever; or
2. introduce a distinct nonterminal `RECOVERING` state and define the exact transition that remains
   eligible for reset and regrant before a resource becomes terminal.

This document does not authorize reset, regrant, or coverage restoration on a resource already
marked terminal.

### 6.2 Grant ledger

The controller durably records every capture grant:

```
captureDomainId
sourceIdentity
trafficKafkaClusterId
trafficTopicId
captureReplayResourceUid
processId
writerNodeId
podUid
containerId
nodeUid
recoveryId
captureDomainRecoverySequence
captureDomainFenceToken
captureSessionId
replayBoundaryPlanId
captureActivationNumber
grantTime
lastObservedState
retirementState
```

`captureDomainId` has the unique, authority-issued meaning defined in §3.3. `trafficTopicId` is
Kafka's immutable topic identity, not only its reusable name.

The ledger is the inventory of capture activations that must be retired before recovery. Queries for
unresolved predecessors are keyed by `captureDomainId` across **all** topic identities, not only by
Capture Replay resource UID. Topic identity classifies the record namespace; it must not hide an
unresolved grant merely because a fresh run chose a new topic. Replica count, current group
membership, Pod objects, load-balancer endpoints, and a newly created resource cannot reconstruct
this history after failures. Durable storage cannot depend solely on the lifecycle of the custom
resource. The ledger or an equivalent unresolved-grant tombstone must survive resource deletion,
forced finalizer removal, and recreation with the same name.

The same domain store owns the lease/CAS fence for capture grants, allocation of the next
`captureDomainRecoverySequence`, and allocation of `captureDomainFenceToken`. Two resource UIDs
cannot concurrently grant capture for one domain, even if both controllers are individually
healthy. Every durable command intent records the resource, recovery sequence, current fence token,
target identity, exact command payload or hash, and idempotence key before the command is issued.

### 6.3 Completeness population

For the current round, `CaptureCoverageComplete=True` requires:

- `CaptureWorkflowTerminal=False`;
- status identifies the current capture domain, recovery sequence, session, immutable Kafka
  cluster/topic IDs, and replay-boundary plan;
- every traffic-affecting live process reports the current resource UID, recovery, session,
  immutable Kafka identities, replay-boundary plan, and activation;
- every process able to forward source traffic is capture-only;
- every non-capturing connection count is zero;
- every older grant is durably retired or belongs to a workload that cannot affect source traffic;
  and
- every required protocol and binary version is compatible.

Because terminal status is immutable, this transition is available only for initial healthy startup
and controlled replacement that never crossed the terminal failure boundary.

Future same-resource recovery additionally requires:

- every old capture activation has durable capture-retirement proof under §8.1 or §8.2;
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
completeness certificate containing the resource UID, `captureDomainId`, recovery id and sequence,
`captureSessionId`, immutable Kafka cluster/topic IDs, replay-boundary plan id, grant-ledger
revision, traffic-affecting workload inventory, and retirement-evidence references. A future
automatic-recovery certificate also contains the source-side retirement proof and acknowledged
coverage-establishment offsets.

Every status mutation, including terminal failure, incomplete coverage, and complete coverage,
uses an immutable status-transition intent containing the current `captureDomainFenceToken`, exact
object UID and expected version, exact status patch and certificate hash. A server-side status-write
validator performs a linearizable domain-authority check at the point the Kubernetes write is
accepted; ordinary controller-side validation before the API call is not sufficient. RBAC permits
status writes only through that validated path. The Kubernetes object-version precondition remains
an additional conflict check, not the controller fence.

A crash before the validated status write leaves the condition unchanged. On failover, a true
condition whose referenced certificate or status intent is missing or fails revalidation is
immediately treated as unknown.

## 7. Capture-compromise transition

### 7.1 Planned routing to pass-through capacity

Before intentionally routing any pass-through process, the controller:

1. allocates the next recovery id;
2. durably sets `CaptureCoverageComplete=False`, `CaptureWorkflowTerminal=True`, `recoveryId`, and
   `nonCapturingSince`;
3. verifies that the status update succeeded; and
4. only then routes to the minimum pass-through capacity required by availability policy.

If the status update fails or is ambiguous, pass-through is not routed.

### 7.2 Active proxy capture failure

Terminal capture failure includes retry exhaustion, inability to publish a Kafka record required
for capture, an ambiguous producer outcome, or another failure that compromises capture. Membership
revocation, partition loss, coordinator outage, delayed heartbeats, empty polls, and membership-poll
failure after the first usable assignment are not terminal capture failures.

The proxy applies `--capture-failure-policy` immediately:

- `fail-closed` emits high-severity diagnostics and terminates without attempting connection or
  writer retirement, because Kafka acknowledgements are not trustworthy enough to complete that
  protocol.
- `fail-open` permanently closes the capture activation and immediately converts existing and new
  TCP connections to uncaptured forwarding. It does not wait for controller authorization and can
  never return to capture in that process.

If the failed process cannot report its own transition, the controller durably marks the resource
incomplete and terminal from workload and grant-ledger evidence. If it can report, the controller
records the transition as soon as it observes it. In neither case does the controller infer that
capture remained complete merely because the process disappeared or remained available in
pass-through.

For the current round, either terminal policy makes the capture resource terminal. It may continue
reporting and controlling failed-run pass-through capacity, but it cannot issue another capture
grant, emit a fleet reset, establish coverage, or authorize a source snapshot. A fresh workflow uses
a new resource UID and new run identity.

The fresh workflow cannot declare capture ready while a failed-run pass-through workload can still
receive or forward source traffic. Each such workload's proxy connection set must become empty, or
the workload must terminate or be fenced under §8.3.

Two further restart preconditions are load-bearing and unresolved for the current round:

1. **Kafka input isolation.** A new resource UID does not classify a delayed record from the failed
   activation. Every managed fresh run uses a new `captureSessionId`, trusted replay plan, and
   session-aware checkpoints. It must additionally choose one of:
   - when reusing the same topic, add per-partition reset records/offsets and bind the plan to them;
     or
   - use a distinct immutable Kafka topic identity for the fresh run, with start offsets fixed in
     the trusted plan and no predecessor records in that topic.

   The second option creates a completely separate run with a different immutable input namespace;
   it never changes the topic used by an existing run. Reusing the same topic without a session
   field is unsafe and forbidden.
2. **Source-side quiescence.** Before the fresh source snapshot begins, the workflow must establish
   the source-specific in-flight retirement condition in §8.4 for every failed-run process. A new
   resource, Pod termination, or elapsed controller time is not that barrier.

Until these choices are implemented, “start a fresh workflow” means remain stopped at an external,
operator-verified restart boundary. The controller must not automatically authorize the new source
snapshot.

### 7.3 Controlled replacement without capture failure

A controlled scale-down or clean replacement does not compromise capture if:

- no terminal capture failure occurred;
- it never forwarded uncaptured traffic;
- it is removed from routing or proven terminated;
- every remaining traffic-affecting process is strict; and
- sufficient strict capacity exists without routing to pass-through.

This is ordinary strict-capacity replacement. It does not require a new capture session or source
snapshot.

## 8. Suppression and old capture-activation retirement

### 8.1 Healthy suppression

A proxy may acknowledge `CAPTURE_SUPPRESSED_AND_QUIESCENT` only when:

1. eligibility to accept new captured client connections for the old capture activation is irreversibly
   closed;
2. every old `(writerNodeId, partition)` continues existing-connection traffic and periodic
   manifests while its connections drain;
3. for every closed captured connection, Netty has reported closure, the kernel and Netty provide
   no further network traffic for that connection, its event-loop owner has submitted its terminal
   observation through the Kafka publisher after every earlier observation, Kafka has acknowledged
   all of those observations, and only then has the event-loop owner removed the connection from
   the active set;
4. every `(writerNodeId, partition)` connection registry belonging to the old capture activation is
   empty;
5. every publisher lane has entered `RETIRING` and its periodic manifest publisher is quiescent;
6. every remaining previously accepted producer send completed successfully;
7. a final empty manifest followed by terminal self `NoMoreWrites` is acknowledged for every old
   `(writerNodeId, partition)`;
8. every publisher lane has entered `RETIRED`;
9. the producer is closed; and
10. none of the activation's writer identities can be used again.

This is a strong local proof and may allow a healthy process to receive a later capture grant with
a fresh `captureActivationId` and capture session. The next `writerNodeId` is created by
incrementing `assignmentSequence` after a new group assignment. Individual connection retirement is
exactly the base
protocol above. The source-side in-flight-request barrier and Kubernetes workload traffic
retirement remain separate fleet-level obligations in §§8.3–8.4; neither is part of one
connection's retirement lifecycle.

Healthy suppression and other planned administrative shutdowns use a five-minute default bound for
this orderly retirement. Capture compromise, event-loop death, out-of-memory-like failure, and
corrupted internal ownership do not use this retirement path.

### 8.2 Failed or ambiguous producer

A process with a failed or ambiguous send cannot acknowledge healthy suppression. Stopping new
submissions is not proof that all old Kafka requests disappeared.

For fleet reset, the controller may treat the old capture activation as capture-retired only after:

- the one-way capture-activation latch is irreversibly closed;
- no new capture submission can enter locally;
- the grant ledger forbids another grant to that process activation; and
- every later old-session Kafka record will be rejected by the session gate.

A `fail-open` process may remain alive in pass-through while coverage is incomplete. If the process
cannot reliably report or enforce the closed latch, the controller terminates the actual process,
container, or host and confirms termination rather than merely observing API-object deletion.

Process death proves that no new application sends originate afterward. It does not retract an old
Kafka request already outside the process. `producerMaxBlockTime`, `producerDeliveryTimeout`, and a
safety margin may be useful operational waits, but they are not the correctness proof for rejecting
late Kafka records.

The capture-session rule supplies that proof: after reset to session S, every record carrying a
nonmatching session is discarded and alarmed, even when its writer node id was never previously observed
on that partition.

### 8.3 Kubernetes traffic retirement and fencing

Capture retirement and traffic retirement are separate obligations. Sections 8.1 and 8.2 retire an
old capture activation so that it cannot contribute accepted records to the new session. This
section proves that the associated Kubernetes workload can no longer forward source traffic from
the old interval. Traffic retirement gates coverage establishment and the source snapshot; it does
not need to delay the per-partition reset once every old capture activation is retired.

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
   old capture-activation latch closed, source listener closed, no source-affecting connections or queued
   submissions, and publisher quiescent. The controller binds that report to
   the container ID and node UID already recorded in the grant ledger. The process may remain alive
   and later receive a fresh activation as permitted by §4.2.
2. **Runtime-confirmed termination:** fresh evidence from the kubelet or container runtime for the
   exact Pod UID and container ID on a reachable node confirms that the container exited.
3. **Infrastructure fencing:** the node or underlying compute instance is powered off, terminated,
   or fenced by a mechanism that terminates existing connectivity and prevents the old process from
   reaching either Kafka or the source.

A process that entered `PASS_THROUGH_COMPROMISED` cannot use local quiescence to become eligible for
capture again. Under §4.2 it must have runtime-confirmed termination or infrastructure fencing
before a fresh workflow can declare capture ready. Future same-resource recovery would require the
same proof before returning coverage to true.

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

Controller leader election is an availability mechanism, not the command fence. Every Capture
Replay status mutation goes through §6.3's server-side current-token and immutable-intent
validation as well as an object-version precondition. Every proxy mutation carries resource UID,
domain id, target Pod UID and process id, recovery sequence, `recoveryId`, session, activation,
`captureDomainFenceToken`, and an immutable command-intent reference. A paused former leader may
resume, but it cannot create a current intent or pass the server-side current-token check. A
receiver also rejects an old-token command even when all of its recovery, session, and activation
fields happen to equal the current values.

### 8.4 Source-side quiescence requirement

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

This proof is required both before future automatic recovery may return coverage to true and before
the current terminal-failure path may authorize a fresh workflow's source snapshot. The current
round does not automate the proof; it stops at the external restart boundary described in §7.2.

## 9. Same-topic fresh-run reset and future coverage establishment

### 9.1 Same-topic fresh-run reset

After every prior capture activation is retired under §8.1 or §8.2 and the controller has
frozen the old grant ledger:

1. under the current `captureDomainFenceToken`, atomically allocate the next
   `captureDomainRecoverySequence`, `recoveryId`, and `captureSessionId` in the domain authority
   store;
2. durably persist one immutable reset intent containing `captureDomainId`, those values, a unique
   `resetIntentId`, the Kafka cluster/topic IDs, the exact control-record payload and hash, and one
   send obligation per partition before any send;
3. claim the outstanding obligations under the current domain fence and select a healthy
   Kafka-capable control-record producer;
4. call `emitFleetCaptureReset(resetIntentId, exactPersistedPayload)` and write those exact bytes
   once to every traffic partition; the producer validates the intent, payload hash, and current
   domain authority before accepting the operation;
5. on failed or ambiguous delivery, retry the identical persisted payload rather than allocating a
   new session or sequence;
6. retain every incomplete partition obligation and durably record each acknowledged
   reset offset; and
7. issue no new capture grant until every reset copy is acknowledged; and
8. persist the immutable replay-boundary plan containing those reset offsets before any grant.

The reset establishes the accepted session for each partition. It does not reconstruct source
traffic from the abandoned interval.

An exact duplicate `FleetCaptureReset{captureDomainId, recoveryId,
captureDomainRecoverySequence, captureSessionId, resetIntentId}` on one partition is idempotent. A
reset with the same intent id and different bytes, or the same recovery and a different session, is
a protocol violation. A reset with a lower domain recovery sequence never supersedes the trusted
current session. A higher sequence can supersede an earlier one only through a newly reviewed
recovery transition and a new trusted replay plan; the replayer never chooses between competing
reset records by arrival time alone.

A delayed record from an abandoned nonmatching session that lands after the reset is discarded and alarmed. This
applies even if the old `writerNodeId` had never appeared on that partition.

Pass-through processes and their existing connections may still be forwarding while the
condition is false. They do not block reset once their old capture activation is permanently
closed, because every one of their source mutations remains part of the incomplete interval. They
do block coverage establishment and the next source snapshot.

### 9.2 New capture grants

After reset fan-out completes and the replay-boundary plan is durable, the controller binds eligible
processes to the new session and plan id and authorizes only session-fenced capability probing.
Each process creates a fresh `captureActivationId`, emits probes using
`writerNodeId = captureActivationId + ":PROBE"` and carrying that `captureSessionId`, and joins the
group only after every probe is acknowledged and its producer configuration is validated. There is
one group join followed by Kafka's normal assignment. After the process receives an assignment, it
increments `assignmentSequence`, creates
`captureActivationId + ":" + assignmentSequence`, acknowledges that identity's initial
complete manifests for its permitted partitions, and only then accepts new captured client
connections.

New connections use strict capture only after activation. Existing pass-through connections never
upgrade in place; the proxy connection set is allowed to drain, or those connections are
force-closed.

### 9.3 Coverage establishment

Only after every remaining traffic-affecting process is strict in the current session, every old
workload is traffic-retired under §8.3, and all non-capturing connections are gone may the
controller persist one immutable coverage intent containing a unique `coverageIntentId`, the exact
`CaptureCoverageEstablished{captureDomainId, recoveryId, captureDomainRecoverySequence,
captureSessionId, coverageIntentId}` payload and hash, and one obligation per partition. The
current fence holder claims those obligations and calls
`emitCaptureCoverageEstablished(coverageIntentId, exactPersistedPayload)`. The publisher applies
the same current-authority validation and exact-byte retry rules as reset emission.

This record settles no connection and repairs no missing traffic. It states that the controller
had already established complete capture for the named session before that partition offset.

The custom-resource condition becomes true only after:

- every partition copy is acknowledged;
- the recovery and session remain current;
- the complete population is revalidated; and
- no new compromise occurred during fan-out.

Partial fan-out remains incomplete and retries idempotently.

## 10. Managed replayer rules and bootstrap

### 10.1 Session gate

A managed replayer is created with one expected `captureSessionId`. For every
capture-activation-scoped record:

- matching session: process under the base replayer rules;
- nonmatching session: discard, advance, and emit a high-severity alarm;
- missing session in a managed run: fail closed as protocol-incompatible.

The replayer does not parse writer node ids or compare numeric epochs.

Session mismatch is a consumer decision about an ordinary traffic or manifest record, not a new
wire record type. Do not introduce vague wrapper names such as
`KafkaPostDeclarationTrafficRecord` or `KafkaSupersededTrafficRecord`. Use an explicit
`SessionMismatch` disposition/metric with the expected and observed session identities.

### 10.2 Reset encountered by an existing replay run

A same-topic replay run based on a snapshot from session S1 must not silently cross
`FleetCaptureReset{captureSessionId=S2}`. The uncaptured interval between sessions requires a new
source snapshot.

Therefore an existing S1 replay run that encounters the S2 reset terminates as superseded or
invalidated. It does not settle old state and continue into S2.

### 10.3 Trusted replay-boundary plan and snapshot binding

Before any capture grant for a managed fresh run, the workflow durably creates an immutable
replay-boundary plan:

```
replayBoundaryPlanId
captureDomainId
captureSessionId
trafficKafkaClusterId
trafficTopicId
trafficTopicName              // diagnostic only
resetOffsetByPartition         // required only when reusing a topic
partitionStartOffsets
recoveryId
captureDomainRecoverySequence
resetIntentId                  // required only when reusing a topic
```

`partitionStartOffsets` is fixed before any new-session capture grant is issued and is never moved
forward to snapshot start or completion:

- For a same-topic fresh run,
  `partitionStartOffsets[P] = resetOffsetByPartition[P] + 1`.
- For a distinct immutable topic, the workflow creates and verifies the topic as a new input
  namespace, completes all setup probes, then records each partition's broker end offset before
  granting capture. No capture activation for the new run may publish before those offsets are
  durably stored in the trusted plan, and no later setup write may move the boundary. These sampled
  offsets are normally zero but are defined as broker offsets so acknowledged inert setup records
  do not become replay input.

Both branches deliberately replay every matching-session request captured after their boundary,
including requests whose effects may already appear in the source snapshot. Such duplication is
compatible with the system's at-least-once contract; moving the offset forward could omit a request
captured before the snapshot cut but forwarded to the source afterward.

After a source snapshot is accepted, the snapshot manifest binds:

```
snapshotId
replayBoundaryPlanId
completenessCertificateRevision
coverageEstablishedOffsetByPartition // future automatic coverage restoration
```

This binding finalizes the trusted replay plan but cannot change its capture domain, session, Kafka
identity, or partition start offsets. The replayer receives the finalized plan as trusted
bootstrap. In the same-topic branch, it need not have personally consumed the earlier reset or
coverage-establishment records when its configured start offsets are later.

The replayer must not adopt the first capture session or reset it sees. A delayed or stale record
could be first, and a consumer cannot reconstruct controller-domain authority from log arrival
alone. Every managed replay run requires a trusted replay plan. Observed reset records are validated
against that plan; they do not replace it.

Replayer checkpoints persist the expected capture session, domain recovery sequence, immutable
Kafka cluster/topic IDs, and replay-plan identity. Restart never re-infers them from retained
traffic.

### 10.4 Kafka retention

In the same-topic branch, traffic-topic retention may delete old reset and
coverage-establishment records before a new replayer starts. In the distinct-topic branch, the
replayer likewise cannot infer the authoritative pre-grant boundary merely from the first retained
record. The durable replay plan is therefore required for every managed snapshot and restart.
Periodically re-emitting a boundary record is diagnostic redundancy, not a substitute for trusted
snapshot metadata.

## 11. Managed fresh-run snapshot workflow

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

The replay plan always starts at the pre-grant boundary from §10.3—immediately after reset in the
same-topic branch or at the recorded post-setup broker offsets in the distinct-topic branch—not at
a Kafka position sampled during snapshot creation. Therefore new-session requests racing the
snapshot are replayed rather than omitted. The snapshot workflow may create duplicates, but it
cannot create a hole by advancing the replay start past already captured work.

## 12. Future automatic recovery workflow

This workflow is conditional on resolving the resource-model choice in §6.1. If terminal resources
remain immutable, every step below that grants a new session or restores coverage occurs on a new
resource under the capture-domain lease. If a `RECOVERING` state is introduced, its transition and
failure rules must be added before this workflow is implementable.

For an incident that actually forwarded uncaptured traffic:

1. A proxy enters `CAPTURE_RETRYING`; forwarding remains strict and blocked as needed.
2. Retry is exhausted or capture is otherwise compromised; the configured policy is `fail-open`,
   so the proxy immediately enters `PASS_THROUGH_COMPROMISED` and converts existing and new TCP
   connections to uncaptured forwarding.
3. The controller observes the transition, allocates recovery R, and durably sets coverage false.
4. Strict replacement capacity is started and probed.
5. Every previous capture activation is retired by healthy suppression or the permanent
   failed-activation rules under §8.1 and §8.2.
6. The controller creates session S and chooses the trusted Kafka input branch:
   - **same topic:** persist one immutable reset intent, fan its exact
     `FleetCaptureReset{D,R,S,I}` payload to every partition, await every acknowledgement, and
     record start offset `resetOffset+1`; or
   - **distinct immutable topic:** create and verify the new topic namespace, complete inert setup
     probes, record the pre-grant broker end offset for every partition, and emit no fleet reset.
7. After the selected branch's complete boundary plan is durable, the controller grants S to
   eligible proxies.
8. Proxies become `CAPTURE_AUTHORITATIVE` after the base capability probe, normal group assignment,
   and initial-manifest acknowledgement.
9. Remaining pass-through processes and off-load-balancer connections become traffic-retired
    under §8.3.
10. Source-side in-flight retirement is established under §8.4.
11. The controller persists an immutable coverage intent and fans its exact
    `CaptureCoverageEstablished{D,R,S,I}` payload to every partition.
12. After all acknowledgements and revalidation, the resource becomes complete.
13. A new source snapshot is taken and bound to S.
14. A new replayer run starts from that snapshot's trusted replay plan.

At every stage, failure leaves the condition false or unknown. No timeout skips a proof.

## 13. Implementation deltas

The terminal-on-error controller behavior, immutable Kubernetes identity, command fencing, and
durable failed condition belong to the current round. Session identity, trusted replay plans, and
session-aware checkpoints are also current for every managed fresh run. Per-partition reset is
current only when reusing a Kafka topic. Coverage re-establishment and same-resource
replacement-snapshot automation remain future deltas.

| Change | Scope | Required behavior |
|---|---|---|
| Managed configuration | Current | Add `suppressCaptureByDefault`, the process-wide `--capture-failure-policy`, definite-uncompromised retry configuration, a 60-minute default maximum connection lifetime, and a five-minute default orderly-retirement bound. |
| Separate status/control interface | Current | Expose local state and authenticated idempotent commands without sharing the source listener. |
| Capture Replay status | Current | Add complete/false/unknown coverage, immutable terminal-workflow status, recovery identity, and transition timestamps. |
| Durable grant ledger | Current | Persist every process, capture activation, assignment-scoped writer identity, grant, and retirement state across controller failover. |
| Completeness certificate | Current | Persist immutable evidence before atomically publishing its revision with `CaptureCoverageComplete=True`; failover treats missing or invalid evidence as unknown. |
| Traffic-affecting inventory | Current | Track load-balanced processes, processes whose connection sets are draining, off-LB live connections, and unreachable grants. |
| Kubernetes workload identity | Current | Track Pod UID, container ID, node UID, and process identity; never retire by Pod name or replacement readiness. |
| Kubernetes fencing | Current | Prove traffic retirement through trusted healthy quiescence, runtime-confirmed termination, or infrastructure fencing that cuts existing connectivity; force deletion and node timeout are insufficient. |
| Controller command fence | Current | Allocate a domain fence token, persist immutable command intents, target immutable Pod and process identity, and reject stale same-recovery commands independently of leader election. |
| Record schema | Current | Carry `captureSessionId` on every managed traffic, manifest, and proxy-completion record; carry `manifestCycle` per `TrafficObservation`; use the Kafka record header as the authoritative `writerNodeId` for `NoMoreWrites`; permit mixed-cycle parents with one child obligation per observation and parent commit only after every child is terminal `Satisfied`. Valid control records and record-level session rejection use one control child. If a fresh run reuses the same Kafka topic, add per-partition reset records now; add coverage-establishment records for future automatic recovery. |
| Proxy state machine | Current | Implement retry only for definite uncompromised transient failures, immediate `fail-closed` termination, irreversible whole-process `fail-open`, healthy suppression, permanent failed-activation rules, last-usable-assignment routing, and concurrent draining of assignment-scoped writer identities. |
| Session-aware replayer | Current | Enforce the expected session, reject nonmatching-session records, persist bootstrap, and invalidate a run on a superseding reset when reset records exist. |
| Snapshot replay plan | Current | Persist the capture domain, accepted session, domain sequence, immutable Kafka IDs, and partition start offsets; include reset offsets when reusing a topic. |
| Capability probe | Current | Implement the base acknowledged per-leader capability probe using `writerNodeId = captureActivationId + ":PROBE"` and the assignment-scoped initial-manifest gate rather than metadata connectivity. |
| Kafka clock-skew enforcement | Current | Supply the same `E` and `S` parameters to proxies and replayers, configure the node monitor against that `S`, block unhealthy broker startup, stop a skewed broker locally, publish a custom `ClockSkew` condition, and disable replayer broker-time expiration whenever the declared bound is not healthy. |
| Replayer fatal-termination alarms | Current | Treat the final in-process fatal metric as best-effort; alert independently on unexpected replayer container restarts and correlate exit code `80` with fatal event-loop-owner loss. Qualify the required kube-state-metrics interfaces for the deployed version. |
| Source retirement proof | Current fresh-run precondition; future automation | Define how forced termination excludes source operations completing after the recovery boundary. |
| Security | Current | Authenticate and authorize every mutation and control-record emission. |
| Observability | Current and future | Export terminal failure, inventory, compromise, suppression, proxy connection-set-drain, and rejection metrics now; add session, fan-out, and replacement-snapshot metrics with future recovery. |

## 14. Failure boundaries

The terminal resource, Kubernetes identity, and stale-command boundaries below apply now. The
session and replay-plan boundaries also apply to every managed fresh run. Reset boundaries apply
now when a topic is reused. Coverage-establishment boundaries remain future automatic recovery.

- Controller unavailability does not prevent a configured `fail-open` proxy from making its local
  irreversible transition after capture compromise. It still prevents new capture grants and
  planned controller-directed pass-through routing.
- Existing strict proxies may continue their already granted session while the controller is down.
- New processes remain suppressed without a grant.
- Unexpected loss of any `CAPTURE_AUTHORITATIVE` process terminally fails the current workflow even when no
  uncaptured forwarding has yet been observed.
- A stale command with a lower domain recovery sequence, lower domain fence token, unvalidated
  command intent, or nonmatching session cannot reactivate a superseded workflow.
- `CaptureWorkflowTerminal=True` is immutable; every later grant, reset, or coverage-establishment
  command for that resource UID is rejected.
- Load-balancer removal and Pod deletion are not process termination.
- Force-deleted Pods and unreachable nodes remain traffic-affecting until runtime evidence or
  infrastructure fencing is recorded.
- A fresh workflow cannot declare capture ready while failed-run pass-through capacity remains
  traffic-affecting.
- A stale controller that lost leadership cannot issue an accepted command even when it repeats
  the current resource, recovery, session, and activation: it lacks the current domain fence and
  cannot persist a current immutable command intent.
- A percentage below 100% can authorize capacity decisions but never complete capture.
- Absence of the replayer's final OpenTelemetry fatal metric does not suppress a
  Kubernetes-observed restart or exit-code alarm.
- Simultaneous capture-activation and final-manifest loss is not retroactively repaired. The current
  workflow explicitly abandons that incomplete interval and may start a fresh capture and snapshot
  workflow only after §7.2's isolation and source-quiescence preconditions.

All managed fresh runs additionally require:

- Late records from a nonmatching session are discarded and alarmed, including the first record from a
  previously unseen capture activation.
- A replay run never crosses from one capture session to another without a new snapshot.

Same-topic fresh runs additionally require that a reset acknowledged on only some partitions
establishes nothing globally.

Future automatic coverage restoration additionally requires that coverage establishment
acknowledged on only some partitions keeps the resource incomplete.

## 15. Validation plan

Current implementation requires the terminal-failure, immutable-identity, stale-command,
force-deletion, and old-workload-retirement cases. Every managed fresh-run implementation also
requires session identity, a trusted replay plan, and session-aware checkpoint tests. A same-topic
fresh run additionally requires reset fan-out and reset-offset tests. Coverage restoration on a
terminal resource and automated replacement-snapshot orchestration remain future tests.

### 15.1 Deterministic tests

Current round:

- stale, duplicate, and out-of-order controller commands;
- a paused former controller leader resuming after a newer recovery has started;
- a paused former leader issuing a command with the current recovery and session but an older
  `captureDomainFenceToken`;
- an invented higher fence token without a domain-authority-validated immutable intent being
  rejected;
- a paused former leader attempting a status write with the still-current Kubernetes object
  version but an old domain fence token, and the server-side validator rejecting it;
- Capture Replay deletion and recreation with the same name but a different resource UID;
- forced finalizer removal followed by reconstruction from the surviving unresolved-grant ledger;
- Pod IP reuse receiving a delayed command addressed to the previous Pod UID and process id;
- controller failure after certificate persistence but before the status compare-and-swap;
- failover observing a true condition whose referenced certificate is missing or invalid;
- retry success returning to `CAPTURE_AUTHORITATIVE` before the failure decision;
- ambiguous producer outcome never returning to `CAPTURE_AUTHORITATIVE`;
- permanent capture-activation closure after retry exhaustion;
- unexpected active-Pod loss terminally failing the workflow;
- terminal status remaining immutable after retry exhaustion;
- every same-resource grant, reset, and coverage-establishment command being rejected after
  terminal failure;
- a fresh resource remaining unready while failed-run pass-through capacity is traffic-affecting;
- a fresh resource being unable to reuse the failed topic without the complete same-topic package:
  a new `captureSessionId`, immutable reset intent, per-partition reset acknowledgements and start
  offsets, trusted replay plan, and session-aware checkpoints;
- a delayed failed-session record arriving after the fresh run boundary and being discarded,
  advanced past, and alarmed;
- replay bootstrap and checkpoint restart preserving the trusted capture domain, session, domain
  sequence, immutable Kafka identities, and replay-plan identity;
- a fresh snapshot remaining unauthorized until the source-specific in-flight retirement barrier
  succeeds;
- a configured `fail-open` process transitioning immediately to whole-process pass-through after
  capture compromise, with the controller durably recording incomplete and terminal status when it
  observes the transition;
- planned controller-directed pass-through routing only after durable incomplete and terminal
  status;
- healthy suppression acknowledgement only after every closed connection completes the base
  connection-retirement order, every retiring `(writerNodeId, partition)` registry is empty, and
  every producer future succeeds;
- capability probes use `writerNodeId = captureActivationId + ":PROBE"` and create no replayer writer baseline,
  manifest registry, or connection state;
- the first assignment and every replacement assignment increment `assignmentSequence` and create
  a new `writerNodeId` for new connections while existing connections retain their original
  identity;
- rapid rebalances leave several writer identities draining independently, each keyed by
  `(writerNodeId, partition)`;
- an active identity's empty manifest remains a heartbeat and never resets its accepted broker-time
  baseline;
- after an old identity drains, its final empty manifest and valid `NoMoreWrites` permanently retire
  that identity and partition;
- a manifest prepared before a closed connection's acknowledged active-set removal listing that
  connection, and a manifest prepared afterward being allowed to omit it;
- managed mixed-cycle traffic records creating exactly one child obligation per observation,
  settling WorkClaims and children independently, and withholding the parent offset until every
  child is terminal `Satisfied`;
- cycle-boundary flushing and mixed-cycle managed batching preserving the same per-observation
  semantics and WorkClaim decisions without loss or duplication, while permitting different
  parent-record identities, commit timing, and redelivery granularity;
- failed or ambiguous sends preventing healthy suppression;
- off-load-balancer live connections blocking completeness;
- unreachable processes producing false or unknown, never true.

Conditional current tests when a fresh run reuses a Kafka topic:

- reset fan-out idempotence and partial failure;
- reset offsets becoming the immutable per-partition replay start offsets;
- reset-intent payload immutability and identical retry after ambiguous send;
- session mismatch rejection for known and previously unseen capture activations through one
  control child that becomes `Satisfied` only after discard, advance, logging, metrics, and alarm;
  and
- a later trusted reset invalidating an older snapshot/replay run.

Future automatic recovery:

- coverage-establishment fan-out idempotence and partial failure;
- same-resource recovery only if a reviewed nonterminal `RECOVERING` state is introduced; and
- replacement-snapshot authorization and rejection.

### 15.2 Testcontainers Kafka

Current session-fencing tests:

- a record from a nonmatching session creating one control child, then being discarded, advanced
  past, logged, counted, alarmed, and marked `Satisfied`;
- an expected-session mixed-cycle record settling WorkClaims and children independently while the
  parent offset remains uncommitted until every child is terminal `Satisfied`;
- restart from a trusted replay plan after its boundary records have aged out; and
- checkpoints rejecting a different capture domain, session, domain sequence, or immutable Kafka
  identity.

Conditional current tests when a fresh run reuses a Kafka topic:

- an old producer request appended after `FleetCaptureReset` and rejected by session;
- reset from a different producer overtaking an ambiguous old send;
- controller failover during reset fan-out, including takeover of the persisted immutable intent;
- broker restart during reset and activation;
- duplicate reset records and independent partition barriers;
- all relevant capture activations and final manifests disappearing, with broker-time expiration
  affecting only already-known connection accumulators and never replacing the explicit
  session-reset boundary for managed-run recovery;
- a request captured before snapshot start but forwarded after the snapshot cut remaining included
  because replay starts immediately after reset; and
- protocol-version rejection during the wire-format rollout.

Future automatic recovery adds broker restart and controller failover during coverage
establishment, plus duplicate coverage records.

### 15.3 Live deployment tests

Current round:

- broker startup blocked by an unhealthy node clock;
- skew beyond the local threshold stopping the broker before the declared `S` bound is consumed;
- custom `ClockSkew` condition, cordon, taint, and node replacement without modifying kubelet
  `Ready`;
- replayer broker-time expiration disabled and alarmed when the fleet cannot attest the skew bound;
- a replayer event-loop death producing container exit code `80`, incrementing the Kubernetes
  restart signal, and firing the reason-specific alarm even when the final in-process
  `replayFatalFailures` point is not exported;
- an ordinary replayer `System.exit` path not being classified as event-loop-owner loss;
- deployment qualification failing when the required kube-state-metrics restart or
  last-termination interfaces are absent;
- controlled strict-proxy replacement with sufficient remaining capacity and no terminal capture
  failure;
- AZ capacity falling below policy and controller-authorized routing to pass-through capacity;
- `fail-open` pass-through preserving existing connections and accepting new connections without
  capture;
- replacement-process handoff requiring client reconnect;
- a connection remaining open until the 60-minute default maximum lifetime and blocking recovery
  until it closes or is force-closed;
- Pod deletion without actual process death;
- force deletion leaving the old process running;
- a node partition leaving the Pod and existing connections alive;
- Pod-name and StatefulSet-ordinal reuse while the old grant remains unretired;
- node or host fencing when process termination cannot be confirmed;
- controller restart with grant-ledger reconstruction.
- resource deletion and recreation discovering unresolved predecessor grants by `captureDomainId`
  across all Kafka topic identities rather than by resource name, UID, or current topic alone.

Future automatic recovery:

- snapshot rejection when recovery changes mid-snapshot; and
- full recovery followed by a replayer launched after reset records have aged out, using only the
  durable replay plan.

## 16. Current implementation requirements and future recovery decisions

The current terminal-on-error Kubernetes implementation still requires:

1. Implement and verify the exact capability probe in §4.4 and the initial-manifest gate.
2. Choose and implement the fresh-run Kafka isolation rule from §7.2. Every choice requires a new
   `captureSessionId`, trusted replay plan, and session-aware checkpoints. Reusing the same topic
   additionally requires an immutable reset intent, per-partition reset records and offsets, and
   reset-aware replay invalidation.
3. Define the source-specific in-flight retirement barrier required before a fresh snapshot.
4. Define durable replay-plan and checkpoint storage.
5. Define durable terminal-status and capture-domain grant-ledger storage, canonical
   `sourceIdentity` assignment, the one-to-one `captureDomainId` authority, command-intent storage,
   and domain-fence failover semantics.
6. Finalize the Kubernetes termination-evidence adapters for the supported runtimes, durable
   unresolved-grant storage outside the custom-resource lifecycle, and the infrastructure-fencing
   operation for unreachable nodes.
7. Decide whether fatal replayer termination requires a strict no-loss event journal in addition
   to the current Kubernetes metrics alarms. If it does, define the controller's Pod-status watch,
   durable event identity, storage, replay after watch interruption, and acknowledgement contract.

Future automatic recovery within a failed workflow additionally requires:

1. Automate and durably record the source-side in-flight retirement proof after forced process or
   host termination.
2. Finalize coverage-establishment record rollout and orchestration. Reset rollout is already
   current for the same-topic fresh-run profile.
3. Decide whether unmanaged operators may manually provide a `captureSessionId`; if so, document
   that this is a manual new-run boundary rather than automatic recovery.

Until those decisions are complete, a terminal capture failure permanently leaves that resource
incomplete and terminal. The controller must not emit a reset, grant a replacement session, return
coverage to true, or authorize a new source snapshot under that resource. A fresh workflow may
proceed only after the Kafka-input-isolation and source-quiescence preconditions in §7.2 are
externally satisfied and recorded.
