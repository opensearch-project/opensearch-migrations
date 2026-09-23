package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public class TrafficSourceContexts {

    private TrafficSourceContexts() {}

    public static class ReadChunkContext extends BaseNestedSpanContext<
        RootReplayerContext,
        IScopedInstrumentationAttributes> implements ITrafficSourceContexts.IReadChunkContext {
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
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().readChunkInstruments;
        }

        public ReadChunkContext(RootReplayerContext rootScope, IScopedInstrumentationAttributes enclosingScope) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }

    }

    public static class BackPressureBlockContext extends BaseNestedSpanContext<
        RootReplayerContext,
        ITrafficSourceContexts.IReadChunkContext> implements ITrafficSourceContexts.IBackPressureBlockContext {
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
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().backPressureInstruments;
        }

        public BackPressureBlockContext(
            @NonNull RootReplayerContext rootScope,
            @NonNull ITrafficSourceContexts.IReadChunkContext enclosingScope
        ) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

    public static class WaitForNextSignal extends BaseNestedSpanContext<
        RootReplayerContext,
        ITrafficSourceContexts.IBackPressureBlockContext> implements ITrafficSourceContexts.IWaitForNextSignal {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().waitForNextSignalInstruments;
        }

        public WaitForNextSignal(
            @NonNull RootReplayerContext rootScope,
            @NonNull ITrafficSourceContexts.IBackPressureBlockContext enclosingScope
        ) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

}

*/
// REBUILD-LIMBO-END(G11)