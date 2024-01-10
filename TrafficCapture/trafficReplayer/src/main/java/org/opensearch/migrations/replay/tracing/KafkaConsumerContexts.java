package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.MeterProvider;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import org.apache.kafka.common.TopicPartition;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

import java.util.Collection;

public class KafkaConsumerContexts {

    private KafkaConsumerContexts() {}

    @AllArgsConstructor
    public static class AsyncListeningContext
            implements IKafkaConsumerContexts.IAsyncListeningContext<RootReplayerContext> {
        public static class MetricInstruments {
            public final LongCounter kafkaPartitionsRevokedCounter;
            public final LongCounter kafkaPartitionsAssignedCounter;
            public final LongUpDownCounter kafkaActivePartitionsCounter;
            public MetricInstruments(MeterProvider meterProvider) {
                var meter = meterProvider.get(SCOPE_NAME);
                kafkaPartitionsRevokedCounter = meter
                        .counterBuilder(IKafkaConsumerContexts.MetricNames.PARTITIONS_REVOKED_EVENT_COUNT).build();
                kafkaPartitionsAssignedCounter = meter
                        .counterBuilder(IKafkaConsumerContexts.MetricNames.PARTITIONS_ASSIGNED_EVENT_COUNT).build();
                kafkaActivePartitionsCounter = meter
                        .upDownCounterBuilder(IKafkaConsumerContexts.MetricNames.ACTIVE_PARTITIONS_ASSIGNED_COUNT).build();
            }
        }

        @Getter
        @NonNull
        private final IInstrumentationAttributes<RootReplayerContext> enclosingScope;

        @Override
        public @NonNull RootReplayerContext getRootInstrumentationScope() {
            return enclosingScope.getRootInstrumentationScope();
        }

        private @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().asyncListeningInstruments;
        }

        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            meterIncrementEvent(getMetrics().kafkaPartitionsRevokedCounter);
            onParitionsAssignedChanged(partitions.size());
        }

        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            meterIncrementEvent(getMetrics().kafkaPartitionsAssignedCounter);
            onParitionsAssignedChanged(partitions.size());
        }

        private void onParitionsAssignedChanged(int delta) {
            meterDeltaEvent(getMetrics().kafkaActivePartitionsCounter, delta);
        }
    }

    public static class TouchScopeContext
            extends DirectNestedSpanContext<RootReplayerContext, IInstrumentationAttributes<RootReplayerContext>>
            implements IKafkaConsumerContexts.ITouchScopeContext
    {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME, ACTIVITY_NAME);
            }
        }
        public TouchScopeContext(@NonNull IInstrumentationAttributes<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().touchInstruments;
        }
    }

    public static class PollScopeContext
            extends DirectNestedSpanContext<IRootReplayerContext, IInstrumentationAttributes<IRootReplayerContext>>
        implements IKafkaConsumerContexts.IPollScopeContext {
        @Override
        public CommonScopedMetricInstruments getMetrics() {
            return getRootInstrumentationScope().poll;
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME, ACTIVITY_NAME);
            }
        }
        public PollScopeContext(@NonNull IInstrumentationAttributes<IRootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

    }

    public static class CommitScopeContext
            extends DirectNestedSpanContext<IRootReplayerContext, IInstrumentationAttributes<IRootReplayerContext>>
        implements IKafkaConsumerContexts.ICommitScopeContext {
        public CommitScopeContext(@NonNull IInstrumentationAttributes<IRootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        @Override
        public DoubleHistogram getEndOfScopeDurationMetric() {
            return getRootInstrumentationScope().getCommitDuration();
        }

        @Override
        public LongCounter getEndOfScopeCountMetric() {
            return getRootInstrumentationScope().getCommitCounter();
        }
    }

    public static class KafkaCommitScopeContext
            extends DirectNestedSpanContext<IRootReplayerContext, KafkaConsumerContexts.CommitScopeContext>
            implements IKafkaConsumerContexts.IKafkaCommitScopeContext {

        public KafkaCommitScopeContext(@NonNull KafkaConsumerContexts.CommitScopeContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        @Override
        public DoubleHistogram getEndOfScopeDurationMetric() {
            return getRootInstrumentationScope().getKafkaCommitDuration();
        }

        @Override
        public LongCounter getEndOfScopeCountMetric() {
            return getRootInstrumentationScope().getKafkaCommitCounter();
        }
    }
}
