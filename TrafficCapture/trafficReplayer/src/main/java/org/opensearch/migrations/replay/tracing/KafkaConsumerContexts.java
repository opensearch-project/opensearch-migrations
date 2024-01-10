package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import org.apache.kafka.common.TopicPartition;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

import java.util.Collection;

public class KafkaConsumerContexts {

    private KafkaConsumerContexts() {}

    @AllArgsConstructor
    public static class AsyncListeningContext implements IKafkaConsumerContexts.IAsyncListeningContext {
        @Getter
        @NonNull
        private final IInstrumentationAttributes<IRootReplayerContext> enclosingScope;

        @Override
        public @NonNull IRootReplayerContext getRootInstrumentationScope() {
            return enclosingScope.getRootInstrumentationScope();
        }

        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            meterIncrementEvent(getRootInstrumentationScope().getKafkaPartitionsRevokedCounter());
            onParitionsAssignedChanged(partitions.size());
        }

        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            meterIncrementEvent(getRootInstrumentationScope().getKafkaPartitionsAssignedCounter());
            onParitionsAssignedChanged(partitions.size());
        }

        private void onParitionsAssignedChanged(int delta) {
            meterDeltaEvent(getRootInstrumentationScope().getKafkaActivePartitionsCounter(), delta);
        }
    }

    public static class TouchScopeContext
            extends DirectNestedSpanContext<IRootReplayerContext, IInstrumentationAttributes<IRootReplayerContext>>
            implements IKafkaConsumerContexts.ITouchScopeContext
    {
        public TouchScopeContext(@NonNull IInstrumentationAttributes<IRootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        @Override
        public LongHistogram getEndOfScopeDurationMetric() {
            return getRootInstrumentationScope().getKafkaTouchDuration();
        }

        @Override
        public LongCounter getEndOfScopeCountMetric() {
            return getRootInstrumentationScope().getKafkaTouchCounter();
        }
    }

    public static class PollScopeContext
            extends DirectNestedSpanContext<IRootReplayerContext, IInstrumentationAttributes<IRootReplayerContext>>
        implements IKafkaConsumerContexts.IPollScopeContext {
        public PollScopeContext(@NonNull IInstrumentationAttributes<IRootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        @Override
        public LongHistogram getEndOfScopeDurationMetric() {
            return getRootInstrumentationScope().getKafkaPollDuration();
        }

        @Override
        public LongCounter getEndOfScopeCountMetric() {
            return getRootInstrumentationScope().getKafkaPollCounter();
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
        public LongHistogram getEndOfScopeDurationMetric() {
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
        public LongHistogram getEndOfScopeDurationMetric() {
            return getRootInstrumentationScope().getKafkaCommitDuration();
        }

        @Override
        public LongCounter getEndOfScopeCountMetric() {
            return getRootInstrumentationScope().getKafkaCommitCounter();
        }
    }
}
