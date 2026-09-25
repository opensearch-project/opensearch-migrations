/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.intake.ReplayIntakeOwner;
import org.opensearch.migrations.replay.intake.RecordAssociationId;
import org.opensearch.migrations.replay.intake.SourceAssemblySink;
import org.opensearch.migrations.replay.identity.KafkaRecordId;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import lombok.NonNull;

/** Fixed-cardinality observability for replay intake and source assembly. */
public final class ReplayIntakeMetrics implements ReplayIntakeOwner.Metrics {

    public static final AttributeKey<String> INPUT_KIND_ATTRIBUTE = AttributeKey.stringKey("inputKind");
    public static final AttributeKey<String> INCOMPLETE_REASON_ATTRIBUTE =
        AttributeKey.stringKey("incompleteReason");
    public static final AttributeKey<String> RECORD_ID_ATTRIBUTE = AttributeKey.stringKey("recordId");
    public static final AttributeKey<String> ASSOCIATION_ID_ATTRIBUTE = AttributeKey.stringKey("associationId");
    public static final AttributeKey<String> ASSOCIATION_KIND_ATTRIBUTE =
        AttributeKey.stringKey("associationKind");
    public static final AttributeKey<String> ASSOCIATION_ACTION_ATTRIBUTE =
        AttributeKey.stringKey("associationAction");
    public static final AttributeKey<Long> CAPTURED_REQUEST_ORDINAL_ATTRIBUTE =
        AttributeKey.longKey("capturedRequestOrdinal");
    public static final String RECORD_ASSOCIATION_CHANGED_SPAN = "replayIntakeRecordAssociationChanged";

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
    private final LongCounter capturedClosesAccepted;
    private final LongCounter captureProtocolViolations;
    private final LongCounter recordBatchesRejectedAfterProtocolViolation;
    private final Tracer tracer;

    public ReplayIntakeMetrics(@NonNull Meter meter, @NonNull Tracer tracer) {
        this.tracer = tracer;
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
    public void recordAssociationChanged(
        @NonNull KafkaRecordId recordId,
        @NonNull RecordAssociationId association,
        boolean added
    ) {
        var span = tracer.spanBuilder(RECORD_ASSOCIATION_CHANGED_SPAN).startSpan();
        try {
            span.setAttribute(RECORD_ID_ATTRIBUTE, recordId.toString());
            span.setAttribute(ASSOCIATION_ID_ATTRIBUTE, association.toString());
            span.setAttribute(ASSOCIATION_ACTION_ATTRIBUTE, added ? "added" : "removed");
            switch (association) {
                case RecordAssociationId.Request request -> {
                    span.setAttribute(ASSOCIATION_KIND_ATTRIBUTE, "request");
                    span.setAttribute(
                        CAPTURED_REQUEST_ORDINAL_ATTRIBUTE,
                        request.replayRequestId().capturedRequestOrdinal()
                    );
                }
                case RecordAssociationId.RequestAssembly assembly -> {
                    span.setAttribute(ASSOCIATION_KIND_ATTRIBUTE, "assembly");
                    span.setAttribute(
                        CAPTURED_REQUEST_ORDINAL_ATTRIBUTE,
                        assembly.capturedRequestOrdinal()
                    );
                }
                case RecordAssociationId.TerminalConnection ignored ->
                    span.setAttribute(ASSOCIATION_KIND_ATTRIBUTE, "terminal");
            }
        } finally {
            span.end();
        }
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
