/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.intake.ReplayIntakeOwner;
import org.opensearch.migrations.replay.intake.SourceAssemblySink;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/** Fixed-cardinality observability for replay intake and source assembly. */
public final class ReplayIntakeMetrics implements ReplayIntakeOwner.Metrics {

    public static final AttributeKey<String> INPUT_KIND_ATTRIBUTE = AttributeKey.stringKey("inputKind");
    public static final AttributeKey<String> INCOMPLETE_REASON_ATTRIBUTE =
        AttributeKey.stringKey("incompleteReason");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String OWNER_STARTED = "replayIntakeOwnerStarted";
        public static final String OWNER_STOPPED_AFTER_DRAINING = "replayIntakeOwnerStoppedAfterDraining";
        public static final String INPUTS_APPLIED = "replayIntakeInputsApplied";
        public static final String RECORDS_APPLIED = "replayIntakeRecordsApplied";
        public static final String REQUESTS_RECONSTITUTED = "replayIntakeRequestsReconstituted";
        public static final String RESPONSES_PROVEN_COMPLETE = "replayIntakeResponsesProvenComplete";
        public static final String RESPONSES_UNPROVEN_COMPLETE = "replayIntakeResponsesUnprovenComplete";
        public static final String RESPONSES_INCOMPLETE = "replayIntakeResponsesIncomplete";
        public static final String CAPTURED_CLOSES_ACCEPTED = "replayIntakeCapturedClosesAccepted";
        public static final String CAPTURE_PROTOCOL_VIOLATIONS = "replayIntakeCaptureProtocolViolations";
    }

    private final LongCounter ownerStarted;
    private final LongCounter ownerStoppedAfterDraining;
    private final LongCounter inputsApplied;
    private final LongCounter recordsApplied;
    private final LongCounter requestsReconstituted;
    private final LongCounter responsesProvenComplete;
    private final LongCounter responsesUnprovenComplete;
    private final LongCounter responsesIncomplete;
    private final LongCounter capturedClosesAccepted;
    private final LongCounter captureProtocolViolations;

    public ReplayIntakeMetrics(@NonNull Meter meter) {
        ownerStarted = counter(meter, MetricNames.OWNER_STARTED, "owners");
        ownerStoppedAfterDraining = counter(meter, MetricNames.OWNER_STOPPED_AFTER_DRAINING, "owners");
        inputsApplied = counter(meter, MetricNames.INPUTS_APPLIED, "inputs");
        recordsApplied = counter(meter, MetricNames.RECORDS_APPLIED, "records");
        requestsReconstituted = counter(meter, MetricNames.REQUESTS_RECONSTITUTED, "requests");
        responsesProvenComplete = counter(meter, MetricNames.RESPONSES_PROVEN_COMPLETE, "responses");
        responsesUnprovenComplete = counter(meter, MetricNames.RESPONSES_UNPROVEN_COMPLETE, "responses");
        responsesIncomplete = counter(meter, MetricNames.RESPONSES_INCOMPLETE, "responses");
        capturedClosesAccepted = counter(meter, MetricNames.CAPTURED_CLOSES_ACCEPTED, "closes");
        captureProtocolViolations = counter(meter, MetricNames.CAPTURE_PROTOCOL_VIOLATIONS, "violations");
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

    private static LongCounter counter(Meter meter, String name, String unit) {
        return meter.counterBuilder(name).setUnit(unit).build();
    }
}
