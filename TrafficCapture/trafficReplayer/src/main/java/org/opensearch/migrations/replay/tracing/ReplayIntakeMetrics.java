/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.intake.ReplayIntakeOwner;
import org.opensearch.migrations.replay.intake.PartitionIntakeState;
import org.opensearch.migrations.replay.intake.SourceAssemblySink;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/** Fixed-cardinality observability for replay intake and source assembly. */
public final class ReplayIntakeMetrics implements ReplayIntakeOwner.Metrics {

    public static final AttributeKey<String> INPUT_KIND_ATTRIBUTE = AttributeKey.stringKey("inputKind");
    public static final AttributeKey<String> INCOMPLETE_REASON_ATTRIBUTE =
        AttributeKey.stringKey("incompleteReason");
    public static final AttributeKey<String> WRITER_TIME_TRANSITION_ATTRIBUTE =
        AttributeKey.stringKey("writerTimeTransition");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String OWNER_STARTED = "replayIntakeOwnerStarted";
        public static final String OWNER_STOPPED_AFTER_DRAINING = "replayIntakeOwnerStoppedAfterDraining";
        public static final String INPUTS_APPLIED = "replayIntakeInputsApplied";
        public static final String RECORDS_APPLIED = "replayIntakeRecordsApplied";
        public static final String ACTIVE_RECORD_TRACKERS = "replayIntakeActiveRecordTrackers";
        public static final String RECORD_TRACKERS_RETIRED = "replayIntakeRecordTrackersRetired";
        public static final String REQUESTS_RECONSTITUTED = "replayIntakeRequestsReconstituted";
        public static final String RESPONSES_PROVEN_COMPLETE = "replayIntakeResponsesProvenComplete";
        public static final String RESPONSES_UNPROVEN_COMPLETE = "replayIntakeResponsesUnprovenComplete";
        public static final String RESPONSES_INCOMPLETE = "replayIntakeResponsesIncomplete";
        public static final String RETRY_SOURCE_RESPONSES_COMPLETE =
            "replayIntakeRetrySourceResponsesComplete";
        public static final String RETRY_SOURCE_RESPONSES_UNAVAILABLE =
            "replayIntakeRetrySourceResponsesUnavailable";
        public static final String WRITER_TIME_TRANSITIONS =
            "replayIntakeWriterTimeTransitions";
        public static final String SOURCE_CONNECTIONS_EXPIRED =
            "replayIntakeSourceConnectionsExpired";
        public static final String TARGET_CONNECTION_EXPIRATIONS_SENT =
            "replayIntakeTargetConnectionExpirationsSent";
        public static final String BROKER_TIME_VIOLATIONS =
            "replayIntakeBrokerTimeViolations";
        public static final String CAPTURED_CLOSES_ACCEPTED = "replayIntakeCapturedClosesAccepted";
        public static final String CAPTURE_PROTOCOL_VIOLATIONS = "replayIntakeCaptureProtocolViolations";
        public static final String RECORD_BATCHES_REJECTED_AFTER_PROTOCOL_VIOLATION =
            "replayIntakeRecordBatchesRejectedAfterProtocolViolation";
    }

    private final LongCounter ownerStarted;
    private final LongCounter ownerStoppedAfterDraining;
    private final LongCounter inputsApplied;
    private final LongCounter recordsApplied;
    private final LongUpDownCounter activeRecordTrackers;
    private final LongCounter recordTrackersRetired;
    private final LongCounter requestsReconstituted;
    private final LongCounter responsesProvenComplete;
    private final LongCounter responsesUnprovenComplete;
    private final LongCounter responsesIncomplete;
    private final LongCounter retrySourceResponsesComplete;
    private final LongCounter retrySourceResponsesUnavailable;
    private final LongCounter writerTimeTransitions;
    private final LongCounter sourceConnectionsExpired;
    private final LongCounter targetConnectionExpirationsSent;
    private final LongCounter brokerTimeViolations;
    private final LongCounter capturedClosesAccepted;
    private final LongCounter captureProtocolViolations;
    private final LongCounter recordBatchesRejectedAfterProtocolViolation;

    public ReplayIntakeMetrics(@NonNull Meter meter) {
        ownerStarted = counter(meter, MetricNames.OWNER_STARTED, "owners");
        ownerStoppedAfterDraining = counter(meter, MetricNames.OWNER_STOPPED_AFTER_DRAINING, "owners");
        inputsApplied = counter(meter, MetricNames.INPUTS_APPLIED, "inputs");
        recordsApplied = counter(meter, MetricNames.RECORDS_APPLIED, "records");
        activeRecordTrackers = meter.upDownCounterBuilder(MetricNames.ACTIVE_RECORD_TRACKERS)
            .setUnit("records")
            .build();
        recordTrackersRetired = counter(meter, MetricNames.RECORD_TRACKERS_RETIRED, "records");
        requestsReconstituted = counter(meter, MetricNames.REQUESTS_RECONSTITUTED, "requests");
        responsesProvenComplete = counter(meter, MetricNames.RESPONSES_PROVEN_COMPLETE, "responses");
        responsesUnprovenComplete = counter(meter, MetricNames.RESPONSES_UNPROVEN_COMPLETE, "responses");
        responsesIncomplete = counter(meter, MetricNames.RESPONSES_INCOMPLETE, "responses");
        retrySourceResponsesComplete =
            counter(meter, MetricNames.RETRY_SOURCE_RESPONSES_COMPLETE, "responses");
        retrySourceResponsesUnavailable =
            counter(meter, MetricNames.RETRY_SOURCE_RESPONSES_UNAVAILABLE, "responses");
        writerTimeTransitions = counter(meter, MetricNames.WRITER_TIME_TRANSITIONS, "transitions");
        sourceConnectionsExpired = counter(meter, MetricNames.SOURCE_CONNECTIONS_EXPIRED, "connections");
        targetConnectionExpirationsSent =
            counter(meter, MetricNames.TARGET_CONNECTION_EXPIRATIONS_SENT, "commands");
        brokerTimeViolations = counter(meter, MetricNames.BROKER_TIME_VIOLATIONS, "violations");
        capturedClosesAccepted = counter(meter, MetricNames.CAPTURED_CLOSES_ACCEPTED, "closes");
        captureProtocolViolations = counter(meter, MetricNames.CAPTURE_PROTOCOL_VIOLATIONS, "violations");
        recordBatchesRejectedAfterProtocolViolation =
            counter(meter, MetricNames.RECORD_BATCHES_REJECTED_AFTER_PROTOCOL_VIOLATION, "batches");
    }

    @Override
    public void ownerStarted() {
        ownerStarted.add(1);
    }

    @Override
    public void ownerStoppedAfterDraining() {
        ownerStoppedAfterDraining.add(1);
    }

    @Override
    public void inputApplied(@NonNull ReplayIntakeOwner.InputKind inputKind) {
        inputsApplied.add(1, Attributes.of(INPUT_KIND_ATTRIBUTE, inputKind.name()));
    }

    @Override
    public void recordApplied() {
        recordsApplied.add(1);
    }

    @Override
    public void activeRecordTrackersChanged(int delta) {
        activeRecordTrackers.add(delta);
    }

    @Override
    public void recordTrackerRetired() {
        recordTrackersRetired.add(1);
    }

    @Override
    public void requestReconstituted() {
        requestsReconstituted.add(1);
    }

    @Override
    public void responseCompleted(boolean keptAlive) {
        (keptAlive ? responsesProvenComplete : responsesUnprovenComplete).add(1);
    }

    @Override
    public void responseIncomplete(@NonNull SourceAssemblySink.IncompleteReason reason) {
        responsesIncomplete.add(1, Attributes.of(INCOMPLETE_REASON_ATTRIBUTE, reason.name()));
    }

    @Override
    public void retrySourceResponseCompleted() {
        retrySourceResponsesComplete.add(1);
    }

    @Override
    public void retrySourceResponseUnavailable() {
        retrySourceResponsesUnavailable.add(1);
    }

    @Override
    public void writerTimeTransition(@NonNull PartitionIntakeState.WriterTimeTransition transition) {
        if (transition != PartitionIntakeState.WriterTimeTransition.NONE) {
            writerTimeTransitions.add(
                1,
                Attributes.of(WRITER_TIME_TRANSITION_ATTRIBUTE, transition.name())
            );
        }
    }

    @Override
    public void sourceConnectionsExpired(int count) {
        if (count > 0) {
            sourceConnectionsExpired.add(count);
        }
    }

    @Override
    public void targetConnectionExpirationSent() {
        targetConnectionExpirationsSent.add(1);
    }

    @Override
    public void brokerTimeViolation() {
        brokerTimeViolations.add(1);
    }

    @Override
    public void capturedCloseAccepted() {
        capturedClosesAccepted.add(1);
    }

    @Override
    public void captureProtocolViolation() {
        captureProtocolViolations.add(1);
    }

    @Override
    public void recordBatchRejectedAfterProtocolViolation() {
        recordBatchesRejectedAfterProtocolViolation.add(1);
    }

    private static LongCounter counter(Meter meter, String name, String unit) {
        return meter.counterBuilder(name).setUnit(unit).build();
    }
}
