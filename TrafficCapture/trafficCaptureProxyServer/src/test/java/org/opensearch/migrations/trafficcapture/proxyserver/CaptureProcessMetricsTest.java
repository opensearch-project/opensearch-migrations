package org.opensearch.migrations.trafficcapture.proxyserver;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.netty.CaptureFailurePolicy;
import org.opensearch.migrations.trafficcapture.netty.CaptureProcessState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void failOpenCaptureGapRemainsActiveAcrossRepeatedMetricCollection() {
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

            assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    instrumentation.getFinishedMetrics(),
                    CaptureProcessMetrics.PASS_THROUGH_TRANSITIONS
                )
            );
            assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    instrumentation.getFinishedMetrics(),
                    CaptureProcessMetrics.CAPTURE_GAP_ACTIVE
                )
            );
            assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    instrumentation.getFinishedMetrics(),
                    CaptureProcessMetrics.CAPTURE_GAP_ACTIVE
                )
            );
        }
    }

    @Test
    void captureGapClearsOnlyWhenThePassThroughProcessBeginsTermination() {
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
            state.unstableProcessFailed(new IllegalStateException("process unstable"));

            var recorded = instrumentation.getFinishedMetrics();
            assertEquals(
                0,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    CaptureProcessMetrics.CAPTURE_GAP_ACTIVE
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

    @Test
    void captureGapCarriesProcessAndActivationAttribution() {
        try (var instrumentation = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new CaptureProcessMetrics(
                instrumentation.openTelemetrySdk.getMeter(
                    RootCaptureContext.SCOPE_NAME
                ),
                "process-a",
                "activation-a"
            );
            var state = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_OPEN,
                metrics::recordTransition
            );

            state.requiredCaptureFailed(new IllegalStateException("capture unavailable"));

            var attributes = findSinglePointAttributes(
                instrumentation.getFinishedMetrics(),
                CaptureProcessMetrics.CAPTURE_GAP_ACTIVE
            );
            assertEquals("process-a", attributes.get(CaptureProcessMetrics.PROCESS_ID));
            assertEquals("activation-a", attributes.get(CaptureProcessMetrics.CAPTURE_ACTIVATION_ID));
            assertEquals("fail-open", attributes.get(CaptureProcessMetrics.MODE));
            assertTrue(attributes.get(CaptureProcessMetrics.SOURCE_FORWARDING_CONTINUED));
        }
    }

    private static io.opentelemetry.api.common.Attributes findSinglePointAttributes(
        Collection<io.opentelemetry.sdk.metrics.data.MetricData> metrics,
        String metricName
    ) {
        var points = metrics.stream()
            .filter(metric -> metric.getName().equals(metricName))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .toList();
        assertEquals(1, points.size());
        return points.getFirst().getAttributes();
    }
}
