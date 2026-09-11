package org.opensearch.migrations.trafficcapture.proxyserver;

import org.opensearch.migrations.trafficcapture.netty.CaptureProcessState;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

final class CaptureProcessMetrics {
    static final String PASS_THROUGH_TRANSITIONS = "captureProcessPassThroughTransitions";
    static final String TERMINATION_TRANSITIONS = "captureProcessTerminationTransitions";

    private final LongCounter passThroughTransitions;
    private final LongCounter terminationTransitions;

    CaptureProcessMetrics(Meter meter) {
        passThroughTransitions = meter.counterBuilder(PASS_THROUGH_TRANSITIONS)
            .setDescription("Irreversible proxy transitions from authoritative capture to pass-through")
            .setUnit("count")
            .build();
        terminationTransitions = meter.counterBuilder(TERMINATION_TRANSITIONS)
            .setDescription("Proxy transitions to immediate process termination")
            .setUnit("count")
            .build();
    }

    void recordTransition(CaptureProcessState.State state) {
        switch (state) {
            case PASS_THROUGH -> passThroughTransitions.add(1);
            case TERMINATING -> terminationTransitions.add(1);
            case CAPTURE -> throw new IllegalArgumentException(
                "CAPTURE is the initial state, not a process transition"
            );
        }
    }
}
