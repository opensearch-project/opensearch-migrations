package org.opensearch.migrations.tracing;

import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.semconv.ServiceAttributes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RootOtelContextTest {
    @Test
    void absentEndpointsCreateNoopSdk() {
        Assertions.assertDoesNotThrow(() ->
            RootOtelContext.initializeOpenTelemetryWithCollectorsOrAsNoop(
                OtelCollectorEndpoints.empty(),
                "test-service",
                "test-instance"
            )
        );
    }

    @Test
    void blankTraceEndpointThrows() {
        var endpoints = new OtelCollectorEndpoints("  ", null);
        var exception = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> RootOtelContext.initializeOpenTelemetryWithCollectorsOrAsNoop(
                endpoints,
                "test-service",
                "test-instance"
            )
        );
        Assertions.assertTrue(exception.getMessage().contains("trace endpoint cannot be blank"));
    }

    @Test
    void blankMetricsEndpointThrows() {
        var endpoints = new OtelCollectorEndpoints(null, "");
        var exception = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> RootOtelContext.initializeOpenTelemetryWithCollectorsOrAsNoop(
                endpoints,
                "test-service",
                "test-instance"
            )
        );
        Assertions.assertTrue(exception.getMessage().contains("metrics endpoint cannot be blank"));
    }

    @Test
    void endpointNormalizationAddsHttpScheme() {
        Assertions.assertEquals(
            "http://otel-collector:4317",
            RootOtelContext.normalizeOtlpEndpoint("otel-collector:4317", "metrics")
        );
    }

    @Test
    void endpointNormalizationKeepsHttpAndHttpsSchemes() {
        Assertions.assertEquals(
            "http://otel-collector:4317",
            RootOtelContext.normalizeOtlpEndpoint("http://otel-collector:4317", "metrics")
        );
        Assertions.assertEquals(
            "https://otel-collector:4317",
            RootOtelContext.normalizeOtlpEndpoint("https://otel-collector:4317", "metrics")
        );
    }

    @Test
    void malformedEndpointThrows() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> RootOtelContext.normalizeOtlpEndpoint("http://", "metrics")
        );
    }

    @Test
    void differentInstanceNamesProduceDistinctMetricResources() {
        var firstMetric = collectMetric("proxy-one");
        var secondMetric = collectMetric("proxy-two");

        Assertions.assertNotEquals(firstMetric.getResource(), secondMetric.getResource());
        Assertions.assertEquals(
            "capture",
            firstMetric.getResource().getAttribute(ServiceAttributes.SERVICE_NAME)
        );
        Assertions.assertEquals(
            "proxy-one",
            firstMetric.getResource().getAttribute(ServiceAttributes.SERVICE_INSTANCE_ID)
        );
        Assertions.assertEquals(
            "proxy-two",
            secondMetric.getResource().getAttribute(ServiceAttributes.SERVICE_INSTANCE_ID)
        );
    }

    private static MetricData collectMetric(String instanceName) {
        var metricReader = InMemoryMetricReader.create();
        try (var openTelemetry = RootOtelContext.buildOpenTelemetryForCollectors(
            new OtelCollectorEndpoints(null, "collector:4317"),
            "capture",
            instanceName,
            endpoint -> metricReader
        )) {
            openTelemetry.getMeter("test").counterBuilder("requests").build().add(1);
            return metricReader.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals("requests"))
                .findFirst()
                .orElseThrow();
        }
    }
}
