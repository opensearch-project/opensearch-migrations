package org.opensearch.migrations.tracing;

import java.util.List;

import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommonScopedMetricInstrumentsTest {
    @Test
    public void testThatBadSizeThrowsException() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new CommonScopedMetricInstruments(null, "testActivity", 0, 2)
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new CommonScopedMetricInstruments(null, "testActivity", -2, 2)
        );
        var otelSdkBundle = new InMemoryInstrumentationBundle(false, false);
        Assertions.assertDoesNotThrow(
            () -> new CommonScopedMetricInstruments(
                otelSdkBundle.getOpenTelemetrySdk().getMeter(""),
                "testActivity",
                1,
                8
            )
        );
    }

    @Test
    public void durationsDefaultToA30SecondCeilingAndLongActivitiesExtendToAnHour() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var meter = telemetry.getOpenTelemetrySdk().getMeter("");
            new CommonScopedMetricInstruments(meter, "standard").contextDuration.record(1);
            new CommonScopedMetricInstruments(
                meter,
                "long",
                CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS
            ).contextDuration.record(1);

            var metrics = telemetry.getFinishedMetrics();
            Assertions.assertEquals(CommonScopedMetricInstruments.DURATION_BUCKETS_MS, boundaries(metrics, "standardDuration"));
            Assertions.assertEquals(CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS, boundaries(metrics, "longDuration"));
            Assertions.assertEquals(30_000.0, CommonScopedMetricInstruments.DURATION_BUCKETS_MS.getLast());
            Assertions.assertEquals(3_600_000.0, CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS.getLast());
        }
    }

    private static List<Double> boundaries(Iterable<MetricData> metrics, String name) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                return metric.getHistogramData().getPoints().iterator().next().getBoundaries();
            }
        }
        throw new AssertionError("Missing metric " + name);
    }
}
