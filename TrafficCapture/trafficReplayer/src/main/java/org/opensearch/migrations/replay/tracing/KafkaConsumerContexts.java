package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.apache.kafka.common.TopicPartition;
import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IHasRootInstrumentationScope;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

import java.util.Collection;

public class KafkaConsumerContexts {

    private KafkaConsumerContexts() {}

    public static class AsyncListeningContext
            implements IKafkaConsumerContexts.IAsyncListeningContext {
        @Getter
        @NonNull
        public final RootReplayerContext enclosingScope;
        @Getter @Setter
        Exception observedExceptionToIncludeInMetrics;

        public AsyncListeningContext(@NonNull RootReplayerContext enclosingScope) {
            this.enclosingScope = enclosingScope;
        }

        public static class MetricInstruments {
            public final LongCounter kafkaPartitionsRevokedCounter;
            public final LongCounter kafkaPartitionsAssignedCounter;
            public final LongUpDownCounter kafkaActivePartitionsCounter;
            public MetricInstruments(Meter meter) {
                kafkaPartitionsRevokedCounter = meter
                        .counterBuilder(IKafkaConsumerContexts.MetricNames.PARTITIONS_REVOKED_EVENT_COUNT).build();
                kafkaPartitionsAssignedCounter = meter
                        .counterBuilder(IKafkaConsumerContexts.MetricNames.PARTITIONS_ASSIGNED_EVENT_COUNT).build();
                kafkaActivePartitionsCounter = meter
                        .upDownCounterBuilder(IKafkaConsumerContexts.MetricNames.ACTIVE_PARTITIONS_ASSIGNED_COUNT).build();
            }
        }

        private @NonNull MetricInstruments getMetrics() {
            return enclosingScope.asyncListeningInstruments;
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
            extends DirectNestedSpanContext<RootReplayerContext, TrafficSourceContexts.BackPressureBlockContext, ITrafficSourceContexts.IBackPressureBlockContext>
            implements IKafkaConsumerContexts.ITouchScopeContext
    {
        @Override
        public IKafkaConsumerContexts.IPollScopeContext createNewPollContext() {
            return new KafkaConsumerContexts.PollScopeContext(getRootInstrumentationScope(), this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }
        public TouchScopeContext(@NonNull TrafficSourceContexts.BackPressureBlockContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().touchInstruments;
        }
    }

    public static class PollScopeContext
            extends BaseNestedSpanContext<RootReplayerContext, IInstrumentationAttributes>
            implements IKafkaConsumerContexts.IPollScopeContext {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        @Override
        public CommonScopedMetricInstruments getMetrics() {
            return getRootInstrumentationScope().pollInstruments;
        }

        public PollScopeContext(@NonNull RootReplayerContext rootScope,
                                @NonNull IInstrumentationAttributes enclosingScope) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

    public static class CommitScopeContext
            extends BaseNestedSpanContext<RootReplayerContext, IInstrumentationAttributes>
        implements IKafkaConsumerContexts.ICommitScopeContext {

        @Override
        public IKafkaConsumerContexts.IKafkaCommitScopeContext createNewKafkaCommitContext() {
            return new KafkaConsumerContexts.KafkaCommitScopeContext(this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().commitInstruments;
        }

        public CommitScopeContext(@NonNull RootReplayerContext rootScope,
                                  @NonNull IInstrumentationAttributes enclosingScope) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

    public static class KafkaCommitScopeContext
            extends DirectNestedSpanContext<RootReplayerContext, KafkaConsumerContexts.CommitScopeContext, IKafkaConsumerContexts.ICommitScopeContext>
            implements IKafkaConsumerContexts.IKafkaCommitScopeContext {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().kafkaCommitInstruments;
        }


        public KafkaCommitScopeContext(@NonNull KafkaConsumerContexts.CommitScopeContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

    }
}
