package org.opensearch.migrations.trafficcapture.netty.tracing;

import io.opentelemetry.api.metrics.Meter;
import lombok.Getter;
import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IWithStartTimeAndAttributes;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext;

public class WireCaptureContexts {
    private WireCaptureContexts() {}

    public static abstract class HttpMessageContext extends
            BaseNestedSpanContext<RootWireLoggingContext, IConnectionContext>
            implements IHttpTransactionContext, IWithStartTimeAndAttributes {

        @Getter
        final long sourceRequestIndex;

        public HttpMessageContext(RootWireLoggingContext rootWireLoggingContext, IConnectionContext enclosingScope,
                                  long sourceRequestIndex) {
            super(rootWireLoggingContext, enclosingScope);
            this.sourceRequestIndex = sourceRequestIndex;
        }
    }

    public static class RequestContext extends HttpMessageContext {
        public static final String ACTIVITY_NAME = "gatheringRequest";

        public RequestContext(RootWireLoggingContext rootWireLoggingContext,
                              IConnectionContext enclosingScope,
                              long sourceRequestIndex) {
            super(rootWireLoggingContext, enclosingScope, sourceRequestIndex);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().httpRequestInstruments;
        }
    }

    public static class BlockingContext extends HttpMessageContext {
        public static final String ACTIVITY_NAME = "blocked";

        public BlockingContext(RootWireLoggingContext rootWireLoggingContext,
                              IConnectionContext enclosingScope,
                              long sourceRequestIndex) {
            super(rootWireLoggingContext, enclosingScope, sourceRequestIndex);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        @Override
        public RequestContext.MetricInstruments getMetrics() {
            return getRootInstrumentationScope().httpRequestInstruments;
        }
    }

    public static class WaitingForResponseContext extends HttpMessageContext {
        public static final String ACTIVITY_NAME = "waitingForResponse";
        public WaitingForResponseContext(RootWireLoggingContext rootWireLoggingContext,
                              IConnectionContext enclosingScope,
                              long sourceRequestIndex) {
            super(rootWireLoggingContext, enclosingScope, sourceRequestIndex);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        @Override
        public RequestContext.MetricInstruments getMetrics() {
            return getRootInstrumentationScope().httpRequestInstruments;
        }
    }

    public static class ResponseContext extends HttpMessageContext {
        public static final String ACTIVITY_NAME = "gatheringResponse";
        public ResponseContext(RootWireLoggingContext rootWireLoggingContext,
                              IConnectionContext enclosingScope,
                              long sourceRequestIndex) {
            super(rootWireLoggingContext, enclosingScope, sourceRequestIndex);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        @Override
        public RequestContext.MetricInstruments getMetrics() {
            return getRootInstrumentationScope().httpRequestInstruments;
        }
    }
}
