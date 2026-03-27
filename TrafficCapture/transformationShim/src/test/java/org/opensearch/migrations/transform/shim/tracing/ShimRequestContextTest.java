package org.opensearch.migrations.transform.shim.tracing;

import java.util.List;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShimRequestContextTest {

    private InMemoryInstrumentationBundle bundle;
    private RootShimProxyContext rootContext;

    @BeforeEach
    void setUp() {
        bundle = new InMemoryInstrumentationBundle(true, true);
        rootContext = new RootShimProxyContext(bundle.openTelemetrySdk, IContextTracker.DO_NOTHING_TRACKER);
    }

    @AfterEach
    void tearDown() {
        bundle.close();
    }

    @Test
    void shimRequestSpan_emittedWithCorrectAttributes() {
        try (var ctx = new ShimRequestContext(rootContext, "GET", "/test/path")) {
            assertEquals("shimRequest", ctx.getActivityName());
        }

        List<SpanData> spans = bundle.getFinishedSpans();
        assertEquals(1, spans.size());

        SpanData span = spans.get(0);
        assertEquals("shimRequest", span.getName());
        assertEquals("GET", span.getAttributes().get(ShimRequestContext.HTTP_METHOD_ATTR));
        assertEquals("/test/path", span.getAttributes().get(ShimRequestContext.HTTP_URL_ATTR));
    }

    @Test
    void shimRequestSpan_emittedForPostMethod() {
        try (var ctx = new ShimRequestContext(rootContext, "POST", "/api/update")) {
            // span is open
        }

        SpanData span = bundle.getFinishedSpans().get(0);
        assertEquals("POST", span.getAttributes().get(ShimRequestContext.HTTP_METHOD_ATTR));
        assertEquals("/api/update", span.getAttributes().get(ShimRequestContext.HTTP_URL_ATTR));
    }

    @Test
    void shimRequestMetrics_countAndDurationEmitted() {
        try (var ctx = new ShimRequestContext(rootContext, "GET", "/metrics-test")) {
            // simulate some work
        }

        var metrics = bundle.getFinishedMetrics();
        assertFalse(metrics.isEmpty(), "Expected metrics to be emitted");

        long count = InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "shimRequestCount");
        assertTrue(count > 0, "shimRequestCount should be > 0, got: " + count);
    }

    @Test
    void shimRequestSpan_recordsException() {
        var exception = new RuntimeException("test error");
        try (var ctx = new ShimRequestContext(rootContext, "GET", "/error")) {
            ctx.addTraceException(exception, true);
        }

        SpanData span = bundle.getFinishedSpans().get(0);
        assertFalse(span.getEvents().isEmpty(), "Expected exception event on span");

        var metrics = bundle.getFinishedMetrics();
        long exceptionCount = InMemoryInstrumentationBundle.getMetricValueOrZero(
            metrics, "shimRequestExceptionCount");
        assertTrue(exceptionCount > 0, "Exception counter should be > 0");
    }

    @Test
    void shimRequestSpan_enclosingScopeIsNull() {
        try (var ctx = new ShimRequestContext(rootContext, "GET", "/")) {
            assertNotNull(ctx.getCurrentSpan());
            assertEquals(null, ctx.getEnclosingScope());
        }
    }

    @Test
    void multipleRequests_eachProducesSpan() {
        for (int i = 0; i < 3; i++) {
            try (var ctx = new ShimRequestContext(rootContext, "GET", "/req-" + i)) {
                // each request
            }
        }

        assertEquals(3, bundle.getFinishedSpans().size());

        var metrics = bundle.getFinishedMetrics();
        long count = InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "shimRequestCount");
        assertEquals(3, count);
    }
}
