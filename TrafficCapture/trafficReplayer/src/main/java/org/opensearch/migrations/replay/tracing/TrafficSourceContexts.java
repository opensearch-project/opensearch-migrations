package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.metrics.MeterProvider;
import lombok.NonNull;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

public class TrafficSourceContexts {

    private TrafficSourceContexts() {}

    public static class ReadChunkContext
            extends DirectNestedSpanContext<RootReplayerContext, IInstrumentationAttributes<RootReplayerContext>>
            implements ITrafficSourceContexts.IReadChunkContext<RootReplayerContext>
    {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME, ACTIVITY_NAME);
            }
        }
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().readChunkInstruments;
        }

        public ReadChunkContext(IInstrumentationAttributes<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }
    }

    public static class BackPressureBlockContext
            extends DirectNestedSpanContext<RootReplayerContext,
                                            ITrafficSourceContexts.IReadChunkContext<RootReplayerContext>>
            implements ITrafficSourceContexts.IBackPressureBlockContext<RootReplayerContext>
    {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME, ACTIVITY_NAME);
            }
        }
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().backPressureInstruments;
        }

        public BackPressureBlockContext(@NonNull ITrafficSourceContexts.IReadChunkContext<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }
    }

    public static class WaitForNextSignal
            extends DirectNestedSpanContext<RootReplayerContext,
                                            ITrafficSourceContexts.IBackPressureBlockContext<RootReplayerContext>>
            implements ITrafficSourceContexts.IWaitForNextSignal<RootReplayerContext> {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME, ACTIVITY_NAME);
            }
        }
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().waitForNextSignalInstruments;
        }

        public WaitForNextSignal(@NonNull ITrafficSourceContexts.IBackPressureBlockContext<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }
    }

}
