# Managed Fleet Capture Recovery

**Status: managed terminal-failure and fresh-run contract (2026-09-14).**

This document extends the standalone
[Proxy Capture Protocol](proxyCaptureProtocol.md) for a controller-managed Kubernetes fleet. The
standalone protocol uses writer-partition heartbeats, self-emitted `NoMoreWrites` for permanent
`(writerNodeId, partition)` retirement, explicit retention when neither broker-time expiration nor
terminal completion can be proved, and an irreversible local capture gate. Heartbeats carry no
connection state.

`WriterPartitionHeartbeat` is the fixed protobuf name. Managed mode additionally requires that
record to carry `captureSessionId`, as shown in §5.

Managed operation requires:

- `captureActivationId` identifies one capture-authoritative process lifetime, while every new
  Kafka group assignment increments `assignmentSequence` and creates the `writerNodeId` used by
  newly opened connections;
- the group uses a Kafka-provided assignment strategy and carries no custom assignor metadata,
  subscription `userData`, readiness state, minimum member count, or startup quorum; stickiness and
  cooperative movement are optional load-balancing optimizations only;
- Kafka membership only load-balances new connections; after the first usable assignment, the
  proxy retains its last usable assignment through revocation, partition loss, coordinator outage,
  and membership-poll failure until Kafka supplies a replacement;
- existing connections retain their original `writerNodeId`; old writer identities continue
  heartbeats while draining and finish with self `NoMoreWrites`;
- `NoMoreWrites` is partition-scoped, self-emitted after the connection registry for that
  `(writerNodeId, partition)` is empty and the
  publisher retirement barrier has quiesced periodic heartbeat callbacks, and terminal for that
  `(writerNodeId, partition)`; each closed connection completes the base connection-retirement
  protocol first, and the producer closes only after `NoMoreWrites` is acknowledged;
- the base protocol may expire already-known incomplete connection state from a skew-adjusted Kafka
  `LogAppendTime` horizon only while the fleet's declared clock-skew bound is healthy;
- within the standalone design's non-streaming source-execution scope, strict mode captures a
  complete request before source execution and checks acknowledged-heartbeat freshness before
  forwarding;
- Critical Mutation Traffic is classified according to the standalone protocol's configured
  HTTP-method predicate;
- producer configuration enforces idempotence, `acks=all`, and an ordering-preserving
  `max.in.flight.requests.per.connection`; deployment configuration cannot weaken those settings;
- the exact connection registry remains local to the proxy and is never serialized into a heartbeat;
  connection add and acknowledged retirement use the standalone design's narrow connection-local
  lifecycle boundary while ordinary packet capture remains concurrent;
- `--capture-failure-policy=fail-closed` terminates immediately after Kafka publication or capture
  is compromised, without attempting orderly retirement;
- `--capture-failure-policy=fail-open` permanently abandons capture for the whole process, but
  permits no uncaptured source forwarding until the controller has durably recorded incomplete
  terminal coverage and acknowledged that exact capture activation; after acknowledgement it keeps
  surviving TCP connections open, accepts new TCP connections without capture, and raises a
  persistent gap alarm;
- fatal replayer termination is alarmed from Kubernetes-observed container state and the
  reason-specific halt code; the replayer's final in-process OpenTelemetry metric remains
  best-effort; and
- an uncaptured source interval starts a new capture and replay run with a fresh source snapshot.

Any terminal capture failure after a permitted retry terminates the entire capture workflow. A
`fail-closed` proxy terminates immediately. A `fail-open` proxy immediately
abandons capture but blocks uncaptured source forwarding while it reports the compromise. The
controller durably marks the resource incomplete and terminal before acknowledging that exact
activation. Only then may the proxy make its irreversible whole-process transition to uncaptured
forwarding. If acknowledgement does not arrive within the configured finite deadline, the proxy
terminates instead. The controller never grants capture again under that resource UID and never
returns its coverage condition to true. Recovery means starting a fresh capture, snapshot, and
replay workflow from an explicit isolated run boundary. The controller may keep acknowledged
failed-run pass-through capacity alive for availability, but that capacity cannot participate in
the replacement capture run.

Automatic return to complete fleet coverage within a failed resource is unsupported. Sections 9.3
and 12 define constraints that any implementation must satisfy but do not authorize that
automation. Every managed fresh run requires session identity, a trusted replay plan, session-aware
checkpoints, and a source-side snapshot barrier. A fresh run that reuses the failed run's Kafka
topic also requires per-partition reset records. Section 7.2 defines the restart preconditions.

