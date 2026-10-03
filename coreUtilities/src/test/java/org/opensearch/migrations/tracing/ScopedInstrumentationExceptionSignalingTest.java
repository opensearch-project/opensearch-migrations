package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.ErrorAttributes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ScopedInstrumentationExceptionSignalingTest {

    private static class TestContext extends BaseSpanContext<RootOtelContext> {
        private final CommonScopedMetricInstruments metrics;

        TestContext(RootOtelContext rootScope, Meter meter) {
            super(rootScope);
            this.metrics = new CommonScopedMetricInstruments(meter, "testActivity");
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() {
            return "testActivity";
        }

        @Override
        public IScopedInstrumentationAttributes getEnclosingScope() {
            return null;
        }

        @Override
        public CommonScopedMetricInstruments getMetrics() {
            return metrics;
        }
    }

    /**
     * Runs one exception through a scope and returns the exported span.  endSpan() is called
     * directly rather than close() so that the assertions below only see what addTraceException
     * put on the span, without the end-of-scope metrics close() would also emit.
     */
    private static SpanData captureSpanForException(Throwable e, boolean isPropagating) {
        try (var bundle = new InMemoryInstrumentationBundle(true, false)) {
            var rootContext = new RootOtelContext(
                "test",
                IContextTracker.DO_NOTHING_TRACKER,
                bundle.getOpenTelemetrySdk()
            );
            var ctx = new TestContext(rootContext, bundle.getOpenTelemetrySdk().getMeter("test"));
            ctx.addTraceException(e, isPropagating);
            ctx.endSpan(IContextTracker.DO_NOTHING_TRACKER);
            return bundle.getFinishedSpans().get(0);
        }
    }

    @Test
    void propagatingExceptionSetsErrorStatusAndErrorType() {
        var span = captureSpanForException(new IllegalStateException("target unreachable"), true);

        Assertions.assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        Assertions.assertEquals("target unreachable", span.getStatus().getDescription());
        Assertions.assertEquals(
            IllegalStateException.class.getName(),
            span.getAttributes().get(ErrorAttributes.ERROR_TYPE)
        );
        Assertions.assertFalse(span.getEvents().isEmpty(), "expected the exception event to be recorded");
    }

    /**
     * Exceptions reach us from arbitrary library code, so the type name has to survive classes that
     * have no canonical or simple name -- getCanonicalName() is null for these and getSimpleName()
     * is empty, either of which would silently strip the signal off the spans that carry a failure.
     */
    @Test
    void anonymousExceptionStillReportsErrorTypeAndDescription() {
        var anonymous = new IllegalStateException() {
        };
        var span = captureSpanForException(anonymous, true);

        Assertions.assertNull(anonymous.getClass().getCanonicalName(), "expected an anonymous class");
        Assertions.assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        Assertions.assertEquals(
            anonymous.getClass().getName(),
            span.getAttributes().get(ErrorAttributes.ERROR_TYPE)
        );
        Assertions.assertEquals(anonymous.getClass().getName(), span.getStatus().getDescription());
    }

    @Test
    void nonPropagatingExceptionRecordsEventWithoutFailingTheSpan() {
        var span = captureSpanForException(new IllegalStateException("retried and recovered"), false);

        Assertions.assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
        Assertions.assertNull(span.getAttributes().get(ErrorAttributes.ERROR_TYPE));
        Assertions.assertFalse(span.getEvents().isEmpty(), "expected the exception event to be recorded");
    }

    @Test
    void messagelessExceptionFallsBackToTypeNameForTheStatusDescription() {
        var span = captureSpanForException(new IllegalStateException(), true);

        Assertions.assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        Assertions.assertEquals("IllegalStateException", span.getStatus().getDescription());
    }
}
