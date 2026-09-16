# Managed Fleet Capture Recovery

**Status: managed terminal-failure and fresh-run design contract; implementation conformance is
incomplete (2026-09-14).**

Managed fleet operation will not be implemented soon. This document exists to confirm that the
capture and replay architecture can support a controller-managed Kubernetes design in the near
future, and to keep the base protocol from ruling that design out. Its requirements are stated
precisely enough to be load-bearing constraints on the other documents, but it is deliberately
lighter than the implemented designs: a real implementation effort should expect to close gaps
here before claiming this contract.

This document extends the standalone [Proxy Capture Protocol](proxyCaptureProtocol.md) and assumes
it as written: assignment-scoped writer identities that are never reused; Kafka membership as load
balancing only; connection retirement gated on Kafka acknowledgement of the terminal and all
earlier observations; broker-time expiration (`E + S`) valid only while the declared clock-skew
bound is enforced; strict capture-before-forward for Critical Mutation Traffic within the
non-streaming source-execution scope; mandatory ordering-preserving producer settings; and the
process-wide `--capture-failure-policy` (`fail-closed` terminates immediately, `fail-open`
permanently abandons capture). Heartbeats carry no connection identities, and
`WriterPartitionHeartbeat` is the fixed protobuf name.

The managed contract adds one central rule: **an uncaptured source interval is terminal.** A
capture-compromised proxy never captures again in that process. A `fail-closed` proxy terminates
immediately and may be replaced within the existing run, because it forwarded no uncaptured source
traffic. A `fail-open` proxy abandons capture but blocks uncaptured source forwarding until the
controller has durably marked the resource incomplete and terminal and has acknowledged that exact
capture activation; if acknowledgement does not arrive within the configured finite deadline, the
proxy terminates instead. Once uncaptured forwarding is authorized, the controller never grants
capture again under that resource UID and never returns its coverage condition to true. Restoring
a complete guarantee requires a fresh workflow with a new immutable Kafka topic, a fresh source
snapshot, and a new replayer run. The controller may keep acknowledged failed-run pass-through
capacity alive for availability, but that capacity cannot participate in the replacement capture
run.

Automatic return to complete fleet coverage within a failed resource is unsupported. Sections 9.3
and 12 constrain any future implementation but do not authorize it. Reusing the ended run's topic
is prohibited. Section 7.2 defines the restart preconditions.

The wire records and controller behavior in this document are target requirements. A deployment
must not claim this managed contract until its proxy, replayer, archive tooling, checkpoints, and
controller all implement the same protocol version assigned to the Kafka topic.

## 1. Goals

The managed contract adds four capabilities:

1. Durably mark capture incomplete before any planned or failure-triggered uncaptured source
   traffic is forwarded.
2. Determine whether every process still capable of affecting the source is capture-only.
3. Make controller failure conservative: stall, remain incomplete, or exit, but never silently
   assert complete capture.
4. Bind every managed fresh run to one new immutable Kafka topic, one trusted replay-boundary
   plan, and checkpoints that cannot be applied to another topic or run.

Automatic recovery is out of scope. It would additionally require driving a fleet automatically
through retirement, fresh-run activation, source quiescence, snapshot creation, and replay
startup, and automatically restoring and publishing complete coverage. Neither scope promises
global ordering across connections or partitions or makes missing traffic replayable.

## 2. Why the base protocol is insufficient for automation

The standalone protocol exposes local facts, not one fleet verdict. Managed operation must handle
conditions the base protocol cannot see:

- a process removed from the load balancer may keep an existing connection and continue mutating
  the source for an hour or more;
- killing a producer prevents new sends but does not retract a Kafka request already transmitted
  to a broker — client timeouts are not broker fencing;
- a delayed record from an ended capture activation may still be appended to its topic after
  replacement begins, so a fresh run cannot safely reuse that topic;
- a replayer must receive its topic identity and start offsets from trusted workflow state, not
  infer them from the first traffic record it sees; and
- a lost capture activation with insufficient later broker-time evidence on a partition has no
  ordinary settlement path; recovery must explicitly abandon the incomplete interval rather than
  infer completion from replacement capacity.

These gaps require an external authority plus a new immutable Kafka topic after an uncaptured
interval. A process timeout alone establishes neither boundary.

### 2.1 Kafka broker clock-skew requirement

The base protocol's broker-time expiration proof depends on an enforced bound `S`: the maximum
permitted backward movement between Kafka `LogAppendTime` values at increasing offsets in one
partition. This is an operational safety dependency, not a monitoring preference.

The requirement applies to Kafka broker clocks. Proxy-recorded `TrafficObservation.ts` values are
replay-timing data only; proxy clock health cannot authorize or invalidate broker-time expiration.

The orchestration layer supplies one heartbeat publication interval `H` to every proxy and one
agreed pair of `E` and `S` values to every proxy and replayer in the run. Defaults are `H = 10
seconds` and `E = 30 seconds`. Startup rejects nonpositive values and requires `H < E`, with
enough `E - H` margin for scheduling, publication, retries, acknowledgement processing, and
jitter; `S` does not contribute to that local margin. `heartbeatIntervalMillis` remains
informational to the replayer. The orchestration layer also supplies each proxy's positive
`trafficStreamFlushInterval` `F`, defaulting to five seconds; `F` is not supplied to or
interpreted by the replayer, and proxies do not need identical `F` values.

Every workflow-managed capture topic sets `message.timestamp.type=LogAppendTime`. The proxy
independently verifies this before joining its group: each per-leader capability probe supplies a
producer timestamp of zero and requires a positive broker-assigned timestamp in the
acknowledgement. A rejected probe or an unchanged timestamp prevents capture startup.

Every Kafka broker node must run a node-level clock monitor (Node Problem Detector or an
equivalent DaemonSet) that enforces:

- broker startup is prohibited while the node clock is unhealthy;
- the alarm and local broker-termination threshold is lower than `S`, leaving margin for detection
  and shutdown before the declared bound can be violated;
- excessive skew stops the broker locally without waiting for control-plane reconciliation;
- the node reports a custom `ClockSkew` condition and is cordoned, tainted, and replaced;
- the monitor does not overwrite the kubelet-owned `Ready` condition; and
- an optional Kafka-pod sidecar may terminate a broker faster, but node-health reporting stays
  with the node-level monitor.

If `K` is the maximum pairwise broker skew, use `S = K`. If every broker clock may independently
differ from trusted time by `±K`, use `S = 2K`.

The workflow may start only while the fleet continuously attests that the bound is healthy. If
attestation is lost during a run, the controller terminally fails the workflow and stops replay;
the replayer does not infer external clock health itself. Independently, if the replayer directly
observes a higher-offset record whose `LogAppendTime` is more than `S` below the greatest value
previously observed for that partition, it emits high-severity diagnostics and terminates before
that record authorizes expiration or commit. Recovery requires restoring the clock guarantee and
starting a fresh workflow.

