package org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing;

import java.util.Locale;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Projects capture publication, routing, writer, write-gate, and connection lifecycle transitions
 * into bounded aggregate metrics.
 *
 * <p>Traffic identities remain in logs. Metric attributes are restricted to the enums declared by
 * this class so the series can be aggregated across proxy processes without connection, writer,
 * partition, or activation cardinality.</p>
 */
public final class CapturePublishingMetrics {
    public enum RecordKind {
        TRAFFIC,
        HEARTBEAT
    }

    public enum PublishOutcome {
        ACKNOWLEDGED,
        FAILED
    }

    public enum RoutingTransition {
        ASSIGNMENT_RECEIVED,
        GENERATION_PREPARED,
        GENERATION_ACTIVATED
    }

    public enum FailureReason {
        HEARTBEAT_DEADLINE,
        BROKER_TIME,
        PAYLOAD_LIMIT,
        KAFKA_SEND,
        CORRUPTED_STATE,
        EXECUTOR_REJECTED,
        MEMBERSHIP,
        OTHER
    }

    public static final AttributeKey<String> RECORD_KIND =
        AttributeKey.stringKey("recordKind");
    public static final AttributeKey<String> OUTCOME =
        AttributeKey.stringKey("outcome");
    public static final AttributeKey<String> TRANSITION =
        AttributeKey.stringKey("transition");
    public static final AttributeKey<String> REASON =
        AttributeKey.stringKey("reason");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String RECORDS_ACCEPTED = "capturePublishingRecordsAccepted";
        public static final String BYTES_ACCEPTED = "capturePublishingBytesAccepted";
        public static final String COMPLETIONS = "capturePublishingCompletions";
        public static final String RECORDS_AWAITING_ACKNOWLEDGEMENT =
            "capturePublishingRecordsAwaitingAcknowledgement";
        public static final String BYTES_AWAITING_ACKNOWLEDGEMENT =
            "capturePublishingBytesAwaitingAcknowledgement";
        public static final String HEARTBEAT_SUBMISSIONS = "captureHeartbeatSubmissions";
        public static final String HEARTBEAT_ACKNOWLEDGEMENTS = "captureHeartbeatAcknowledgements";
        public static final String HEARTBEAT_DEADLINE_VIOLATIONS =
            "captureHeartbeatDeadlineViolations";
        public static final String BROKER_TIME_VIOLATIONS = "captureBrokerTimeViolations";
        public static final String ROUTING_TRANSITIONS = "captureRoutingTransitions";
        public static final String WRITERS_ACTIVE = "captureWritersActive";
        public static final String WRITERS_DRAINING = "captureWritersDraining";
        public static final String WRITERS_RETIRED = "captureWritersRetired";
        public static final String WRITE_GATE_TRANSITIONS = "captureWriteGateTransitions";
        public static final String WRITES_REJECTED = "captureWritesRejected";
        public static final String PUBLISHER_FAILURES = "capturePublisherFailures";
    }

    private final LongCounter recordsAccepted;
    private final LongCounter bytesAccepted;
    private final LongCounter completions;
    private final LongUpDownCounter recordsAwaitingAcknowledgement;
    private final LongUpDownCounter bytesAwaitingAcknowledgement;
    private final LongCounter heartbeatSubmissions;
    private final LongCounter heartbeatAcknowledgements;
    private final LongCounter heartbeatDeadlineViolations;
    private final LongCounter brokerTimeViolations;
    private final LongCounter routingTransitions;
    private final LongUpDownCounter writersActive;
    private final LongUpDownCounter writersDraining;
    private final LongCounter writersRetired;
    private final LongCounter writeGateTransitions;
    private final LongCounter writesRejected;
    private final LongCounter publisherFailures;

    public CapturePublishingMetrics(@NonNull Meter meter) {
        recordsAccepted = meter.counterBuilder(MetricNames.RECORDS_ACCEPTED)
            .setDescription("Capture records accepted by the Kafka producer boundary")
            .setUnit("records")
            .build();
        bytesAccepted = meter.counterBuilder(MetricNames.BYTES_ACCEPTED)
            .setDescription("Capture payload bytes accepted by the Kafka producer boundary")
            .setUnit("By")
            .build();
        completions = meter.counterBuilder(MetricNames.COMPLETIONS)
            .setDescription("Terminal outcomes for accepted capture publications")
            .setUnit("records")
            .build();
        recordsAwaitingAcknowledgement =
            meter.upDownCounterBuilder(MetricNames.RECORDS_AWAITING_ACKNOWLEDGEMENT)
                .setDescription("Accepted capture records awaiting Kafka acknowledgement")
                .setUnit("records")
                .build();
        bytesAwaitingAcknowledgement =
            meter.upDownCounterBuilder(MetricNames.BYTES_AWAITING_ACKNOWLEDGEMENT)
                .setDescription("Accepted capture payload bytes awaiting Kafka acknowledgement")
                .setUnit("By")
                .build();
        heartbeatSubmissions = meter.counterBuilder(MetricNames.HEARTBEAT_SUBMISSIONS)
            .setDescription("Writer-partition heartbeat records accepted for publication")
            .setUnit("records")
            .build();
        heartbeatAcknowledgements = meter.counterBuilder(MetricNames.HEARTBEAT_ACKNOWLEDGEMENTS)
            .setDescription("Writer-partition heartbeat acknowledgements accepted")
            .setUnit("records")
            .build();
        heartbeatDeadlineViolations =
            meter.counterBuilder(MetricNames.HEARTBEAT_DEADLINE_VIOLATIONS)
                .setDescription("Writer-partition heartbeat acknowledgement deadlines exceeded")
                .setUnit("violations")
                .build();
        brokerTimeViolations = meter.counterBuilder(MetricNames.BROKER_TIME_VIOLATIONS)
            .setDescription("Acknowledgements rejected by capture broker-time continuity checks")
            .setUnit("violations")
            .build();
        routingTransitions = meter.counterBuilder(MetricNames.ROUTING_TRANSITIONS)
            .setDescription("Capture assignment and routing-generation transitions")
            .setUnit("transitions")
            .build();
        writersActive = meter.upDownCounterBuilder(MetricNames.WRITERS_ACTIVE)
            .setDescription("Writer-partition lanes in the active routing generation")
            .setUnit("writers")
            .build();
        writersDraining = meter.upDownCounterBuilder(MetricNames.WRITERS_DRAINING)
            .setDescription("Superseded writer-partition lanes draining existing connections")
            .setUnit("writers")
            .build();
        writersRetired = meter.counterBuilder(MetricNames.WRITERS_RETIRED)
            .setDescription("Writer-partition lanes retired after draining")
            .setUnit("writers")
            .build();
        writeGateTransitions = meter.counterBuilder(MetricNames.WRITE_GATE_TRANSITIONS)
            .setDescription("Irreversible transitions that close the capture Kafka write gate")
            .setUnit("transitions")
            .build();
        writesRejected = meter.counterBuilder(MetricNames.WRITES_REJECTED)
            .setDescription("Capture write submissions rejected after the write gate closed")
            .setUnit("writes")
            .build();
        publisherFailures = meter.counterBuilder(MetricNames.PUBLISHER_FAILURES)
            .setDescription("Terminal capture Kafka publisher failures by bounded reason")
            .setUnit("failures")
            .build();
    }

    public void publicationAccepted(@NonNull RecordKind kind, long bytes) {
        var attributes = kindAttributes(kind);
        recordsAccepted.add(1, attributes);
        bytesAccepted.add(bytes, attributes);
        recordsAwaitingAcknowledgement.add(1, attributes);
        bytesAwaitingAcknowledgement.add(bytes, attributes);
        if (kind == RecordKind.HEARTBEAT) {
            heartbeatSubmissions.add(1);
        }
    }

    public void publicationCompleted(
        @NonNull RecordKind kind,
        @NonNull PublishOutcome outcome,
        long bytes
    ) {
        completions.add(
            1,
            Attributes.of(RECORD_KIND, label(kind), OUTCOME, label(outcome))
        );
        var attributes = kindAttributes(kind);
        recordsAwaitingAcknowledgement.add(-1, attributes);
        bytesAwaitingAcknowledgement.add(-bytes, attributes);
        if (kind == RecordKind.HEARTBEAT && outcome == PublishOutcome.ACKNOWLEDGED) {
            heartbeatAcknowledgements.add(1);
        }
    }

    public void heartbeatDeadlineViolated() {
        heartbeatDeadlineViolations.add(1);
    }

    public void brokerTimeViolated() {
        brokerTimeViolations.add(1);
    }

    public void routingTransition(@NonNull RoutingTransition transition) {
        routingTransitions.add(1, Attributes.of(TRANSITION, label(transition)));
    }

    public void writersActivated(int activated, int previouslyActive) {
        writersActive.add((long) activated - previouslyActive);
        if (previouslyActive > 0) {
            writersDraining.add(previouslyActive);
        }
    }

    public void activeWritersBeganDraining(int count) {
        if (count > 0) {
            writersActive.add(-count);
            writersDraining.add(count);
        }
    }

    public void writerRetired() {
        writersDraining.add(-1);
        writersRetired.add(1);
    }

    public void writeGateTripped(@NonNull FailureReason reason) {
        writeGateTransitions.add(1, Attributes.of(REASON, label(reason)));
    }

    public void writeRejected() {
        writesRejected.add(1);
    }

    public void publisherFailed(@NonNull FailureReason reason) {
        publisherFailures.add(1, Attributes.of(REASON, label(reason)));
    }

    private static Attributes kindAttributes(RecordKind kind) {
        return Attributes.of(RECORD_KIND, label(kind));
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
