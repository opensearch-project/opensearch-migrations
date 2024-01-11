package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.metrics.MeterProvider;
import lombok.NonNull;
import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

public class TrafficSourceContexts {

    private TrafficSourceContexts() {}

    public static class ReadChunkContext
            extends BaseNestedSpanContext<RootReplayerContext, IInstrumentationAttributes>
            implements ITrafficSourceContexts.IReadChunkContext
    {
        @Override
        public ITrafficSourceContexts.IBackPressureBlockContext createBackPressureContext() {
            return new TrafficSourceContexts.BackPressureBlockContext(getRootInstrumentationScope(), this);
        }

        @Override
        public IKafkaConsumerContexts.IPollScopeContext createPollContext() {
            return new KafkaConsumerContexts.PollScopeContext(getRootInstrumentationScope(), this);
        }

        @Override
        public IKafkaConsumerContexts.ICommitScopeContext createCommitContext() {
            return new KafkaConsumerContexts.CommitScopeContext(getRootInstrumentationScope(), this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME, ACTIVITY_NAME);
            }
        }
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().readChunkInstruments;
        }

        public ReadChunkContext(RootReplayerContext rootScope, IInstrumentationAttributes enclosingScope) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }

    }

    public static class BackPressureBlockContext
            extends BaseNestedSpanContext<RootReplayerContext, ITrafficSourceContexts.IReadChunkContext>
            implements ITrafficSourceContexts.IBackPressureBlockContext
    {
        @Override
        public ITrafficSourceContexts.IWaitForNextSignal createWaitForSignalContext() {
            return new TrafficSourceContexts.WaitForNextSignal(getRootInstrumentationScope(), this);
        }

        @Override
        public IKafkaConsumerContexts.ITouchScopeContext createNewTouchContext() {
            return new KafkaConsumerContexts.TouchScopeContext(this);
        }

        @Override
        public IKafkaConsumerContexts.ICommitScopeContext createCommitContext() {
            return new KafkaConsumerContexts.CommitScopeContext(getRootInstrumentationScope(), this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME, ACTIVITY_NAME);
            }
        }
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().backPressureInstruments;
        }

        public BackPressureBlockContext(@NonNull RootReplayerContext rootScope,
                                        @NonNull ITrafficSourceContexts.IReadChunkContext enclosingScope) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

    public static class WaitForNextSignal
            extends BaseNestedSpanContext<RootReplayerContext, ITrafficSourceContexts.IBackPressureBlockContext>
            implements ITrafficSourceContexts.IWaitForNextSignal {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME, ACTIVITY_NAME);
            }
        }
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().waitForNextSignalInstruments;
        }

        public WaitForNextSignal(@NonNull RootReplayerContext rootScope,
                                 @NonNull ITrafficSourceContexts.IBackPressureBlockContext enclosingScope) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

}