This constrains managed Kafka offerings as well as self-hosted brokers: broker-time expiration is
allowed only when the provider supplies an operationally enforceable, attestable bound satisfying
`S`. An SLO that does not bound backward `LogAppendTime` movement is insufficient, and a managed
capture-and-replay workflow must not start without one.

### 2.2 Replayer fatal-termination alarming

The replayer cannot guarantee export of a final OpenTelemetry metric before `Runtime.halt()`. The
fatal handler still attempts `replayFatalFailures{reason=event_loop_terminated}`, but fleet
alarming must not depend on that export.

Fatal event-loop-owner loss uses exit code `80`, reserved for that reason and distinct from every
other replayer exit code. The bounded fatal path may begin with `System.exit(80)` so shutdown
hooks can flush diagnostics, and invokes `Runtime.halt(80)` if that bound expires. Kubernetes
observes either path as a container termination — normally a container restart, not Pod deletion.

Every managed deployment runs kube-state-metrics or an equivalent independent observer and
exports:

- `kube_pod_container_status_restarts_total`;
- `kube_pod_container_status_last_terminated_exitcode`; and
- when available, `kube_pod_container_status_last_terminated_timestamp`.

The last-termination metrics are version-dependent interfaces and must be verified during
deployment qualification. The stable, general alarm is any unexpected replayer container restart;
a second reason-specific alarm correlates a recent restart with exit code `80`:

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

The alert has no waiting period and remains firing long enough to be observed after the
five-minute window. Selectors are deployment parameters. A pull-based metrics system still cannot
guarantee that every termination is observed; a strict no-loss requirement needs an external
controller that watches container-status transitions and persists each termination durably.
Whether the managed controller must provide that journal remains unresolved (§16).

## 3. Terminology and identities

### 3.1 `processId`

Identifies one running proxy process to the controller and deployment platform. Fresh on every
process start; never reused.

### 3.2 Capture activation and writer identities

`captureActivationId` identifies one capture-authoritative lifetime of a proxy process. It is
fresh for every new capture activation and never reused after capture is abandoned.

`assignmentSequence` is process-local, unique, and never reused within one `captureActivationId`;
the proxy chooses a new value for every new Kafka group assignment before accepting a connection
under it. Newly opened connections use:

```text
writerNodeId = captureActivationId + ":" + assignmentSequence
```

Only uniqueness and non-reuse matter; the value's ordering has no protocol meaning. Existing
connections permanently retain their original `writerNodeId`, so rapid rebalances may leave
several writer identities draining in one process. Every connection registry, publisher lane, and
broker-time baseline is keyed by `(writerNodeId, partition)`.

Each `(writerNodeId, partition)` has one continuous accepted-heartbeat broker-time baseline.
Timely heartbeats update it, including while the local registry is empty; they never end or reset
it. A late heartbeat permanently compromises the process, and ordered heartbeat publication
prevents a later heartbeat from restoring freshness.

While that baseline is timely, the replayer does not infer that any individual connection closed.
Per-connection completion comes only from the connection's terminal `CloseObservation`; the
controller adds no second connection-membership protocol. That makes the base proxy invariant
load-bearing: Netty must close the real channel, the event-loop owner must publish the terminal
observation after all earlier ones, and the registry may remove the connection only after Kafka
acknowledges all of them.

The pre-group Kafka reachability probe uses `writerNodeId = captureActivationId + ":PROBE"`.
`CaptureCapabilityProbe` proves only that the proxy can publish to Kafka; the replayer creates no
writer, heartbeat, or connection state from it.

### 3.3 `captureDomainId`

Identifies one source dataset and its capture-stream lineage across resource deletion,
recreation, and topic changes. It is a globally unique opaque value minted exactly once by a
linearizable capture-domain authority and stored outside the custom-resource lifecycle, with a
compare-and-set-protected one-to-one binding from the deployment's canonical `sourceIdentity`.
No resource name, topic name, endpoint alias, or new controller instance can create a second
active domain for the same lineage. A deployment that cannot define a canonical source identity
and enforce that binding is not authorized for managed fresh-run recovery: separate resources
could otherwise grant capture concurrently for one source.

### 3.4 `recoveryId`

Identifies one idempotent controller recovery workflow: the Capture Replay resource identity plus
a durably allocated, monotonically increasing `captureDomainRecoverySequence`, allocated with a
compare-and-set in the domain authority so it stays ordered across resource deletion and
recreation. It prevents stale control commands from reactivating a superseded workflow.

### 3.5 Fresh-run Kafka topic identity

After an uncaptured interval, the workflow creates a Kafka topic with a new, non-reused topic name
and Kafka topic ID. No ended-run proxy is configured to publish to it, and the new replayer
consumes only this topic, so a delayed send from an ended process stays confined to the ended
run's topic. The trusted replay plan records the Kafka cluster ID, topic ID, topic name, and
partition start offsets. Never delete and recreate a topic name for a fresh run: an ended producer
could still know the old name while the recreated topic has a different topic ID.

### 3.6 `captureDomainFenceToken`

Fences controller authority within one capture domain, including two controller leaders acting on
the same resource, recovery, topic, and activation. The domain authority allocates a strictly
increasing token whenever control authority is acquired or transferred. Every external mutation
carries the token and a reference to an immutable command intent durably recorded under it. The
receiving proxy or control-record publisher validates the intent and token with a linearizable
read from the domain authority before mutating; if the authority is unavailable, the mutation
stays blocked. Receivers reject lower tokens, persist the highest validated token as a local
defense, and treat an exact retry of an already authorized intent as idempotent. This closes the
paused-former-leader hole even when both controllers hold the same recovery and topic values.

### 3.7 Traffic-affecting process

A process is traffic-affecting if it can receive a new source connection, owns an existing client
or source-side connection, may still forward a previously accepted source operation, or is
unreachable and has not been durably retired or fenced. Fleet completeness is evaluated over this
set, not over load-balancer endpoints or desired replicas.

### 3.8 Kubernetes workload identity

The controller identifies a running process by immutable workload identity, not Pod name:

```text
cluster identity
namespace
Pod UID
container ID
node UID
processId
captureActivationId
```

A replacement Pod with the same Deployment, ordinal, labels, IP, or name is a different process.
Writer identities are data-plane identities owned by this workload; the grant ledger retains the
old workload identity and all of its writer identities until their retirement proof is durable.

## 4. Required proxy changes

### 4.1 Configuration

Controller-managed processes use:

```
suppressCaptureByDefault = true
--capture-failure-policy = fail-closed | fail-open
```

`suppressCaptureByDefault=true` is stable deployment configuration, not something the controller
toggles per incident; every replacement boots suppressed even if it missed prior commands.

