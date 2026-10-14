# Capture and replay observability operations

This runbook covers the Kubernetes capture proxy and traffic replayer. Metrics and
logs are the production diagnostic contract. Trace export is optional and normally
disabled.

This document is retained through the live gameday so its useful material can be
integrated into the permanent operator documentation afterward.

## Operating model

- Metrics detect and localize sustained faults.
- Logs use `event=<bounded_name> key=value` and carry traffic identities.
- `replay_health`, `replay_oldest_record`, and `replay_stall_detected` are written
  to both the main replayer log and the dedicated activity log.
- Aggregate metrics never use request, connection, Kafka offset, bucket, object key,
  pod, or JVM instance identifiers as attributes.
- EKS removes `service.instance.id` before CloudWatch metric conversion so fleet
  queries remain aggregate. The restart-scoped value remains available in logs and
  telemetry resources.
- Each replayer reports its own consumer lag (`kafkaSourceConsumerLag`, from the
  client's last fetch response) and commit-head age. Summed across the fleet these
  approximate group lag; `kafka-consumer-groups.sh --describe` remains the
  authoritative broker-side view. No Kafka-exporter lag series is deployed.
- CloudWatch gauge samples arrive once per pod per export, so `Maximum` is the
  hottest pod, `Sum / PERIOD` the fleet total, and percentiles the spread across
  pods. Histograms carry only a usable mean in CloudWatch.
- `console kafka describe-consumer-group` appends a metadata-only replay watermark:
  the timestamp span from the last contiguously and durably replayed record to
  the newest partition record. It fetches no traffic payloads, and it is not the
  exact age of the next unprocessed record or authoritative backlog drain time.
- This repository installs no alerts, alarm rules, notifications, or notification
  targets. Thresholds below are investigation guidance and dashboard coloring only.

## Metric inventory

The Kubernetes Prometheus exporter adds unit/type suffixes to these instrument
names. The capture/replay Grafana dashboard uses the verified exported series
names directly so histogram buckets, sums, counts, counters, and gauges cannot be
combined accidentally. Other metric backends may expose different suffixes.

The dashboard's replay-progress panels use the windowed mean of
`lagBetweenSourceAndTargetRequests` and the recent derivative of that mean. The
operator-selectable catch-up floor defaults to five seconds, with one-second and
one-minute alternatives. The estimate projects when the recently observed mean
replay lag will reach that floor; it is not authoritative Kafka backlog drain
time. It is shown only while lag is above the floor and decreasing by more than
0.01 seconds per second. No value is displayed for below-floor, non-converging,
or insufficient-history cases. Broker consumer-group lag remains a separate
CLI-derived signal.

### Capture

| Instrument | Type / unit | Bounded attributes | Healthy expectation |
|---|---|---|---|
| `captureConnectionsActive` | up-down / connections | — | Tracks current proxy connections; returns to zero after drain |
| `captureConnectionCloses` | counter / connections | `reason` | Expected close reasons dominate |
| `capturePublishingRecordsAccepted` | counter / records | `recordKind` | Increases with capture traffic |
| `capturePublishingBytesAccepted` | counter / bytes | `recordKind` | Increases with capture traffic |
| `capturePublishingCompletions` | counter / records | `recordKind`, `outcome` | Acknowledged completions keep pace with accepted records |
| `capturePublishingRecordsAwaitingAcknowledgement` | up-down / records | `recordKind` | Bounded and returns toward zero |
| `capturePublishingBytesAwaitingAcknowledgement` | up-down / bytes | `recordKind` | Bounded and returns toward zero |
| `captureHeartbeatSubmissions` | counter / records | — | Advances at the configured heartbeat cadence |
| `captureHeartbeatAcknowledgements` | counter / records | — | Keeps pace with submissions |
| `captureHeartbeatDeadlineViolations` | counter / violations | — | Zero |
| `captureBrokerTimeViolations` | counter / violations | — | Zero |
| `captureRoutingTransitions` | counter / transitions | `transition` | Changes only with assignment/generation lifecycle |
| `captureWritersActive` | up-down / writers | — | Matches active routes |
| `captureWritersDraining` | up-down / writers | — | Normally zero outside transitions |
| `captureWritersRetired` | counter / writers | — | Advances after completed drains |
| `captureWriteGateTransitions` | counter / transitions | `reason` | Zero during healthy capture |
| `captureWritesRejected` | counter / writes | `reason` | Zero |
| `capturePublisherFailures` | counter / failures | `reason` | Zero |
| `captureGapActive` | up-down / processes | existing bounded process-state dimensions | Zero |

### Replay intake and activity

| Instrument | Type / unit | Bounded attributes | Healthy expectation |
|---|---|---|---|
| `replayActiveContexts` | gauge / contexts | — | Moves with traffic and drains to zero |
| `replayOldestContextAge` | gauge / ms | — | Below the configured old-work threshold |
| `replayActiveContextsByActivity` | gauge / contexts | `activity` | Known activities report explicit zeroes when empty |
| `replayOldestContextAgeByActivity` | gauge / ms | `activity` | Below the activity threshold |
| `replayOverThresholdContextsByActivity` | gauge / contexts | `activity` | Zero |
| `replayActiveOwnerOperations` | gauge / operations | — | Bounded; drains to zero |
| `replayLongRunningOwnerOperations` | gauge / operations | — | Investigate sustained nonzero values |
| `replayActiveOwnerOperationsByType` | gauge / operations | `operationType` | Known types report explicit zeroes when empty |
| `replayLongRunningOwnerOperationsByType` | gauge / operations | `operationType` | Identifies the operation category behind an age signal |
| `replayOldestKafkaRecordAge` | gauge / ms | — | Below the old-record investigation threshold |
| `replayKafkaCommitUncertainPartitions` | gauge / partitions | — | Zero |
| `replayIntakeInputsApplied` | counter / inputs | `inputKind` | Advances while source or lifecycle work is active |
| `replayIntakeRecordsApplied` | counter / records | — | Keeps pace with Kafka reads |
| `replayIntakeActiveRecordTrackers` | up-down / records | — | Bounded; drains to zero |
| `replayIntakeRecordTrackersRetired` | counter / records | — | Advances as record work finishes |
| `replayIntakeRequestsReconstituted` | counter / requests | — | Advances with request capture |
| `replayIntakeResponsesFramedComplete` | counter / responses | — | Advances for self-delimited source responses |
| `replayIntakeResponseFramingFallbacks` | counter / responses | `framingFallbackReason` | Zero or explained malformed/upgraded source traffic |
| `replayIntakePostFramingResponseObservations` | counter / observations | `postFramingObservationType` | Compare with baseline; investigate abrupt rate changes |
| `replayIntakePostFramingResponseBytes` | counter / bytes | `postFramingObservationType` | Compare with baseline; investigate positive-byte growth |
| `replayIntakeResponsesIncomplete` | counter / responses | `incompleteReason` | Zero except explained source termination |
| `replayIntakeRetryReadyRequestSupply` | up-down / requests | — | Bounded by configured replay supply |
| `replayIntakeBrokerTimeViolations` | counter / violations | — | Zero |
| `replayIntakeCaptureProtocolViolations` | counter / violations | — | Zero |
| `replayIntakeGenerationGraceStarted` | counter / generations | `graceMode` | Advances on rebalance or shutdown |
| `replayIntakeGenerationForceStarted` | counter / generations | — | Normally zero |
| `replayIntakeGenerationCleanupFinished` | counter / generations | — | Eventually matches generation cleanup starts |
| `replayIntakeStaleGenerationInputsIgnored` | counter / inputs | `inputKind` | Low and explained by generation turnover |

The remaining replay-intake counters describe response completion, retry-source
availability, writer-time transitions, source expiration, demand, batch
entitlements, and captured closes. Their names are the `replayIntake*` instruments
in `ReplayIntakeMetrics`; all dimensions are bounded enums.

### Kafka commit, target, tuple, and resources

| Instrument | Type / unit | Bounded attributes | Healthy expectation |
|---|---|---|---|
| `kafkaSourceRecordsRead` | counter / records | — | Advances with broker input |
| `kafkaSourceRecordsCommitted` | counter / records | — | Catches up to terminally accounted reads |
| `kafkaSourceRecordsCancelled` | counter / records | — | Changes only during explained cleanup |
| `kafkaSourceRecordsAbandonedAtRevocation` | counter / records | `cause` | Low; explained by rebalance |
| `kafkaSourceRecordsCommitIneligible` | counter / records | — | Zero absent protocol violation |
| `kafkaSourceRecordsOutstanding` | up-down / records | — | Bounded; drains to zero |
| `kafkaSourceCommitAttemptsRejected` | counter / attempts | — | Zero or transient |
| `kafkaSourceCommitResolutions` | counter / events | `outcome` | Acknowledged outcomes dominate |
| `kafkaSourceCommitRetryAttempts` | counter / attempts | `failure_class` | Low; exponential pacing during an outage |
| `kafkaSourceCommitRetryDegraded` | up-down / episodes | — | Zero; one while this process is backing off |
| `kafkaSourceCommitRetryRecoveries` | counter / events | — | Advances when an acknowledged commit ends degradation |
| `kafkaSourceAssignedPartitions` | gauge / partitions | — | Even across pods; spread shows assignment skew |
| `kafkaSourceConsumerLag` | gauge / records | — | Bounded at steady state; unread records only, add `kafkaSourceRecordsOutstanding` for uncommitted backlog |
| `kafkaSourceMaxPartitionLag` | gauge / records | — | Close to the per-partition average; one high partition is a hot spot |
| `kafkaSourceLagUnknownPartitions` | gauge / partitions | — | Zero once each owned partition has been fetched |
| `kafkaSourceOldestCommitHeadAge` | gauge / ms | — | Bounded by replay lag plus response time; steady growth means a stuck head |
| `kafkaSourceStaleCommitHeadPartitions` | gauge / partitions | — | Zero; counts heads held more than five minutes |
| `targetAttemptPermitsActive` | up-down / permits | — | At or below configured capacity; drains to zero |
| `targetAttemptPermitAcquisitionsPending` | up-down / requests | — | Normally zero or short lived |
| `targetAttemptPermitHeldDuration` | histogram / ms | — | Stable around target latency |
| `targetAttemptsWithoutResponse` | counter / attempts | `noResponseKind` | Zero or transient |
| `targetRetriesExhausted` | counter / requests | — | Zero |
| `tupleDurabilityWritesAccepted` | counter / writes | — | Advances with completed requests |
| `tupleDurabilityWritesPending` | up-down / writes | — | Normally zero or bounded |
| `tupleDurabilityRetries` | counter / retries | — | Outer physical-sink-call retries; zero for retained inner S3 retries |
| `tupleDurabilityTerminalFailures` | counter / writes | `failureReason` | Zero |
| `tupleDurabilityWritesDurable` | counter / writes | — | Keeps pace with accepted writes |
| `tupleDurabilityWritesCancelled` | counter / writes | — | Changes only during explained cancellation |
| `tupleDurabilityWriteLatency` | histogram / ms | — | Completed-write latency; does not report pending age |
| `tupleSinkUploadRetryAttempts` | counter / retries | — | Advances for each failed inner S3 upload followed by same-key retry |
| `tupleSinkUploadsRetrying` | up-down / objects | — | Current S3 objects retained for successful same-key retry |
| `tupleSinkUploadRecoveries` | counter / objects | — | Positive evidence of durability after one or more failed attempts |
| `resourceOwnedHandles` | up-down / handles | `resourceType` | Returns to zero after drain |
| `resourceOwnedBuffers` | up-down / buffers | `resourceType` | Returns to zero after drain |
| `resourceOwnedBytes` | up-down / bytes | `resourceType` | Returns to zero after drain |
| `resourceDuplicateCloseAttempts` | counter / attempts | `resourceType` | Zero |
| `resourceOwnershipInvariantFailures` | counter / failures | `resourceType` | Zero |
| `replayProtocolViolationDrainsStarted` | counter / drains | — | Zero |
| `replayProtocolViolationTerminationsTriggered` | counter / terminations | — | Zero |
| `replayFatalFailures` | counter / failures | `reason` | Zero |

#### Tuple pending age

`replayOldestContextAgeByActivity{activity="comparingResults"}` is the oldest
pending tuple-durability age. `RequestReplayOwner` creates the `comparingResults`
context immediately before submitting the logical tuple write and closes it only
after that write reaches a typed durable, cancelled, or terminal result. An inner
S3 retry leaves the durability future pending, so the context remains active and
this age continues increasing throughout the outage.

Use the age together with:

- `tupleDurabilityWritesPending` for the number of logical tuple writes waiting;
- `tupleSinkUploadsRetrying` for the number of staged S3 objects retrying;
- `tupleSinkUploadRetryAttempts` for continuing retry activity; and
- `tupleSinkUploadRecoveries` plus falling populations for positive recovery.

The age is intentionally a tuple-durability signal rather than an object-key metric.
Bucket and object key remain log-only identities.

## Conservation checks

Evaluate deltas over the same time window and include the current up-down value:

```text
Kafka:
read = committed + cancelled + abandoned_at_revocation
     + commit_ineligible + outstanding

Retry-ready:
additions - removals = current_supply

Target permits:
acquired - released = active

Tuple durability:
accepted = durable + cancelled + terminal_failures + pending
```

The orderly-drain monitor asserts its own final context, owner-operation, and Kafka
diagnostic populations. Use the subsystem conservation metrics above when validating
the broader process shutdown.

## Suggested investigation thresholds

These are starting points for investigation and dashboard coloring, not installed
alerts:

- investigate immediately: capture gap, heartbeat deadline violation, publisher
  failure, write rejection, protocol violation, replay fatal failure, or tuple
  terminal failure;
- investigate when sustained: oldest Kafka record above 120 seconds, old contexts,
  long-running owner operations, commit uncertainty, tuple writes or S3 objects
  remaining pending, or broker CLI consumer lag;
- after workload termination, investigate any draining population that remains
  nonzero.

Fleet consumer lag is `kafkaSourceConsumerLag` summed across replayers; confirm a
lag investigation with `kafka-consumer-groups.sh` output. Use
`console kafka describe-consumer-group` when the offset count also needs the
replayer's temporal watermark context.

## Log queries

Kubernetes examples:

```bash
kubectl -n "$NAMESPACE" logs -l app=traffic-replayer --since=15m \
  | grep 'event=replay_stall_detected'

kubectl -n "$NAMESPACE" logs -l app=traffic-replayer --since=15m \
  | grep 'topic=traffic partition=0 generation=12 offset=345'

kubectl -n "$NAMESPACE" logs -l app=capture-proxy --since=15m \
  | grep -E 'event=capture_(heartbeat_late|write_gate_changed|publisher_failed)'

kubectl -n "$NAMESPACE" logs -l app=traffic-replayer --since=15m \
  | grep -E 'event=tuple_sink_upload_(degraded|recovered)'
```

Useful bounded event names include:

- capture: `capture_startup_summary`, `capture_assignment_received`,
  `capture_generation_prepared`, `capture_generation_activated`,
  `capture_writer_draining`, `capture_writer_retired`, `capture_health`,
  `capture_write_gate_changed`, `capture_heartbeat_late`,
  `capture_broker_time_violation`, `capture_connection_dropped`,
  `capture_publisher_failed`, `capture_shutdown_summary`;
- replay: `replayer_startup_summary`, `kafka_assignment_changed`,
  `kafka_generation_retiring`, `kafka_commit_retry_degraded`,
  `kafka_commit_retry_recovered`,
  `replay_generation_grace_started`, `replay_generation_force_started`,
  `replay_generation_cleanup_finished`, `replay_health`,
  `replay_oldest_record`, `replay_stall_detected`,
  `capture_protocol_violation`,
  `tuple_durability_degraded`, `tuple_durability_recovered`,
  `tuple_sink_upload_degraded`, `tuple_sink_upload_recovered`,
  `replayer_fatal_failure`, `replayer_shutdown_summary`.

Repeated inner S3 retry failures are DEBUG
`event=tuple_sink_upload_retry_failed`; the transition into degradation is one WARN,
and complete recovery is one INFO.

Kafka commit retry follows the same transition discipline:
`event=kafka_commit_retry_degraded` is one WARN per degraded episode,
`event=kafka_commit_retry_backoff` and `event=kafka_commit_uncertain` are DEBUG details,
and `event=kafka_commit_retry_recovered` is one INFO after a later acknowledgment.
The `failure_class` metric attribute is one of the bounded rebalance, coordinator,
timeout, transport, wakeup, or other classifications; partition and offset details
remain bounded log fields rather than metric dimensions.

Commit backoff bounds failure amplification after a commit error; it does not pace
initial consumer-group admission. A `TrafficReplay` currently creates one
Kubernetes Deployment at the requested `podReplicas`, without application startup
jitter or a controller-side join-rate limit. For large fleet increases, raise the
replica count in batches and wait for assignments to stabilize before the next
batch. During the ramp, require the degraded gauge to return to zero, retry-attempt
growth to stop, and broker coordinator and storage saturation signals to remain
healthy. The quiet-window assignor keeps surviving workers' partitions and defers a
full rebalance until membership stops growing for `kafkaQuietWindowMs`, so new
replicas stay idle during a ramp; it does not remove the initial join/coordinator
burst.

Per-attempt target failures, retry exhaustion, source-connection expiration, and
positive-byte post-response-boundary observations can scale with workload volume
and are DEBUG (`target_response_unavailable`, `target_retries_exhausted`,
`source_connection_expired`, and `source_response_post_boundary_observation`).
Expected zero-byte post-boundary observations are TRACE. Use the bounded metrics
for fleet-wide diagnosis, then enable detailed logging for individual evidence.

Follow one record using `topic`, `partition`, `generation`, and `offset`, then follow
its descendants using `writer`, `connection`, `lifetime`, `request`, and `attempt`.
No trace or span ID is required.

## Decision trees

### Capture is not reaching Kafka

1. Check `captureGapActive`, write rejection, publisher failure, and heartbeat
   deadline counters.
2. Inspect `capture_health`, then assignment, generation, writer, and write-gate
   events.
3. If awaiting-ack populations grow, investigate broker connectivity. If they are
   flat while proxy bytes grow, investigate routing/write-gate state.

### Kafka records are not becoming requests

1. Check broker consumer-group lag with the gameday helper.
2. Compare `kafkaSourceRecordsRead`, `replayIntakeRecordsApplied`, active record
   trackers, and oldest-record age.
3. Inspect `replay_oldest_record` and `replay_stall_detected`. With no active
   descendant, use the immutable Kafka diagnostic fields for commit head,
   eligibility, in-flight commit, or uncertainty.

### Requests are waiting before target execution

1. Check pending permits, active permits, owner operations by type, and activity
   populations.
2. Inspect the supplemental owner-operation log detail for `waitReason`.
3. Distinguish replay scheduling, permit capacity, required receiver delivery, and
   target connection setup before changing concurrency.

### Target attempts fail or exhaust retries

1. Check `targetAttemptsWithoutResponse` by `noResponseKind` and
   `targetRetriesExhausted`.
2. If individual request detail is needed, enable DEBUG and inspect
   `target_response_unavailable`, target-attempt events, and
   `target_retries_exhausted`.
3. Separate connection, write, read/response, and retry-policy outcomes.

### Tuples are not durable

1. Check pending tuple writes, retrying S3 objects, inner retry attempts, terminal
   failures, and `replayOldestContextAgeByActivity{activity="comparingResults"}`.
2. Inspect `tuple_sink_upload_degraded` for the bounded failure class, exception
   type, cause, bucket, and object key. Use DEBUG retry events only when needed.
3. Recovery is explicit: `tuple_sink_upload_recovered`,
   `tupleSinkUploadRecoveries` advancing, and both pending populations returning
   toward zero.
4. `tupleDurabilityRetries` remaining zero is expected while the inner S3 writer
   retains the same physical durability future.

### Clean shutdown does not finish

1. Evaluate the conservation equations and draining metrics.
2. Check `replay_health`, `replay_stall_detected`, and shutdown summaries.
3. Identify the remaining context, owner obligation, permit, tuple, resource, or
   Kafka commit state before restarting a pod.

## Executable gameday support

Run against the deployed namespace:

```bash
NAMESPACE=ma \
KAFKA_CLUSTER=migration-kafka \
CONSUMER_GROUP=traffic-replayer \
deployment/k8s/scripts/capture-replay-gameday.sh baseline
```

Commands:

- `lag`: emits one broker-side group summary with partition count, total lag, and
  maximum partition lag;
- `events`: collects bounded capture, replay, and tuple-sink lifecycle events from
  the recent window;
- `baseline`: emits lag, key metric snapshots, tuple pending age, and recent events;
- `self-test`: validates the lag parser without a cluster.

For every gameday scenario record the first metric or investigation-threshold
breach, first operational event, identity chain, time to detection, causal
hypothesis, and recovery event. Required scenarios are healthy traffic/scale,
capture acknowledgement stall, routing change, Kafka outage, replay rebalance,
slow/unavailable target, malformed input, tuple sink outage, ownership fault, pod
restart, and collector outage. Run with trace export disabled.
