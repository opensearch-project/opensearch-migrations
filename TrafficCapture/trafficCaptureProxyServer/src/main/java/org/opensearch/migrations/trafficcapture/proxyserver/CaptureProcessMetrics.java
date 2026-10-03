package org.opensearch.migrations.trafficcapture.proxyserver;

import org.opensearch.migrations.trafficcapture.netty.CaptureProcessState;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;

final class CaptureProcessMetrics {
    static final AttributeKey<String> PROCESS_ID = AttributeKey.stringKey("processId");
    static final AttributeKey<String> CAPTURE_ACTIVATION_ID =
        AttributeKey.stringKey("captureActivationId");
    static final AttributeKey<String> MODE = AttributeKey.stringKey("mode");
    static final AttributeKey<Boolean> SOURCE_FORWARDING_CONTINUED =
        AttributeKey.booleanKey("sourceForwardingContinued");
    static final String PASS_THROUGH_TRANSITIONS = "captureProcessPassThroughTransitions";
    static final String TERMINATION_TRANSITIONS = "captureProcessTerminationTransitions";
    static final String CAPTURE_GAP_ACTIVE = "captureGapActive";

    private final LongCounter passThroughTransitions;
    private final LongCounter terminationTransitions;
    private final LongUpDownCounter captureGapActive;
    private final Attributes processAttributes;
    private final Attributes captureGapAttributes;
    private boolean gapActive;

    CaptureProcessMetrics(Meter meter) {
        this(meter, null, null);
    }

    CaptureProcessMetrics(
        Meter meter,
        String processId,
        String captureActivationId
    ) {
        var processAttributesBuilder = Attributes.builder();
        if (processId != null && !processId.isBlank()) {
            processAttributesBuilder.put(PROCESS_ID, processId);
        }
        if (captureActivationId != null && !captureActivationId.isBlank()) {
            processAttributesBuilder.put(CAPTURE_ACTIVATION_ID, captureActivationId);
        }
        processAttributes = processAttributesBuilder.build();
        captureGapAttributes = Attributes.builder()
            .putAll(processAttributes)
            .put(MODE, "fail-open")
            .put(SOURCE_FORWARDING_CONTINUED, true)
            .build();
        passThroughTransitions = meter.counterBuilder(PASS_THROUGH_TRANSITIONS)
            .setDescription("Irreversible proxy transitions from authoritative capture to pass-through")
            .setUnit("count")
            .build();
        terminationTransitions = meter.counterBuilder(TERMINATION_TRANSITIONS)
            .setDescription("Proxy transitions to immediate process termination")
            .setUnit("count")
            .build();
        captureGapActive = meter.upDownCounterBuilder(CAPTURE_GAP_ACTIVE)
            .setDescription("Whether this proxy is alive in irreversible pass-through after capture failure")
            .setUnit("processes")
            .build();
    }

    synchronized void recordTransition(CaptureProcessState.State state) {
        switch (state) {
            case PASS_THROUGH -> {
                passThroughTransitions.add(1, captureGapAttributes);
                if (!gapActive) {
                    captureGapActive.add(1, captureGapAttributes);
                    gapActive = true;
                }
            }
            case TERMINATING -> {
                terminationTransitions.add(1, processAttributes);
                if (gapActive) {
                    captureGapActive.add(-1, captureGapAttributes);
                    gapActive = false;
                }
            }
            case CAPTURE -> throw new IllegalArgumentException(
                "CAPTURE is the initial state, not a process transition"
            );
        }
    }
}
