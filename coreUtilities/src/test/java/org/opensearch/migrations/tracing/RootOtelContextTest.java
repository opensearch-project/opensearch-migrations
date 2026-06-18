package org.opensearch.migrations.tracing;

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
}
