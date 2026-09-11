package org.opensearch.migrations.trafficcapture.proxyserver;

import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.netty.CaptureFailurePolicy;
import org.opensearch.migrations.trafficcapture.netty.CaptureProcessState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaptureProcessMetricsTest {
    @Test
    void failClosedMetricIsRecordedBeforeTheTerminationListenerRuns() {
        try (var instrumentation = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new CaptureProcessMetrics(
                instrumentation.openTelemetrySdk.getMeter(
                    RootCaptureContext.SCOPE_NAME
                )
            );
            var state = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_CLOSED,
                metrics::recordTransition
            );
            var metricObservedByTerminationListener = new AtomicLong();
            state.addTerminationListener(ignored ->
                metricObservedByTerminationListener.set(
                    InMemoryInstrumentationBundle.getMetricValueOrZero(
                        instrumentation.getFinishedMetrics(),
                        CaptureProcessMetrics.TERMINATION_TRANSITIONS
                    )
                )
            );

            state.requiredCaptureFailed(new IllegalStateException("capture unavailable"));

            assertEquals(1, metricObservedByTerminationListener.get());
        }
    }

    @Test
    void processCompromiseTransitionsRemainVisibleAsCumulativeMetrics() {
        try (var instrumentation = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new CaptureProcessMetrics(
                instrumentation.openTelemetrySdk.getMeter(
                    RootCaptureContext.SCOPE_NAME
                )
            );
            var state = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_OPEN,
                metrics::recordTransition
            );

            state.requiredCaptureFailed(new IllegalStateException("capture unavailable"));
            state.requiredCaptureFailed(new IllegalStateException("duplicate"));
            state.unstableProcessFailed(new IllegalStateException("process unstable"));

            var recorded = instrumentation.getFinishedMetrics();
            assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    CaptureProcessMetrics.PASS_THROUGH_TRANSITIONS
                )
            );
            assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    CaptureProcessMetrics.TERMINATION_TRANSITIONS
                )
            );
        }
    }
}
