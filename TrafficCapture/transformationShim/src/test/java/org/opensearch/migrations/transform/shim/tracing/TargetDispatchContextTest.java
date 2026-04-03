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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetDispatchContextTest {

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
    void targetDispatchSpan_emittedAsChildOfShimRequest() {
        try (var requestCtx = new ShimRequestContext(rootContext, "GET", "/test")) {
            try (var dispatchCtx = (TargetDispatchContext) requestCtx.createTargetDispatchContext("solr")) {
                dispatchCtx.setStatusCode(200);
                dispatchCtx.addBytesSent(100);
                dispatchCtx.addBytesReceived(500);
            }
        }

        List<SpanData> spans = bundle.getFinishedSpans();
        assertEquals(2, spans.size());

        SpanData dispatchSpan = spans.stream()
            .filter(s -> "targetDispatch".equals(s.getName()))
            .findFirst().orElseThrow();

        assertEquals("solr", dispatchSpan.getAttributes().get(TargetDispatchContext.TARGET_NAME_ATTR));
        assertEquals(200L, dispatchSpan.getAttributes().get(TargetDispatchContext.HTTP_STATUS_CODE_ATTR));
        assertEquals(100L, dispatchSpan.getAttributes().get(TargetDispatchContext.BYTES_SENT_ATTR));
        assertEquals(500L, dispatchSpan.getAttributes().get(TargetDispatchContext.BYTES_RECEIVED_ATTR));

        // Verify parent-child relationship
        SpanData requestSpan = spans.stream()
            .filter(s -> "shimRequest".equals(s.getName()))
            .findFirst().orElseThrow();
        assertEquals(requestSpan.getSpanId(), dispatchSpan.getParentSpanId());
    }

    @Test
    void multipleTargetDispatches_eachProducesSpan() {
        try (var requestCtx = new ShimRequestContext(rootContext, "POST", "/search")) {
            try (var solrCtx = (TargetDispatchContext) requestCtx.createTargetDispatchContext("solr")) {
                solrCtx.setStatusCode(200);
            }
            try (var osCtx = (TargetDispatchContext) requestCtx.createTargetDispatchContext("opensearch")) {
                osCtx.setStatusCode(201);
            }
        }

        List<SpanData> dispatchSpans = bundle.getFinishedSpans().stream()
            .filter(s -> "targetDispatch".equals(s.getName()))
            .toList();
        assertEquals(2, dispatchSpans.size());

        assertTrue(dispatchSpans.stream().anyMatch(
            s -> "solr".equals(s.getAttributes().get(TargetDispatchContext.TARGET_NAME_ATTR))));
        assertTrue(dispatchSpans.stream().anyMatch(
            s -> "opensearch".equals(s.getAttributes().get(TargetDispatchContext.TARGET_NAME_ATTR))));
    }

    @Test
    void targetDispatchMetrics_bytesCountersEmitted() {
        try (var requestCtx = new ShimRequestContext(rootContext, "GET", "/bytes")) {
            try (var dispatchCtx = (TargetDispatchContext) requestCtx.createTargetDispatchContext("alpha")) {
                dispatchCtx.addBytesSent(256);
                dispatchCtx.addBytesReceived(1024);
            }
        }

        var metrics = bundle.getFinishedMetrics();
        long bytesSent = InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "targetBytesSent");
        long bytesReceived = InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "targetBytesReceived");
        assertTrue(bytesSent > 0, "targetBytesSent should be > 0");
        assertTrue(bytesReceived > 0, "targetBytesReceived should be > 0");
    }

    @Test
    void targetDispatchSpan_inheritsParentAttributes() {
        try (var requestCtx = new ShimRequestContext(rootContext, "DELETE", "/items/42")) {
            try (var dispatchCtx = (TargetDispatchContext) requestCtx.createTargetDispatchContext("backend")) {
                // parent attributes should propagate
            }
        }

        SpanData dispatchSpan = bundle.getFinishedSpans().stream()
            .filter(s -> "targetDispatch".equals(s.getName()))
            .findFirst().orElseThrow();

        // http.method and http.url propagated from parent via fillAttributesForSpansBelow
        assertEquals("DELETE", dispatchSpan.getAttributes().get(ShimRequestContext.HTTP_METHOD_ATTR));
        assertEquals("/items/42", dispatchSpan.getAttributes().get(ShimRequestContext.HTTP_URL_ATTR));
    }

    @Test
    void targetDispatchSpan_recordsException() {
        try (var requestCtx = new ShimRequestContext(rootContext, "GET", "/fail")) {
            try (var dispatchCtx = (TargetDispatchContext) requestCtx.createTargetDispatchContext("broken")) {
                dispatchCtx.addTraceException(new RuntimeException("connection refused"), true);
            }
        }

        SpanData dispatchSpan = bundle.getFinishedSpans().stream()
            .filter(s -> "targetDispatch".equals(s.getName()))
            .findFirst().orElseThrow();
        assertFalse(dispatchSpan.getEvents().isEmpty(), "Expected exception event on dispatch span");
    }
}