The maximum whole-connection lifetime defaults to 60 minutes. Planned retirement while capture and
Kafka remain trustworthy follows
[Proxy Capture Protocol §3.5](proxyCaptureProtocol.md#35-scale-down). Each proxy also receives the
base protocol's positive `trafficStreamFlushInterval` `F`
([Proxy Capture Protocol §4.2](proxyCaptureProtocol.md#42-publisher-ordering-and-acknowledgement)
is normative); the controller supplies the setting and observes its health metrics, but `F` is not
a recovery boundary.

A heartbeat acknowledgement deadline expiring, an ambiguous producer outcome, inability to publish
a required record, or any other compromise closes the capture activation immediately and applies
the configured process-wide policy. That process never returns to `CAPTURE_AUTHORITATIVE`;
replacement requires a fresh process and fresh `captureActivationId`.

### 4.2 Local states

The managed outer lifecycle is:

```
SUPPRESSED
  -> PROBING
  -> CAPTURE_AUTHORITATIVE

CAPTURE_AUTHORITATIVE
  -> process exit                   // fail-closed after capture compromise
  -> COMPROMISED_AWAITING_CONTROLLER_ACK
       -> PASS_THROUGH_COMPROMISED  // controller durably recorded the gap
       -> process exit              // acknowledgement deadline expired
```

A healthy controller-requested suppression has a separate path:

```
CAPTURE_AUTHORITATIVE -> SUPPRESSING -> CAPTURE_SUPPRESSED_AND_QUIESCENT
```

`CAPTURE_AUTHORITATIVE` is a local state, not group metadata. After probing succeeds, the process
joins the group and uses its first completed assignment from a Kafka-provided strategy. After
that, revocation, partition loss, empty polls, coordinator outages, delayed group heartbeats, and
polling failures change nothing; the proxy keeps its last usable assignment until Kafka supplies a
replacement. Fleet startup and availability thresholds use authenticated `captureReady` status and
the controller's workload inventory, never Kafka subscription or assignment data.

### 4.3 Status and control interface

Every proxy exposes a port that is not used for source forwarding. Read-only status includes:

```
processId; captureActivationId, assignmentSequence, currentWriterNodeId (when created);
    drainingWriterNodeIds
podUid; captureReplayResourceUid; captureDomainId; recoveryId;
    captureDomainRecoverySequence; captureDomainFenceToken
trafficKafkaClusterId; trafficTopicId; replayBoundaryPlanId; captureActivationNumber
captureOperatingState; captureCapability and latestProbeResult; captureFailureCause
captureCompromiseAwaitingAcknowledgement; captureCompromiseNotificationAge;
    captureCompromiseAcknowledged
forwardingReady; sourceListenerAccepting; captureReady; newConnectionCaptureSuppressed;
    publisherQuiescent
capturingConnectionCount; nonCapturingConnectionCount; oldestNonCapturingConnectionAge
protocolVersion; binaryVersion
```

Connection counts and draining-writer status come from the proxy's exact local registries. The
controller never reconstructs them from Kafka heartbeats, and never infers an empty registry from
Kafka silence, a recent heartbeat, group departure, or replacement capacity. If the authenticated
process cannot report its local state, the workload and its grants remain unresolved until §8.3
records a termination or fencing proof.

Authenticated, authorized, idempotent mutation endpoints include:

```
suppressCapture(recoveryId)
grantInitialCapture(recoveryId, captureActivationNumber)
forceCloseNonCapturingConnections(recoveryId)
acknowledgeCaptureCompromise(captureActivationId)
```

Automatic coverage restoration and same-resource regrant, if ever implemented, additionally
require:

```
grantCapture(recoveryId, captureActivationNumber)
emitCaptureCoverageEstablished(coverageIntentId, exactPersistedPayload)
```

Every mutation also carries a common command envelope:

```
targetPodUid; targetProcessId; captureReplayResourceUid; captureDomainId
recoveryId; captureDomainRecoverySequence; captureDomainFenceToken
expectedCaptureActivationNumber; commandId; commandIntentId; commandIntentHash
```

Fresh-run commands additionally carry the immutable Kafka cluster/topic IDs,
`replayBoundaryPlanId`, and the new activation number. The proxy rejects a command whose target
Pod UID or process id does not match its immutable local identity, rejects a different resource
UID once bound, rejects stale recovery, topic, activation, or fence values, validates the command
intent and hash against the current `captureDomainFenceToken` before an irreversible mutation, and
handles an exact duplicate `commandId` idempotently.

The coverage-record endpoint never constructs its payload from caller-supplied ids. It accepts
only the exact bytes and hash from an immutable persisted intent, validates that intent's current
domain authorization, and retries those same bytes after an ambiguous send. A leadership change
may claim remaining partition obligations under a newer fence token but cannot alter the persisted
wire payload.

Readiness probes cannot call mutation endpoints. Network policy, transport authentication, and
request authorization restrict mutations to the workflow controller.

### 4.4 Compromise notification and acknowledgement

The first local event proving compromise closes the proxy's capture gate immediately. The proxy
then reports its state without using the traffic Kafka cluster — a push notification or
controller-held event stream normally, with status polling as a reconciliation fallback that still
ends in the explicit acknowledgement call.

One capture activation can be compromised only once, so the notification is identified by the
existing immutable resource UID, Pod UID, `processId`, `captureActivationId`, topic ID, recovery
sequence, and controller fence; retries need no second event identity and duplicates are
idempotent.

Before acknowledging, the controller:

1. validates the reported identities and current domain fence against the grant ledger;
2. persists an immutable terminal-status intent for the exact capture activation;
3. durably sets `CaptureCoverageComplete=False`, `CaptureWorkflowTerminal=True`,
   `nonCapturingSince`, and the failure cause; and
4. verifies that the status write succeeded under the current domain fence.

Only then may it acknowledge. Receiving the notification, failing a health check, or scraping a
metric is not acknowledgement. If the durable update succeeded but the response was lost, a retry
returns the same acknowledgement; failover reconstructs it from durable state.

While acknowledgement is pending, `captureReady=false`, `forwardingReady=false`, and no
source-bound traffic that has not already passed the capture-before-forward gate may be forwarded.
The proxy may retain TCP connections but cannot send their newly received source-bound bytes. A
matching authenticated acknowledgement permits the irreversible transition to
`PASS_THROUGH_COMPROMISED`; deadline expiration terminates the process instead.

### 4.5 Capture capability

The managed fleet uses the exact base capability probe:

1. refresh traffic-topic metadata;
2. choose one representative partition for every distinct current leader broker;
3. publish a semantically inert `CaptureCapabilityProbe` carrying
   `writerNodeId = captureActivationId + ":PROBE"` to each representative;
4. require acknowledgement from every probe;
5. validate the effective producer idempotence, `acks`, and in-flight settings; and
6. refresh metadata again before joining the proxy group.

`captureCapability=true` means that complete sequence succeeded for the reported activation and
metadata view; a metadata lookup or TCP connection alone is insufficient. The probe is a
precondition for capture eligibility, not a durability promise — normal publication failure still
closes the gate — and it creates no replayer state. After a group assignment completes, the proxy
also satisfies the base initial-heartbeat acknowledgement rule for the current `writerNodeId` on
every permitted partition before accepting a captured connection.

## 5. Managed data-plane records

Managed and unmanaged modes use the same `CaptureRecord` envelope, defined normatively in
[Proxy Capture Protocol §4.1](proxyCaptureProtocol.md#41-record-types): the active `payload`
oneof (`TrafficStream`, `WriterPartitionHeartbeat`, `CaptureCapabilityProbe`) is the only
record-type discriminator, no payload carries a partition, `heartbeatIntervalMillis` is
informational, and orchestration configures proxy and replayer expiration rules separately. A
traffic record is homogeneous in writer identity and connection, its observations are processed in
order, and the whole record commits only after all required processing for every observation
finishes. Periodic connection-record publication changes only where one `TrafficStream` ends and
the next begins; it adds no payload case or field. Managed operation adds no record type.

Automatic same-resource coverage restoration is unsupported, and the envelope has exactly the
three payload cases above. Any future implementation would need evidence shaped like:

```
CaptureCoverageEstablished {
    captureDomainId
    recoveryId
    captureDomainRecoverySequence
    coverageIntentId
}
```

Whether that evidence becomes a new `CaptureRecord` payload or uses another durable channel is an
open design decision. If it enters the traffic topic, it must be written and acknowledged
independently on every partition, committed only after validation and semantic effect, and — like
any wire-incompatible format change — placed on a new topic under the base protocol's new-topic
rule. The current protocol must not write this unrecognized payload into the traffic topic.
`CaptureCoverageEstablished` is a record name; "coverage marker" is informal shorthand only.

If a record change is wire-incompatible with the format assigned to an existing topic, the
deployment must use a new topic. Emptying the consumer group, entering a no-capture interval, or
rolling every binary does not permit reuse.

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
  trafficKafkaClusterId: ...
  trafficTopicId: ...
  replayBoundaryPlanId: ...
  nonCapturingSince: ...
  captureRecoveryTimestamp: ...
  completenessCertificateRevision: ...
  unresolvedGrantCount: ...
  trafficAffectingWorkloadCount: ...
```

The status answers whether capture is complete now, whether the workflow may ever capture again,
and which recovery is current; logs and metrics keep diagnostic history. Once
`CaptureWorkflowTerminal=True`, it is immutable for that resource UID and
`CaptureCoverageComplete` can never return to true. The timestamps are control-plane audit values
and never enter broker-time liveness or expiration calculations.

An automatic same-resource workflow is incompatible with an immutable terminal condition. Before
implementing one, choose and document a resource model: either every recovery creates a new
resource and the failed resource stays terminal forever, or a distinct nonterminal `RECOVERING`
state is introduced with exact transition rules. This document authorizes neither recovery nor
regrant on a resource already marked terminal.

### 6.2 Grant ledger

The controller durably records every capture grant:

```
captureDomainId; sourceIdentity; trafficKafkaClusterId; trafficTopicId
captureReplayResourceUid; processId; writerNodeId; podUid; containerId; nodeUid
recoveryId; captureDomainRecoverySequence; captureDomainFenceToken
replayBoundaryPlanId; captureActivationNumber; grantTime; lastObservedState; retirementState
```

The ledger is the inventory of capture activations that must be retired before recovery. Queries
for unresolved predecessors are keyed by `captureDomainId` across **all** topic identities — a
fresh run's new topic must not hide an unresolved grant. Replica counts, group membership, Pod
objects, load-balancer endpoints, and a recreated resource cannot reconstruct this history, so the
ledger (or an equivalent unresolved-grant tombstone) must survive resource deletion, forced
finalizer removal, and recreation with the same name.

The same domain store owns the lease/CAS fence for grants and the allocation of
`captureDomainRecoverySequence` and `captureDomainFenceToken`, so two resource UIDs cannot
concurrently grant capture for one domain. Every durable command intent records the resource,
recovery sequence, current fence token, target identity, exact payload or hash, and idempotence
key before the command is issued.

### 6.3 Completeness population

`CaptureCoverageComplete=True` requires:

- `CaptureWorkflowTerminal=False`;
- status identifies the current capture domain, recovery sequence, immutable Kafka cluster/topic
  IDs, and replay-boundary plan;
- every traffic-affecting live process reports the current resource UID, recovery, Kafka
  identities, replay plan, and activation;
- every process able to forward source traffic is capture-only;
- every non-capturing connection count is zero;
- every older grant is durably retired or belongs to a workload that cannot affect source traffic;
  and
- every required protocol and binary version is compatible.

Because terminal status is immutable, this transition exists only for initial healthy startup and
for replacement that never crossed an uncaptured interval. Any same-resource recovery design would
additionally require durable capture-retirement proof (§8.1–8.2) and traffic-retirement proof
(§8.3) for every old grant, the source-side condition in §8.4, and
`CaptureCoverageEstablished` acknowledged on every partition.

A process removed from the load balancer but retaining one connection remains in the population.
An unreachable process is never assumed healthy. Kafka heartbeats contribute no connection
membership: reachable processes report authenticated registry-derived status, and unreachable
processes keep their grants unresolved until §8.3 proves termination or fencing. Capacity
thresholds such as `minimumStrictReadyPerAz` govern availability decisions only; no threshold
below 100% means capture is complete.

The transition to `True` is write-proof-first. The controller persists an immutable completeness
certificate (resource UID, domain, recovery id and sequence, Kafka IDs, replay plan id,
grant-ledger revision, workload inventory, retirement-evidence references — plus, for any future
automatic recovery, the source-side retirement proof and acknowledged coverage offsets) before
publishing its revision with the condition. Every status mutation uses an immutable
status-transition intent carrying the current fence token, object UID, expected version, exact
patch, and certificate hash, validated by a server-side status-write validator with a linearizable
domain-authority check at write time; RBAC permits status writes only through that path. A crash
before the validated write leaves the condition unchanged, and on failover a true condition whose
certificate is missing or fails revalidation is treated as unknown.

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

Inability to publish a required record, an ambiguous producer outcome, heartbeat lapse, or another
capture compromise permanently closes the process's capture activation. Membership events after
the first usable assignment — revocation, partition loss, coordinator outage, delayed group
heartbeats, empty polls, polling failure — are not compromises.

The proxy closes capture immediately and applies `--capture-failure-policy`:

- `fail-closed` terminates without orderly retirement (Kafka acknowledgements are no longer
  trustworthy enough to complete it). The process forwarded no uncaptured traffic, so its
  replacement may stay in the current topic and replay run.
- `fail-open` enters `COMPROMISED_AWAITING_CONTROLLER_ACK`, reports through §4.4, and forwards no
  new source-bound traffic until the controller has durably marked the exact activation incomplete
  and terminal and acknowledged it. It then converts surviving and new TCP connections to
  uncaptured forwarding and can never return to capture. Acknowledgement-deadline expiration
  terminates it instead. A process that cannot report its own transition cannot begin managed
  pass-through; it terminates at the deadline, and the controller never infers uncaptured
  forwarding from disappearance.

A capture compromise does not by itself end the replay run. The run ends when the controller
authorizes uncaptured forwarding or otherwise records that an uncaptured interval occurred; that
makes the resource incomplete and terminal, unable to grant capture, establish coverage, or
authorize a snapshot.

Fresh-run startup after an uncaptured interval has two load-bearing preconditions:

1. **Kafka input isolation.** A new topic with a non-reused name and topic ID, partition start
   offsets fixed in the trusted replay plan before any grant, the new replayer reading only that
   topic, checkpoints bound to the topic ID and plan, and no reuse of the ended topic.
2. **Source-side quiescence.** The source-specific in-flight retirement condition in §8.4 for
   every failed-run process, established before the fresh snapshot begins. A new resource, Pod
   termination, or elapsed controller time is not that barrier.

Until these are implemented, "start a fresh workflow" means remain stopped at an external,
operator-verified restart boundary; the controller must not automatically authorize the new
snapshot.

### 7.3 Replacement without an uncaptured interval

A clean scale-down, planned replacement, hard process loss, or `fail-closed` failure may remain in
the current topic and replay run when no source traffic was forwarded without capture. The
controller selects this path only when durable state proves the ended process was never authorized
for pass-through and routing never intentionally selected pass-through capacity. A strict hard
crash or `fail-closed` exit satisfies the data-plane condition, because pass-through is impossible
without the §4.4 acknowledgement. If the controller cannot prove the condition, it must use §7.2.

Replacement order:

1. The replacement starts as a fresh process with a fresh `captureActivationId`, configured for
   the current topic and replay plan.
2. It completes the capability probe, joins the group, receives an assignment, creates the
   assignment's writer identities, and receives acknowledgement for their initial heartbeats.
3. Only then may it accept new source connections and publish to the current topic.
4. A healthy old process leaves new-connection routing and retires its connections and writer
   identities. A failed old process stays in the grant ledger until termination or fencing is
   proved; the replayer expires only its already-known incomplete state under the base heartbeat
   rules.
5. Records the old process already submitted remain valid in the current run, including records
   appended after the replacement starts; distinct writer identities keep them unambiguous.

No new topic, snapshot, replay plan, checkpoint namespace, or replayer run is required. If policy
instead lets the replacement capacity forward uncaptured traffic, §§7.1–7.2 apply and the run
ends.

## 8. Suppression and ended-run capture-activation retirement

### 8.1 Healthy suppression

A proxy may acknowledge `CAPTURE_SUPPRESSED_AND_QUIESCENT` only when:

1. eligibility to accept new captured connections for the old activation is irreversibly closed;
2. every old `(writerNodeId, partition)` continues traffic and heartbeats while its connections
   drain;
3. every closed connection completed base connection retirement: Netty reported closure, no
   further network traffic, terminal observation submitted after all earlier observations, Kafka
   acknowledged all of them, and only then was the connection removed;
4. every old connection registry is empty;
5. every publisher lane entered `RETIRING` with its periodic heartbeat publisher quiescent;
6. every remaining previously accepted send completed successfully;
7. every publisher lane entered `RETIRED`;
8. the producer is closed; and
9. none of the activation's writer identities can be used again.

Replacement capture authority goes to a fresh process with a fresh `captureActivationId`. The
source-side barrier and Kubernetes traffic retirement remain separate fleet-level obligations
(§§8.3–8.4).

Healthy suppression and other planned shutdowns use the base protocol's orderly retirement timing.
With the default 300-second internal hard stop, a managed deployment sets
`terminationGracePeriodSeconds` to at least 330 seconds so the proxy's watchdog can flush
diagnostics and halt before Kubernetes forces termination; deployments that change the intervals
must keep the grace period longer than the internal deadline. Capture compromise, event-loop
death, OOM-like failure, and corrupted ownership never use this retirement path.

### 8.2 Failed or ambiguous producer

A process with a failed or ambiguous send cannot acknowledge healthy suppression: stopping new
submissions does not prove old Kafka requests disappeared. Its capture activation is permanently
closed and never regranted. A `fail-open` process may stay alive in pass-through while coverage is
incomplete; if it cannot reliably report or enforce the closed latch, the controller terminates
the process, container, or host and confirms termination rather than observing API-object
deletion.

Process death proves no new sends originate afterward, not that an already transmitted request was
retracted. During no-gap replacement such a record remains valid under its original writer
identity; after an uncaptured interval the fresh run's separate topic confines it to the ended
run. `producerMaxBlockTime`, `producerDeliveryTimeout`, and safety margins are useful operational
waits, not correctness proofs.

### 8.3 Kubernetes traffic retirement and fencing

Capture retirement (§§8.1–8.2) stops new capture submissions. Traffic retirement proves the
workload can no longer forward source traffic; it gates coverage establishment and the snapshot.

Kubernetes routing and object-lifecycle signals are not proof:

- Service removal or unreadiness stops new routing but does not close existing connections;
- a deletion timestamp, terminating EndpointSlice entry, replacement Pod, or absent Pod object
  does not prove the old process stopped;
- force deletion is never process-termination evidence; and
- a `NotReady` or unreachable node is not assumed dead after a timeout.

The controller may mark a workload traffic-retired only after recording one of:

1. **Trusted local quiescence after healthy suppression:** the authenticated process with the
   exact Pod UID, `processId`, resource UID, recovery, topic, and activation reports its one-way
   latch closed, listener closed, no source-affecting connections or queued submissions, and
   publisher quiescent — bound to the container ID and node UID in the grant ledger.
2. **Runtime-confirmed termination:** fresh kubelet or container-runtime evidence for the exact
   Pod UID and container ID on a reachable node confirms the container exited.
3. **Infrastructure fencing:** the node or compute instance is powered off, terminated, or fenced
   in a way that cuts existing connectivity to both Kafka and the source.

A `PASS_THROUGH_COMPROMISED` process cannot use local quiescence; it needs runtime-confirmed
termination or fencing before a fresh workflow can declare capture ready. Pod-name reuse,
ReplicaSet convergence, load-balancer removal, graceful-deletion timeout, and controller
observation timeout satisfy nothing; an unreachable node keeps the workload traffic-affecting
until fencing succeeds.

The ledger records the evidence type, workload identity, observation time, and accepting recovery.
Recovery identity includes the resource UID, so a recreated resource cannot adopt or discard old
grants by name; a finalizer normally retains the resource while grants are unresolved but is not
the durable store. Leader election is availability only — every status mutation passes §6.3's
server-side validation, and every proxy mutation carries the full identity envelope; a paused
former leader cannot pass the current-token check even with otherwise-current field values.

### 8.4 Source-side quiescence requirement

Kafka topic isolation does not prove that a source request transmitted before process death cannot
finish later. Before coverage can become complete or a snapshot can begin, the workflow must
establish a conservative source-side retirement condition covering:

- a request fully transmitted to the source but not yet completed;
- a source response in flight when the proxy was killed; and
- force-closed long-lived connections.

Acceptable proof is source-specific, for example: a source-side barrier ordered after every
earlier operation from the retired proxies; source-visible operation identities plus an
authoritative query proving no old operation remains executable; or a source-enforced maximum
operation lifetime followed by that complete interval after the last possible old send. Proxy
connection closure, Pod termination, a `preStop` hook, `terminationGracePeriodSeconds`, or a
controller timeout is not sufficient. If the source cannot prove completion, cancellation, or an
enforced bound, recovery remains incomplete. This contract requires the proof but does not
automate it.

## 9. Fresh-run topic and automatic-coverage constraints

### 9.1 Fresh-run Kafka boundary

After an uncaptured interval, the controller:

1. under the current `captureDomainFenceToken`, allocates the next
   `captureDomainRecoverySequence` and `recoveryId`;
2. creates and verifies a Kafka topic with a new, non-reused name and topic ID;
3. completes any inert topic-setup writes;
4. records each partition's broker end offset as its start offset before granting capture;
5. persists an immutable replay-boundary plan (cluster ID, topic ID, topic name, partition start
   offsets, capture domain, recovery identity); and
6. issues no capture grant until that plan is durable.

No ended-run process is configured to publish to the new topic; delayed sends stay in the ended
topic. The topic name and ID are never reused. Pass-through processes may still be forwarding
while coverage is false — they block coverage and the snapshot but cannot move the new topic's
fixed replay boundary.

### 9.2 New capture grants

After the plan is durable, the controller binds eligible processes to the new topic and plan and
authorizes probing. Each process creates a fresh `captureActivationId`, probes with
`writerNodeId = captureActivationId + ":PROBE"`, joins the group only after every probe is
acknowledged and producer configuration validates, then — per assignment — chooses a new
`assignmentSequence`, acknowledges the identity's initial heartbeat on every permitted partition,
and only then accepts new captured connections. Existing pass-through connections never upgrade in
place; they drain or are force-closed.

### 9.3 Coverage-establishment requirements for automatic recovery

An automatic implementation may establish coverage only after every remaining traffic-affecting
process is strict in the current topic and plan, every ended-run workload is traffic-retired under
§8.3, and all non-capturing connections are gone. The controller persists one immutable coverage
intent — unique `coverageIntentId`, the exact
`CaptureCoverageEstablished{captureDomainId, recoveryId, captureDomainRecoverySequence,
coverageIntentId}` payload and hash, and one obligation per partition. The current fence holder
claims the obligations and calls
`emitCaptureCoverageEstablished(coverageIntentId, exactPersistedPayload)`, which validates current
authority and retries identical bytes after an ambiguous send.

The record settles no connection and repairs no missing traffic; it states that complete capture
already existed for the named recovery and topic before that partition offset. The resource
condition becomes true only after every partition copy is acknowledged, the recovery, topic, and
plan remain current, the population revalidates, and no new compromise occurred during fan-out.
Partial fan-out remains incomplete and retries idempotently.

## 10. Managed replayer rules and bootstrap

### 10.1 Topic-bound run

A managed replayer is created with one expected Kafka cluster ID, topic ID, and replay-boundary
plan, and consumes only that topic. Startup or checkpoint recovery fails closed on any mismatch.
The replayer does not infer its run from writer node IDs and does not compare numeric epochs. It
never changes topics during a run; recovery after an uncaptured interval creates a new run for the
new topic.

### 10.2 Trusted replay-boundary plan and snapshot binding

Before any capture grant for a fresh run, the workflow durably creates an immutable plan:

```
replayBoundaryPlanId
captureDomainId
trafficKafkaClusterId
trafficTopicId
trafficTopicName
partitionStartOffsets
recoveryId
captureDomainRecoverySequence
```

The workflow creates and verifies the topic, completes all inert setup writes, then records each
partition's broker end offset before granting capture. No new-run activation may publish before
those offsets are durably stored, and no later setup write may move the boundary. The offsets are
normally zero but are defined as broker offsets so acknowledged setup records never become replay
input.

The plan deliberately replays every request captured after its boundary, including requests whose
effects may already appear in the snapshot. That duplication fits the at-least-once contract;
moving the offset forward could omit a request captured before the snapshot cut but forwarded to
the source afterward.

After a snapshot is accepted, its manifest binds:

```
snapshotId
replayBoundaryPlanId
completenessCertificateRevision
coverageEstablishedOffsetByPartition // present only when coverage establishment is supported
```

This finalizes the plan without changing its domain, Kafka identity, or start offsets. The
replayer receives the finalized plan as trusted bootstrap. Checkpoints persist the capture domain,
recovery sequence, Kafka cluster/topic IDs, start offsets, and plan identity; restart never
re-infers them from retained traffic.

### 10.3 Kafka retention

The replayer cannot infer the pre-grant boundary from the first retained record, so the durable
replay plan is required for every managed snapshot and restart.

## 11. Managed fresh-run snapshot workflow

A source snapshot is usable only when:

1. `CaptureCoverageComplete=True`;
2. the workflow records the current recovery, topic ID, and replay plan;
3. the complete-population check succeeds immediately before snapshot start;
4. the condition, topic, and plan remain unchanged throughout; and
5. the complete-population check succeeds again after completion.

Reject the snapshot if capture becomes false or unknown, the recovery, topic, or plan changes, a
granted process becomes unreachable, or the traffic-affecting inventory changes ambiguously.

The accepted manifest stores the replay plan from §10.2; that manifest — not the resource
condition at some later time — identifies what the replayer may consume. Replay always starts at
the pre-grant boundary, not at a position sampled during the snapshot, so new-run requests racing
the snapshot are replayed rather than omitted: duplicates are possible, holes are not.

## 12. Automatic recovery requirements

Automatic recovery is unsupported until the resource-model choice in §6.1 is resolved: either
every recovery is a new resource (terminal resources stay immutable), or a nonterminal
`RECOVERING` state is defined with exact transition and failure rules.

For an incident that actually forwarded uncaptured traffic, the required order is:

1. Capture is compromised under `fail-open`; the proxy enters
   `COMPROMISED_AWAITING_CONTROLLER_ACK` and blocks uncaptured forwarding.
2. The controller validates the exact activation, allocates recovery R, durably sets coverage
   false and terminal, and acknowledges.
3. The proxy enters `PASS_THROUGH_COMPROMISED` and converts surviving and new connections to
   uncaptured forwarding.
4. Strict replacement capacity starts in fresh processes.
5. Every previous activation is retired under §8.1 or §8.2.
6. The controller creates the new immutable topic, completes inert setup, records pre-grant end
   offsets, and persists the replay plan.
7. It then grants capture to eligible proxies.
8. Proxies become `CAPTURE_AUTHORITATIVE` after probing, assignment, and initial heartbeats.
9. Remaining pass-through workloads become traffic-retired under §8.3.
10. Source-side in-flight retirement is established under §8.4.
11. The controller persists a coverage intent and fans `CaptureCoverageEstablished` to every
    partition.
12. After all acknowledgements and revalidation, the resource becomes complete.
13. A new snapshot is taken and bound to the plan.
14. A new replayer run starts from that plan and consumes only the new topic.

At every stage, failure leaves the condition false or unknown. No timeout skips a proof.

## 13. Implementation requirements

Terminal-on-gap controller behavior, immutable Kubernetes identity, command fencing, and durable
failed status are required. A new immutable Kafka topic, trusted replay plan, and topic-bound
checkpoints are required for every managed fresh run. Coverage re-establishment and same-resource
replacement-snapshot automation are not specified.

| Change | Requirement | Required behavior |
|---|---|---|
| Managed configuration | Required | `suppressCaptureByDefault`, process-wide `--capture-failure-policy`, positive `trafficStreamFlushInterval` `F` (default five seconds), 60-minute default connection lifetime, and the base orderly-retirement timing configuration. |
| Separate status/control interface | Required | Local state plus authenticated idempotent commands off the source listener; compromise reporting over a non-Kafka push/watch path with polling reconciliation; acknowledge only after durable terminal status. |
| Kafka group assignment | Required | Kafka-provided assignor; no custom assignor, `userData`, assignment metadata, readiness state, minimum size, or startup quorum; stickiness/cooperative movement optional. |
| Capture Replay status | Required | Complete/false/unknown coverage, immutable terminal status, recovery identity, transition timestamps. |
| Durable grant ledger | Required | Every process, activation, writer identity, grant, and retirement state persisted across failover. |
| Completeness certificate | Required | Immutable evidence persisted before atomically publishing its revision with `CaptureCoverageComplete=True`; failover treats missing/invalid evidence as unknown. |
| Traffic-affecting inventory | Required | Track load-balanced processes, draining connection sets, off-LB live connections, unreachable grants. |
| Kubernetes workload identity | Required | Track Pod UID, container ID, node UID, process identity; never retire by Pod name or replacement readiness. |
| Kubernetes fencing | Required | Traffic retirement only via trusted quiescence, runtime-confirmed termination, or connectivity-cutting fencing; force deletion and node timeout insufficient. |
| Controller command fence | Required | Domain fence token, immutable command intents, immutable Pod/process targeting, stale same-recovery command rejection independent of leader election. |
| Record schema | Required | `CaptureRecord` envelope with the `payload` oneof as the only discriminator; informational `heartbeatIntervalMillis`; no partition in any payload; traffic payloads homogeneous in writer and connection; periodic publication changes only record boundaries; whole-record commit. |
| Proxy state machine | Required | Immediate `fail-closed` termination; managed `fail-open` gated on durable controller acknowledgement; acknowledgement-timeout termination; healthy suppression; permanent failed-activation rules; last-usable-assignment routing; fresh-process replacement; concurrent draining writer identities. |
| Topic-bound replayer | Required | Consume only the plan's immutable topic; reject bootstrap or checkpoints for another topic or plan. |
| Snapshot replay plan | Required | Persist capture domain, recovery sequence, immutable Kafka IDs, and pre-grant partition start offsets. |
| Capability probe | Required | Base acknowledged per-leader probe with `writerNodeId = captureActivationId + ":PROBE"` and the assignment-scoped initial-heartbeat gate, not metadata connectivity. |
| Kafka clock-skew enforcement | Required | Same `E`/`S` to proxies and replayers; node monitor against `S`; block unhealthy broker startup; stop skewed brokers locally; publish `ClockSkew`; refuse or terminally fail without attestation. The replayer independently terminates on observed backward movement greater than `S`. |
| Replayer fatal-termination alarms | Required | Final in-process fatal metric best-effort; alert on unexpected restarts; correlate exit code `80`; qualify the kube-state-metrics interfaces per version. |
| Source retirement proof | Required before a fresh snapshot | Define how forced termination excludes source operations completing after the recovery boundary. |
| Security | Required | Authenticate and authorize every mutation and control-record emission. |
| Observability | Required | Export terminal failure, inventory, compromise, suppression, connection-record age/lateness, connection-set-drain, rejection, topic-boundary, and coverage fan-out metrics. |

## 14. Failure boundaries

- Controller unavailability never delays local capture closure; it does prevent managed
  `fail-open` from beginning uncaptured forwarding. Without durable acknowledgement before the
  deadline, the proxy terminates.
- Existing strict proxies continue their granted run while the controller is down; new processes
  remain suppressed.
- Unexpected loss of a `CAPTURE_AUTHORITATIVE` process does not end the run when no uncaptured
  forwarding occurred (§7.3).
- A stale command — lower recovery sequence, lower fence token, unvalidated intent, or wrong
  topic — cannot reactivate a superseded workflow.
- `CaptureWorkflowTerminal=True` is immutable; every later grant or coverage command for that
  resource UID is rejected.
- Load-balancer removal and Pod deletion are not process termination; force-deleted Pods and
  unreachable nodes remain traffic-affecting until runtime evidence or fencing is recorded.
- A fresh workflow cannot declare capture ready while failed-run pass-through capacity remains
  traffic-affecting.
- A former leader cannot issue an accepted command even with current-looking fields: it lacks the
  current fence and cannot persist a current intent.
- A percentage below 100% can authorize capacity decisions but never complete capture.
- Absence of the replayer's final fatal metric does not suppress the Kubernetes restart or
  exit-code alarm.
- A lost activation without enough later broker-time evidence is not retroactively repaired.
  Same-run replacement expires only already-known incomplete state; after an uncaptured interval,
  the fresh workflow uses a new topic and satisfies §7.2.

Every managed fresh run additionally requires a new non-reused topic name and ID, a fresh snapshot
and plan bound to that topic, a new replayer run consuming only it, and checkpoints unusable
against any other topic or plan. Any automatic-coverage design must keep the resource incomplete
while coverage is acknowledged on only some partitions.

## 15. Validation plan

Validation requires the terminal-failure, immutable-identity, stale-command, force-deletion, and
ended-workload-retirement cases, plus new-topic boundary, trusted replay plan, and topic-bound
checkpoint tests for every fresh-run implementation. Coverage restoration on a terminal resource
and automated replacement-snapshot orchestration are unsupported and have no acceptance tests
here.

### 15.1 Deterministic tests

- Stale, duplicate, and out-of-order controller commands.
- A paused former leader resuming after a newer recovery started; issuing a command with current
  recovery/topic but an older fence token; inventing a higher token without a validated intent;
  attempting a status write with a current object version but an old fence, rejected server-side.
- Resource deletion and recreation with the same name but different UID; forced finalizer removal
  followed by reconstruction from the surviving unresolved-grant ledger.
- Pod IP reuse receiving a delayed command addressed to the previous Pod UID and process id.
- Controller failure after certificate persistence but before the status compare-and-swap;
  failover observing a true condition with missing or invalid certificate.
- Ambiguous producer outcome never returning to `CAPTURE_AUTHORITATIVE`; permanent activation
  closure after any application-visible compromise.
- Unexpected active-Pod loss permitting same-run replacement when no uncaptured traffic occurred;
  ambiguous pass-through-authorization evidence preventing that path.
- Terminal status immutable after uncaptured forwarding is authorized; every later same-resource
  grant or coverage command rejected; a fresh resource unready while failed-run pass-through
  capacity is traffic-affecting; the failed topic never reused.
- A delayed ended-run record confined to the ended topic and invisible to the fresh run.
- Bootstrap and checkpoint restart preserving the trusted domain, sequence, Kafka identities, and
  plan identity.
- A fresh snapshot unauthorized until the source-specific retirement barrier succeeds.
- Managed `fail-open`: capture closes immediately; no uncaptured forwarding while acknowledgement
  is pending; pass-through only after durable terminal status and matching acknowledgement;
  notification loss, duplication, delay, failover, and lost responses handled idempotently.
- Planned pass-through routing only after durable incomplete and terminal status.
- Healthy suppression acknowledged only after complete base connection retirement, empty
  registries, and successful producer futures; failed or ambiguous sends prevent it.
- Probes use `writerNodeId = captureActivationId + ":PROBE"` and create no replayer state.
- The built-in assignor operates without custom metadata; startup capacity uses authenticated
  `captureReady` status, not membership count.
- Every assignment uses a new `assignmentSequence` and writer identity; existing connections keep
  theirs; rapid rebalances drain several identities independently, keyed by
  `(writerNodeId, partition)`.
- Heartbeats contain no connection identities, never reset an accepted baseline, and never list,
  omit, complete, or reopen a connection; drained lanes quiesce heartbeats, finish accepted sends,
  and retire locally.
- Controller completeness uses authenticated registry status, never Kafka-heartbeat-derived
  connection membership.
- Managed traffic records process observations separately while withholding commit until every
  observation's processing finishes.
- A nonempty low-volume record has one `F`-deadline callback; observations at or after the
  deadline enter only a successor; continuous activity does not extend the deadline; idle
  connections emit no empty records.
- Off-load-balancer live connections block completeness; unreachable processes produce false or
  unknown, never true.

An automatic-recovery implementation additionally requires: coverage fan-out idempotence and
partial failure; same-resource recovery only under an explicitly defined `RECOVERING` state; and
replacement-snapshot authorization and rejection.

### 15.2 Testcontainers Kafka

- An expected-topic multi-observation record staying uncommitted until every observation finishes.
- Restart from a trusted replay plan when no boundary record exists in the topic.
- Checkpoints rejecting a different domain, sequence, topic ID, or plan.
- An old producer request appended after fresh-run activation staying in the old topic; the new
  replayer consuming only the new topic.
- A request captured before snapshot start but forwarded after the cut remaining included, because
  replay starts at the pre-grant boundary.
- Rejection of a wire-incompatible format on an existing topic; operation of that format on a new
  topic.

Automatic recovery adds broker-restart, controller-failover, and duplicate coverage-record tests.

### 15.3 Live deployment tests

- Broker startup blocked by an unhealthy node clock; local broker stop before the `S` bound is
  consumed; `ClockSkew` condition, cordon, taint, and replacement without touching kubelet
  `Ready`.
- Workflow start refused without skew attestation; terminal failure and replay shutdown when
  attestation is lost mid-run.
- Immediate replayer termination on observed backward `LogAppendTime` movement greater than `S`.
- Event-loop death producing exit code `80`, incrementing the restart signal, and firing the
  reason-specific alarm even without the final in-process metric; an ordinary `System.exit` not
  classified as event-loop loss; qualification failing when required kube-state-metrics interfaces
  are absent.
- Controlled strict-proxy replacement with no uncaptured forwarding; AZ capacity below policy and
  controller-authorized pass-through routing; managed `fail-open` forwarding uncaptured traffic
  only after durable acknowledgement; replacement handoff requiring client reconnect.
- A connection surviving to the 60-minute default lifetime and blocking recovery until closed or
  force-closed; a long-lived low-volume connection publishing successive bounded-duration records.
- Pod deletion without process death; force deletion leaving the process running; a node partition
  leaving the Pod and connections alive; Pod-name and ordinal reuse while the old grant is
  unretired; node or host fencing when termination cannot be confirmed.
- Controller restart with grant-ledger reconstruction; resource deletion and recreation
  discovering unresolved predecessor grants by `captureDomainId` across all topic identities.

Automatic recovery adds: snapshot rejection when recovery changes mid-snapshot; full recovery
followed by a replayer launched from the durable plan alone.

## 16. Unresolved implementation and automation requirements

Before a deployment can claim this contract:

1. Implement and verify the §4.5 capability probe and initial-heartbeat gate.
2. Implement §7.2's fresh-run Kafka isolation: new non-reused topic, pre-grant offsets in a
   trusted plan, topic-bound checkpoints.
3. Define the source-specific in-flight retirement barrier required before a fresh snapshot.
4. Define durable replay-plan and checkpoint storage.
5. Define durable terminal-status and grant-ledger storage, canonical `sourceIdentity` assignment,
   the one-to-one `captureDomainId` authority, command-intent storage, and domain-fence failover
   semantics.
6. Finalize Kubernetes termination-evidence adapters, durable unresolved-grant storage outside the
   custom-resource lifecycle, and infrastructure fencing for unreachable nodes.
7. Decide whether fatal replayer termination needs a strict no-loss event journal beyond the
   metrics alarms; if so, define the watch, event identity, storage, replay, and acknowledgement
   contract.
8. Select the compromise-notification transport and the default finite acknowledgement deadline.
   The correctness contract is fixed: capture closes locally at once, no uncaptured forwarding
   before durable acknowledgement, and deadline expiry terminates the proxy.
9. Define the authenticated status or certificate and ledger transition that make ordinary
   assignment-scoped publisher-lane retirement durable; §8.1 covers whole-process suppression, but
   retiring one older writer identity inside an active process has no controller-visible record
   yet.

Automatic recovery within a failed workflow additionally requires automating and durably recording
the §8.4 source-side proof after forced termination, and finalizing coverage-establishment record
rollout and orchestration.

Until those decisions are complete, an uncaptured interval permanently leaves the resource
incomplete and terminal: no fresh-run grant, no return to coverage, no new snapshot under that
resource. A fresh workflow may proceed only after §7.2's Kafka-isolation and source-quiescence
preconditions are externally satisfied and recorded.