## 1. Goals

The managed contract adds five capabilities:

1. Durably mark capture incomplete before any planned or failure-triggered uncaptured source
   traffic is forwarded.
2. Determine whether every process still capable of affecting the source is capture-only.
3. Make controller failure conservative: stall, remain incomplete, or exit, but never silently
   assert complete capture.
4. Bind every managed fresh run to one `captureSessionId`, one immutable Kafka input boundary, one
   trusted replay-boundary plan, and session-aware checkpoints.
5. When a fresh run reuses a Kafka topic, establish that boundary with one immutable reset intent
   acknowledged on every partition.

This contract does not support automatic recovery. Supporting it requires two additional
capabilities:

1. Drive a fleet automatically through retirement, fresh-session activation, source-side
   quiescence, snapshot creation, and replay startup after an uncaptured interval.
2. Restore and publish complete fleet coverage automatically, including optional same-resource
   recovery if a nonterminal recovery state is explicitly defined.

Neither scope promises global ordering across connections or partitions or makes missing traffic
replayable.

## 2. Why the base protocol is insufficient for automation

The standalone protocol exposes local facts rather than one fleet verdict. Managed operation must
account for these conditions:

- the controller acknowledges a capture compromise only after recording it durably;
- a process removed from the load balancer may retain an existing connection and continue mutating
  the source for an hour or more;
- killing a producer prevents new sends but does not retract a Kafka request already transmitted
  to a broker; client delivery and blocking timeouts are not broker fencing;
- a delayed record from an ended capture activation can first appear on a partition after a new
  fleet boundary, so a writer identity not yet seen on that partition does not classify the record;
- a replayer starting after Kafka retention removed an earlier boundary record cannot safely infer
  the current capture generation from the first traffic record it sees; and
- loss of the relevant capture activations without self `NoMoreWrites`, followed by insufficient
  later broker-time evidence on a partition, has no ordinary settlement path. Automated recovery
  must explicitly abandon the incomplete interval rather than infer completion from replacement
  capacity.

These gaps require an external authority plus an explicit data-plane session identity. A process
timeout alone is not that authority.

### 2.1 Kafka broker clock-skew requirement

The base protocol's broker-time expiration proof depends on an enforced bound `S`: the maximum
permitted backward movement between Kafka `LogAppendTime` values at increasing offsets in one
partition. This is an operational safety dependency, not merely a monitoring preference.

The orchestration layer supplies one agreed heartbeat publication interval, one agreed heartbeat
expiration interval `E`, and one agreed clock-skew bound `S` to every proxy and replayer in the run.
The default publication interval is 10 seconds and the default `E` is 30 seconds. The orchestration
layer configures the broker-node clock monitor against the same `S`.
The timestamp proof is valid only while those process parameters agree.

Every workflow-managed capture topic sets
`message.timestamp.type=LogAppendTime`. The proxy independently verifies that requirement before
joining its Kafka group: each per-leader capability probe supplies a producer timestamp of zero and
requires the acknowledgement metadata to contain a positive broker-assigned timestamp. A rejected
probe or an unchanged timestamp prevents capture startup.

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
Every connection registry, publisher lane, broker-time baseline, and `NoMoreWrites` lifecycle is
keyed by `(writerNodeId, partition)`.

Each `(writerNodeId, partition)` has one continuous accepted-heartbeat broker-time baseline.
Heartbeats contain no connection identities and update that baseline when timely, including while
the local connection registry is empty. They never end or reset the baseline. After the identity's
connections drain, valid `NoMoreWrites` permanently retires that identity and partition. A late
heartbeat permanently compromises the process; serialized heartbeat publication prevents a later
heartbeat from being submitted to restore freshness.

While that baseline remains timely, the replayer does not infer that any individual connection has
closed. Normal per-connection completion comes only from the connection's terminal
`CloseObservation`. The managed controller does not add a second connection-membership protocol.
This makes the proxy invariant load-bearing: Netty must close the real channel, the connection's
event-loop owner must publish the terminal observation after all earlier observations, and the
registry may remove the connection only after Kafka acknowledges all of them.

The Kafka reachability probe runs before a group generation exists and uses:

```text
writerNodeId = captureActivationId + ":PROBE"
```

