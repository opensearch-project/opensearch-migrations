package org.opensearch.migrations.replay.tracing;

import java.time.Duration;
import java.util.Collection;

import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonMetricInstruments;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.apache.kafka.common.TopicPartition;

public class KafkaConsumerContexts {

    private KafkaConsumerContexts() {}

    public static class LivenessScanContext implements IKafkaConsumerContexts.ILivenessScanContext {
        public static final AttributeKey<String> VERDICT_ATTRIBUTE = AttributeKey.stringKey("verdict");

        @Getter
        @NonNull
        public final RootReplayerContext enclosingScope;
        @Getter
        @Setter
        Exception observedExceptionToIncludeInMetrics;

        public static class MetricInstruments extends CommonMetricInstruments {
            public final LongCounter scanCounter;
            public final LongHistogram distance;
            public final DoubleHistogram latency;
            public final LongCounter bytesDiscarded;
            public final LongCounter verdictCounter;

            private MetricInstruments(Meter meter) {
                super(meter, "livenessScan");
                scanCounter = meter.counterBuilder(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_COUNT)
                    .setUnit("scans")
                    .build();
                distance = meter.histogramBuilder(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_DISTANCE)
                    .ofLongs()
                    .setUnit("records")
                    .build();
                latency = meter.histogramBuilder(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_LATENCY)
                    .setUnit("ms")
                    .build();
                bytesDiscarded = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_BYTES_DISCARDED
                ).setUnit("By").build();
                verdictCounter = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_VERDICT_COUNT
                ).setUnit("verdicts").build();
            }
        }

        public LivenessScanContext(@NonNull RootReplayerContext enclosingScope) {
            this.enclosingScope = enclosingScope;
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return enclosingScope.livenessScanInstruments;
        }

        @Override
        public void recordCycle(int recordsScanned, long discardedBytes, Duration duration) {
            meterIncrementEvent(getMetrics().scanCounter);
            meterHistogram(getMetrics().distance, recordsScanned);
            meterHistogramMillis(getMetrics().latency, duration);
            meterIncrementEvent(getMetrics().bytesDiscarded, discardedBytes);
        }

        @Override
        public void recordVerdict(@NonNull IKafkaConsumerContexts.LivenessScanVerdict verdict) {
            meterIncrementEvent(
                getMetrics().verdictCounter,
                1,
                Attributes.builder().put(VERDICT_ATTRIBUTE, verdict.metricLabel())
            );
        }
    }

    public static class AsyncListeningContext implements IKafkaConsumerContexts.IAsyncListeningContext {
        @Getter
        @NonNull
        public final RootReplayerContext enclosingScope;
        @Getter
        @Setter
        Exception observedExceptionToIncludeInMetrics;

        public AsyncListeningContext(@NonNull RootReplayerContext enclosingScope) {
            this.enclosingScope = enclosingScope;
        }

        public static class MetricInstruments extends CommonMetricInstruments {
            public final LongCounter kafkaPartitionsRevokedCounter;
            public final LongCounter kafkaPartitionsAssignedCounter;
            public final LongUpDownCounter kafkaActivePartitionsCounter;

            private MetricInstruments(Meter meter) {
                super(meter, "asyncKafkaProcessing");
                kafkaPartitionsRevokedCounter = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.PARTITIONS_REVOKED_EVENT_COUNT
                ).build();
                kafkaPartitionsAssignedCounter = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.PARTITIONS_ASSIGNED_EVENT_COUNT
                ).build();
                kafkaActivePartitionsCounter = meter.upDownCounterBuilder(
                    IKafkaConsumerContexts.MetricNames.ACTIVE_PARTITIONS_ASSIGNED_COUNT
                ).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter);
        }

        @NonNull
        public MetricInstruments getMetrics() {
            return enclosingScope.asyncListeningInstruments;
        }

        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            meterIncrementEvent(getMetrics().kafkaPartitionsRevokedCounter);
            onPartitionsAssignedChanged(-1 * partitions.size());
        }

        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            meterIncrementEvent(getMetrics().kafkaPartitionsAssignedCounter);
            onPartitionsAssignedChanged(partitions.size());
        }

        private void onPartitionsAssignedChanged(int delta) {
            meterDeltaEvent(getMetrics().kafkaActivePartitionsCounter, delta);
        }
    }

    public static class TouchScopeContext extends DirectNestedSpanContext<
        RootReplayerContext,
        TrafficSourceContexts.BackPressureBlockContext,
        ITrafficSourceContexts.IBackPressureBlockContext> implements IKafkaConsumerContexts.ITouchScopeContext {
        @Override
        public IKafkaConsumerContexts.IPollScopeContext createNewPollContext() {
            return new KafkaConsumerContexts.PollScopeContext(getRootInstrumentationScope(), this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public TouchScopeContext(@NonNull TrafficSourceContexts.BackPressureBlockContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().touchInstruments;
        }
    }

    public static class PollScopeContext extends BaseNestedSpanContext<
        RootReplayerContext,
        IScopedInstrumentationAttributes> implements IKafkaConsumerContexts.IPollScopeContext {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().pollInstruments;
        }

        public PollScopeContext(
            @NonNull RootReplayerContext rootScope,
            @NonNull IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

    public static class CommitScopeContext extends BaseNestedSpanContext<
        RootReplayerContext,
        IScopedInstrumentationAttributes> implements IKafkaConsumerContexts.ICommitScopeContext {

        @Override
        public IKafkaConsumerContexts.IKafkaCommitScopeContext createNewKafkaCommitContext() {
            return new KafkaConsumerContexts.KafkaCommitScopeContext(this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().commitInstruments;
        }

        public CommitScopeContext(
            @NonNull RootReplayerContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

    public static class KafkaCommitScopeContext extends DirectNestedSpanContext<
        RootReplayerContext,
        KafkaConsumerContexts.CommitScopeContext,
        IKafkaConsumerContexts.ICommitScopeContext> implements IKafkaConsumerContexts.IKafkaCommitScopeContext {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().kafkaCommitInstruments;
        }

        public KafkaCommitScopeContext(@NonNull KafkaConsumerContexts.CommitScopeContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

    }
}