`CaptureCapabilityProbe` confirms only that the proxy can publish to Kafka. It has no replay
meaning. The replayer does not treat its `writerNodeId` as a traffic writer identity, does not
create a heartbeat baseline for it, and does not create connection state.

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
capture and Kafka publication remain trustworthy follows the normative timing and failure policy in
[Proxy Capture Protocol §3.5](proxyCaptureProtocol.md#35-scale-down).

The proxy may retry Kafka while in `CAPTURE_RETRYING` only for a definite transient failure known
not to have compromised capture. All affected source forwarding remains capture-before-forward
during that interval. The proxy may return to `CAPTURE_AUTHORITATIVE` only after the base capability
probe succeeds again and every capture and publisher-health invariant is revalidated.

A heartbeat acknowledgement deadline expiring, an ambiguous producer outcome, inability to publish
a record required for capture, or any other compromise closes the capture activation immediately
and applies the configured process-wide capture failure policy. That process may never return to
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
       -> COMPROMISED_AWAITING_CONTROLLER_ACK
            -> PASS_THROUGH_COMPROMISED  // controller durably recorded the gap
            -> process exit              // acknowledgement deadline expired
```

A healthy controller-requested suppression has a separate path:

```
CAPTURE_AUTHORITATIVE -> SUPPRESSING -> CAPTURE_SUPPRESSED_AND_QUIESCENT
```

`CAPTURE_AUTHORITATIVE` is a local process state, not Kafka group subscription metadata. After
capability probing succeeds, the process joins the group and uses its first completed assignment
from a Kafka-provided assignment strategy. No custom assignor, subscription `userData`, assignment
metadata, member readiness state, minimum member count, or startup quorum participates. After that,
revocation, partition-loss callbacks, empty polls, coordinator outages, delayed consumer-group
heartbeats, and polling failures do not change capture state or stop connection acceptance. The
proxy retains its last usable assignment until Kafka supplies a replacement.

The controller applies fleet startup and availability thresholds using authenticated
`captureReady` status and its traffic-affecting workload inventory. Those thresholds never enter
Kafka subscription or assignment data.

Only the healthy suppression path may later create a fresh capture activation in the same process.
A process that entered `COMPROMISED_AWAITING_CONTROLLER_ACK` or
`PASS_THROUGH_COMPROMISED` because of heartbeat lapse, ambiguous producer outcome, or another
compromise never captures again. It must terminate before recovery can become complete.

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
captureCompromiseAwaitingAcknowledgement
captureCompromiseNotificationAge
captureCompromiseAcknowledged
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

Connection counts and draining-writer status come from the proxy's exact local connection
registries. The controller never reconstructs them from Kafka heartbeats: a heartbeat contains no
connection identities and proves only that its writer and partition can still publish acknowledged
records. If the authenticated process cannot report its local state, the controller does not infer
an empty registry from Kafka silence, a recent heartbeat, group departure, or replacement capacity.
The workload and its grants remain unresolved until one of the termination or fencing proofs in
§8.3 is recorded.

Authenticated, authorized, idempotent mutation endpoints include:

```
suppressCapture(recoveryId)
grantInitialCapture(recoveryId, captureActivationNumber)
forceCloseNonCapturingConnections(recoveryId)
acknowledgeCaptureCompromise(captureActivationId)
```

Same-topic fresh-run reset requires the reset emitter. Automatic coverage restoration and
same-resource regrant, if implemented, additionally require:

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

### 4.4 Compromise notification and acknowledgement

The first local event proving that capture is compromised closes the proxy's capture-authoritative
gate immediately. The proxy then reports its state without using the traffic Kafka cluster. A push
notification or controller-held event stream is the normal low-latency path. Status polling is a
reconciliation fallback: after observing the pending state, the controller still invokes the
explicit acknowledgement endpoint.

One capture activation can be compromised only once. The notification is therefore identified by
the existing immutable resource UID, Pod UID, `processId`, `captureActivationId`,
`captureSessionId`, recovery sequence, and controller fence; retries do not require a second event
identifier. The controller treats an exact duplicate idempotently.

Before acknowledging, the controller:

1. validates the reported identities and current domain fence against the grant ledger;
2. persists an immutable terminal-status intent for the exact capture activation;
3. durably sets `CaptureCoverageComplete=False`, `CaptureWorkflowTerminal=True`,
   `nonCapturingSince`, and the failure cause; and
4. verifies that the status write succeeded under the current domain fence.

Only then may it acknowledge the proxy. Receipt of the notification, a health-check failure, a log,
or a metric is not acknowledgement. If the durable update succeeded but the response was lost, a
retry returns the same acknowledgement. Controller failover reconstructs that result from durable
state.

While acknowledgement is pending, `captureReady=false`, `forwardingReady=false`, and no
source-bound traffic that has not already passed the capture-before-forward gate may be forwarded.
The proxy may retain TCP connections during the wait, but it cannot send their newly received
source-bound traffic. A matching authenticated acknowledgement permits the irreversible transition
to `PASS_THROUGH_COMPROMISED`; expiration of the configured finite acknowledgement deadline
terminates the process instead.

### 4.5 Capture capability

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
gate. The probe creates no replayer writer baseline, heartbeat state, or connection state. After a
group assignment completes, before accepting a captured client connection on an assigned
partition, the proxy also satisfies the base initial-heartbeat acknowledgement rule for the current
generation's `writerNodeId` and that partition.

## 5. Managed session fencing: data-plane record changes

Every session-fenced controller-managed record carries the capture session of the emitting capture
activation:

```
TrafficStream {
    nodeId = writerNodeId
    captureSessionId
    connectionId
    partition
    subStream[] {
        connectionObservationSequence
        ...
    }
}

WriterPartitionHeartbeat {
    writerNodeId
    captureSessionId
    partition
    heartbeatIntervalMillis
    emittedAtMillis?  // optional, diagnostic only
}

NoMoreWrites {
    captureSessionId
}

CaptureCapabilityProbe {
    writerNodeId = captureActivationId + ":PROBE"
    captureSessionId
    probeId
}
```

The Kafka record header for `NoMoreWrites` carries its authoritative assignment-scoped
`writerNodeId`, and Kafka record metadata supplies its authoritative partition. Neither value is
repeated in the protobuf body. A missing or malformed writer header makes the record inert.

`WriterPartitionHeartbeat.heartbeatIntervalMillis` is informational. Managed orchestration still
configures the proxy and replayer expiration rules separately.

The session field is optional only for unmanaged mode. A controller-managed process fails startup
if the configured wire version cannot carry it. One traffic record is homogeneous in writer
identity, session, partition, and connection. It may contain several ordered
`TrafficObservation` values. The replayer processes those observations separately, but for the
replayer Kafka can commit only the whole record. The record therefore remains uncommitted until all
required processing associated with every observation has finished. A heartbeat is one atomic
record and contains no connection identities, chunks, sequence number, or connection-lifecycle
boundary.

A fresh run that reuses a Kafka topic additionally requires the partition-scoped
`FleetCaptureReset` record. An implementation that supports automatic coverage restoration also
requires `CaptureCoverageEstablished`:

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
not establish ordering or authority. The replayer commits a valid control record only after its
validation and semantic effect complete. The Kafka offset remains indivisible and follows the
ordinary whole-record disposition rules.

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

An automatic same-resource workflow is not compatible with
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

`CaptureCoverageComplete=True` requires:

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

Any same-resource recovery design additionally requires:

- every old capture activation has durable capture-retirement proof under §8.1 or §8.2;
- every old traffic-affecting workload has durable traffic-retirement proof under §8.3;
- every traffic-affecting live process reports the current recovery and session;
- no previous-session process can still forward a source operation;
- the source-side retirement condition in §8.4 is satisfied for every old process; and
- `CaptureCoverageEstablished` has been acknowledged on every partition.

A process removed from the load balancer but retaining one existing connection remains in the
population. An unreachable process is never assumed healthy.

Kafka heartbeats do not contribute connection membership to this population. For a reachable
process, the controller uses authenticated status derived from the exact local registry. For an
unreachable process, it preserves the last unresolved grant and treats the workload as
traffic-affecting until §8.3 proves termination or fencing. A recent heartbeat also does not prove
that the process is accepting connections, remains reachable from the controller, or satisfies the
fleet-wide completeness population.

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
revocation, partition loss, coordinator outage, delayed consumer-group heartbeats, empty polls, and
membership-poll failure after the first usable assignment are not terminal capture failures.

The proxy closes capture immediately and applies `--capture-failure-policy`:

- `fail-closed` emits high-severity diagnostics and terminates without attempting connection or
  writer retirement, because Kafka acknowledgements are not trustworthy enough to complete that
  protocol.
- `fail-open` permanently closes the capture activation and enters
  `COMPROMISED_AWAITING_CONTROLLER_ACK`. It reports the compromise through §4.4 and forwards no new
  source-bound traffic until the controller has durably marked the exact activation incomplete and
  terminal and returned a matching acknowledgement. It then converts surviving existing and new
  TCP connections to uncaptured forwarding. It can never return to capture in that process.
  Acknowledgement-deadline expiration terminates the proxy instead.

If the failed process cannot report its own transition, it cannot begin managed pass-through. It
terminates when its acknowledgement deadline expires, while the controller durably marks the
resource incomplete and terminal from workload and grant-ledger evidence. If it can report, the
controller records the transition before acknowledging it. In neither case does the controller
infer that capture remained complete merely because the process disappeared or remained reachable.

Either terminal policy makes the capture resource terminal. It may continue
reporting and controlling failed-run pass-through capacity, but it cannot issue another capture
grant, emit a fleet reset, establish coverage, or authorize a source snapshot. A fresh workflow uses
a new resource UID and new run identity.

The fresh workflow cannot declare capture ready while a failed-run pass-through workload can still
receive or forward source traffic. Each such workload's proxy connection set must become empty, or
the workload must terminate or be fenced under §8.3.

Fresh-run startup also requires two load-bearing preconditions:

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

## 8. Suppression and ended-run capture-activation retirement

### 8.1 Healthy suppression

A proxy may acknowledge `CAPTURE_SUPPRESSED_AND_QUIESCENT` only when:

1. eligibility to accept new captured client connections for the old capture activation is irreversibly
   closed;
2. every old `(writerNodeId, partition)` continues existing-connection traffic and periodic
   heartbeats while its connections drain;
3. for every closed captured connection, Netty has reported closure, the kernel and Netty provide
   no further network traffic for that connection, its event-loop owner has submitted its terminal
   observation through the Kafka publisher after every earlier observation, Kafka has acknowledged
   all of those observations, and only then has the event-loop owner removed the connection from
   the active set;
4. every `(writerNodeId, partition)` connection registry belonging to the old capture activation is
   empty;
5. every publisher lane has entered `RETIRING` and its periodic heartbeat publisher is quiescent;
6. every remaining previously accepted producer send completed successfully;
7. terminal self `NoMoreWrites` is acknowledged for every old `(writerNodeId, partition)`;
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

Healthy suppression and other planned administrative shutdowns use the base protocol's orderly
retirement timing policy. Capture compromise, event-loop death, out-of-memory-like failure, and
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
nonmatching session is discarded and alarmed, even when its writer node id has not yet been observed
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
before a fresh workflow can declare capture ready. Any same-resource recovery design requires the
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

This proof is required before automatic recovery may return coverage to true and before a fresh
workflow may authorize its source snapshot. This contract requires the proof but does not automate
it.

## 9. Same-topic fresh-run reset and automatic-coverage constraints

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
current session. A higher sequence can supersede an earlier one only through an authorized
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
`captureActivationId + ":" + assignmentSequence`, acknowledges that identity's initial heartbeat
for every permitted partition, and only then accepts new captured client connections.

New connections use strict capture only after activation. Existing pass-through connections never
upgrade in place; the proxy connection set is allowed to drain, or those connections are
force-closed.

### 9.3 Coverage-establishment requirements for automatic recovery

An automatic recovery implementation may establish coverage only after every remaining
traffic-affecting process is strict in the current session, every ended-run workload is
traffic-retired under §8.3, and all non-capturing connections are gone. The controller then
persists one immutable coverage intent containing a unique `coverageIntentId`, the exact
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

Session mismatch is a consumer disposition for an ordinary traffic or
`WriterPartitionHeartbeat` record, not a separate wire record type. The disposition and metric
record the expected and observed session identities.

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
coverageEstablishedOffsetByPartition // present only when coverage establishment is supported
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

## 12. Automatic recovery requirements

Automatic recovery is unsupported until the resource-model choice in §6.1 is resolved. If terminal
resources remain immutable, every step below that grants a new session or restores coverage occurs
on a new resource under the capture-domain lease. If a `RECOVERING` state is introduced, its
transition and failure rules must be defined before this workflow is implementable.

For an incident that actually forwarded uncaptured traffic:

1. A proxy enters `CAPTURE_RETRYING`; forwarding remains strict and blocked as needed.
2. Retry is exhausted or capture is otherwise compromised; the configured policy is `fail-open`,
   so the proxy enters `COMPROMISED_AWAITING_CONTROLLER_ACK`, permanently closes capture, and
   blocks uncaptured source forwarding.
3. The controller validates the exact activation, allocates recovery R, durably sets coverage
   false and terminal, and acknowledges the transition.
4. The proxy receives the matching acknowledgement, enters `PASS_THROUGH_COMPROMISED`, and converts
   surviving existing and new TCP connections to uncaptured forwarding.
5. Strict replacement capacity is started and probed.
6. Every previous capture activation is retired by healthy suppression or the permanent
   failed-activation rules under §8.1 and §8.2.
7. The controller creates session S and chooses the trusted Kafka input branch:
   - **same topic:** persist one immutable reset intent, fan its exact
     `FleetCaptureReset{D,R,S,I}` payload to every partition, await every acknowledgement, and
     record start offset `resetOffset+1`; or
   - **distinct immutable topic:** create and verify the new topic namespace, complete inert setup
     probes, record the pre-grant broker end offset for every partition, and emit no fleet reset.
8. After the selected branch's complete boundary plan is durable, the controller grants S to
   eligible proxies.
9. Proxies become `CAPTURE_AUTHORITATIVE` after the base capability probe, normal group assignment,
   and initial-heartbeat acknowledgement.
10. Remaining pass-through processes and off-load-balancer connections become traffic-retired
    under §8.3.
11. Source-side in-flight retirement is established under §8.4.
12. The controller persists an immutable coverage intent and fans its exact
    `CaptureCoverageEstablished{D,R,S,I}` payload to every partition.
13. After all acknowledgements and revalidation, the resource becomes complete.
14. A new source snapshot is taken and bound to S.
15. A new replayer run starts from that snapshot's trusted replay plan.

At every stage, failure leaves the condition false or unknown. No timeout skips a proof.

## 13. Implementation requirements

Terminal-on-error controller behavior, immutable Kubernetes identity, command fencing, and durable
failed status are required. Session identity, trusted replay plans, and session-aware checkpoints
are required for every managed fresh run. Per-partition reset is required when reusing a Kafka
topic. Coverage re-establishment and same-resource replacement-snapshot automation are not
specified.

| Change | Requirement | Required behavior |
|---|---|---|
| Managed configuration | Required | Add `suppressCaptureByDefault`, the process-wide `--capture-failure-policy`, definite-uncompromised retry configuration, a 60-minute default maximum connection lifetime, and the base protocol's orderly-retirement timing configuration. |
| Separate status/control interface | Required | Expose local state and authenticated idempotent commands without sharing the source listener. Report capture compromise through a non-Kafka push or watch path with polling reconciliation; acknowledge only after durable terminal status is recorded. |
| Kafka group assignment | Required | Use a Kafka-provided assignor with no custom assignor, subscription `userData`, assignment metadata, member readiness state, minimum group size, or startup quorum. Treat stickiness and cooperative movement as optional load-balancing optimizations. |
| Capture Replay status | Required | Add complete/false/unknown coverage, immutable terminal-workflow status, recovery identity, and transition timestamps. |
| Durable grant ledger | Required | Persist every process, capture activation, assignment-scoped writer identity, grant, and retirement state across controller failover. |
| Completeness certificate | Required | Persist immutable evidence before atomically publishing its revision with `CaptureCoverageComplete=True`; failover treats missing or invalid evidence as unknown. |
| Traffic-affecting inventory | Required | Track load-balanced processes, processes whose connection sets are draining, off-LB live connections, and unreachable grants. |
| Kubernetes workload identity | Required | Track Pod UID, container ID, node UID, and process identity; never retire by Pod name or replacement readiness. |
| Kubernetes fencing | Required | Prove traffic retirement through trusted healthy quiescence, runtime-confirmed termination, or infrastructure fencing that cuts existing connectivity; force deletion and node timeout are insufficient. |
| Controller command fence | Required | Allocate a domain fence token, persist immutable command intents, target immutable Pod and process identity, and reject stale same-recovery commands independently of leader election. |
| Record schema | Required; reset conditional on topic reuse | Carry `captureSessionId` on every managed traffic, heartbeat, and proxy-completion record; carry informational `heartbeatIntervalMillis` on `WriterPartitionHeartbeat`; use the Kafka record header as the authoritative `writerNodeId` and Kafka record metadata as the authoritative partition for `NoMoreWrites`; keep each traffic record homogeneous in writer identity, session, partition, and connection; and commit a Kafka record only after all required processing for every contained observation finishes. A fresh run that reuses the same Kafka topic also requires per-partition reset records. |
| Proxy state machine | Required | Implement retry only for definite uncompromised transient failures, immediate `fail-closed` termination, managed `fail-open` waiting for durable controller acknowledgement before uncaptured forwarding, acknowledgement-timeout termination, healthy suppression, permanent failed-activation rules, last-usable-assignment routing, and concurrent draining of assignment-scoped writer identities. |
| Session-aware replayer | Required | Enforce the expected session, reject nonmatching-session records, persist bootstrap, and invalidate a run on a superseding reset when reset records exist. |
| Snapshot replay plan | Required | Persist the capture domain, accepted session, domain sequence, immutable Kafka IDs, and partition start offsets; include reset offsets when reusing a topic. |
| Capability probe | Required | Implement the base acknowledged per-leader capability probe using `writerNodeId = captureActivationId + ":PROBE"` and the assignment-scoped initial-heartbeat gate rather than metadata connectivity. |
| Kafka clock-skew enforcement | Required | Supply the same `E` and `S` parameters to proxies and replayers, configure the node monitor against that `S`, block unhealthy broker startup, stop a skewed broker locally, publish a custom `ClockSkew` condition, and disable replayer broker-time expiration whenever the declared bound is not healthy. |
| Replayer fatal-termination alarms | Required | Treat the final in-process fatal metric as best-effort; alert independently on unexpected replayer container restarts and correlate exit code `80` with fatal event-loop-owner loss. Qualify the required kube-state-metrics interfaces for the deployed version. |
| Source retirement proof | Required before a fresh snapshot | Define how forced termination excludes source operations completing after the recovery boundary. |
| Security | Required | Authenticate and authorize every mutation and control-record emission. |
| Observability | Required | Export terminal failure, inventory, compromise, suppression, proxy connection-set-drain, rejection, session, and reset fan-out metrics for the enabled deployment profile. |

## 14. Failure boundaries

The terminal-resource, Kubernetes-identity, stale-command, session, and replay-plan boundaries
below apply to every managed run. Reset boundaries additionally apply when a fresh run reuses a
Kafka topic. Automatic coverage establishment is unsupported; any implementation must satisfy
§9.3.

- Controller unavailability does not delay the local irreversible closure of capture. It does
  prevent managed `fail-open` from beginning uncaptured forwarding. If the controller cannot
  durably record and acknowledge the compromise before the configured finite deadline, the proxy
  terminates.
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
- Loss of a capture activation without self `NoMoreWrites` and without enough later broker-time
  evidence is not retroactively repaired. The current workflow explicitly abandons that incomplete
  interval and may start a fresh capture and snapshot workflow only after §7.2's isolation and
  source-quiescence preconditions.

All managed fresh runs additionally require:

- Late records from a nonmatching session are discarded and alarmed, including the first record
  observed from that capture activation.
- A replay run never crosses from one capture session to another without a new snapshot.

Same-topic fresh runs additionally require that a reset acknowledged on only some partitions
establishes nothing globally.

Any design for automatic coverage restoration must require that coverage establishment
acknowledged on only some partitions keeps the resource incomplete.

## 15. Validation plan

Validation requires the terminal-failure, immutable-identity, stale-command, force-deletion, and
ended-workload-retirement cases. Every managed fresh-run implementation also
requires session identity, a trusted replay plan, and session-aware checkpoint tests. A same-topic
fresh run additionally requires reset fan-out and reset-offset tests. Coverage restoration on a
terminal resource and automated replacement-snapshot orchestration are unsupported and therefore
have no acceptance tests in this contract.

### 15.1 Deterministic tests

Required deterministic tests:

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
- a configured managed `fail-open` process closing capture immediately, forwarding no uncaptured
  source traffic while acknowledgement is pending, and transitioning to whole-process pass-through
  only after the controller durably records incomplete terminal status and acknowledges the exact
  activation;
- compromise notification loss, duplication, delayed delivery, controller failover, and lost
  acknowledgement responses, with idempotent retry and no uncaptured forwarding before durable
  acknowledgement;
- planned controller-directed pass-through routing only after durable incomplete and terminal
  status;
- healthy suppression acknowledgement only after every closed connection completes the base
  connection-retirement order, every retiring `(writerNodeId, partition)` registry is empty, and
  every producer future succeeds;
- capability probes use `writerNodeId = captureActivationId + ":PROBE"` and create no replayer
  writer baseline, heartbeat state, or connection state;
- Kafka's configured built-in assignor operates without custom subscription or assignment metadata,
  and fleet startup capacity is decided from authenticated `captureReady` status rather than group
  membership count;
- the first assignment and every replacement assignment increment `assignmentSequence` and create
  a new `writerNodeId` for new connections while existing connections retain their original
  identity;
- rapid rebalances leave several writer identities draining independently, each keyed by
  `(writerNodeId, partition)`;
- an active identity's heartbeat contains no connection identities and never resets its accepted
  broker-time baseline;
- after an old identity drains, valid `NoMoreWrites` permanently retires that identity and
  partition;
- heartbeats never listing, omitting, completing, reopening, or otherwise changing a connection;
- controller completeness using authenticated local registry status rather than attempting to
  reconstruct connection membership from Kafka heartbeats;
- managed traffic records processing their ordered observations separately while withholding the
  Kafka commit until every observation's required processing finishes;
- failed or ambiguous sends preventing healthy suppression;
- off-load-balancer live connections blocking completeness;
- unreachable processes producing false or unknown, never true.

Conditional tests when a fresh run reuses a Kafka topic:

- reset fan-out idempotence and partial failure;
- reset offsets becoming the immutable per-partition replay start offsets;
- reset-intent payload immutability and identical retry after ambiguous send;
- session mismatch rejection for known and previously unseen capture activations through one
  control child that becomes `Satisfied` only after discard, advance, logging, metrics, and alarm;
  and
- a later trusted reset invalidating an older snapshot/replay run.

Required tests for an implementation that supports automatic recovery:

- coverage-establishment fan-out idempotence and partial failure;
- same-resource recovery only if a nonterminal `RECOVERING` state is explicitly defined; and
- replacement-snapshot authorization and rejection.

### 15.2 Testcontainers Kafka

Required session-fencing tests:

- a record from a nonmatching session being discarded, advanced past, logged, counted, and alarmed
  before its Kafka record becomes commit-eligible;
- an expected-session multi-observation record remaining uncommitted until every observation's
  required processing finishes;
- restart from a trusted replay plan after its boundary records have aged out; and
- checkpoints rejecting a different capture domain, session, domain sequence, or immutable Kafka
  identity.

Conditional tests when a fresh run reuses a Kafka topic:

- an old producer request appended after `FleetCaptureReset` and rejected by session;
- reset from a different producer overtaking an ambiguous old send;
- controller failover during reset fan-out, including takeover of the persisted immutable intent;
- broker restart during reset and activation;
- duplicate reset records and independent partition barriers;
- all relevant capture activations disappearing without self `NoMoreWrites`, with broker-time
  expiration affecting only already-known connection accumulators and never replacing the explicit
  session-reset boundary for managed-run recovery;
- a request captured before snapshot start but forwarded after the snapshot cut remaining included
  because replay starts immediately after reset; and
- protocol-version rejection during the wire-format rollout.

Automatic recovery requires additional broker-restart, controller-failover, and duplicate
coverage-record tests.

### 15.3 Live deployment tests

Required live deployment tests:

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
- managed `fail-open` preserving surviving existing connections and accepting new connections
  without capture only after durable controller acknowledgement;
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

Required tests for an implementation that supports automatic recovery:

- snapshot rejection when recovery changes mid-snapshot; and
- full recovery followed by a replayer launched after reset records have aged out, using only the
  durable replay plan.

## 16. Unresolved implementation and automation requirements

Before a Kubernetes deployment can claim this contract:

1. Implement and verify the exact capability probe in §4.5 and the initial-heartbeat gate.
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
8. Select the authenticated compromise-notification transport and the default finite
   controller-acknowledgement deadline. The correctness contract is already fixed: capture closes
   locally at once, no uncaptured source traffic is forwarded before durable acknowledgement, and
   deadline expiration terminates the proxy.

Automatic recovery within a failed workflow additionally requires:

1. Automate and durably record the source-side in-flight retirement proof after forced process or
   host termination.
2. Finalize coverage-establishment record rollout and orchestration. Same-topic fresh-run reset is
   already required by §9.1.
3. Decide whether unmanaged operators may manually provide a `captureSessionId`; if so, document
   that this is a manual new-run boundary rather than automatic recovery.

Until those decisions are complete, a terminal capture failure permanently leaves that resource
incomplete and terminal. The controller must not emit a reset, grant a replacement session, return
coverage to true, or authorize a new source snapshot under that resource. A fresh workflow may
proceed only after the Kafka-input-isolation and source-quiescence preconditions in §7.2 are
externally satisfied and recorded.
