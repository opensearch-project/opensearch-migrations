package org.opensearch.migrations.replay.tracing;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import org.apache.kafka.common.TopicPartition;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

import java.util.Collection;

public class KafkaConsumerContexts {

    private KafkaConsumerContexts() {}

    @AllArgsConstructor
    public static class AsyncListeningContext implements IKafkaConsumerContexts.IAsyncListeningContext {
        @Getter
        @NonNull
        private final IInstrumentationAttributes enclosingScope;

        @Override
        public @NonNull IInstrumentConstructor getRootInstrumentationScope() {
            return enclosingScope.getRootInstrumentationScope();
        }

        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            meterIncrementEvent(IKafkaConsumerContexts.MetricNames.PARTITIONS_REVOKED_EVENT_COUNT);
            onParitionsAssignedChanged(partitions.size());
        }

        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            meterIncrementEvent(IKafkaConsumerContexts.MetricNames.PARTITIONS_ASSIGNED_EVENT_COUNT);
            onParitionsAssignedChanged(partitions.size());
        }

        private void onParitionsAssignedChanged(int delta) {
            meterDeltaEvent(IKafkaConsumerContexts.MetricNames.ACTIVE_PARTITIONS_ASSIGNED_COUNT, delta);
        }
    }

    public static class TouchScopeContext extends DirectNestedSpanContext<IInstrumentationAttributes>
        implements IKafkaConsumerContexts.ITouchScopeContext
    {
        public TouchScopeContext(@NonNull IInstrumentationAttributes enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }
    }

    public static class PollScopeContext extends DirectNestedSpanContext<IInstrumentationAttributes>
        implements IKafkaConsumerContexts.IPollScopeContext {
        public PollScopeContext(@NonNull IInstrumentationAttributes enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }
    }

    public static class CommitScopeContext extends DirectNestedSpanContext<IInstrumentationAttributes>
        implements IKafkaConsumerContexts.ICommitScopeContext {
        public CommitScopeContext(@NonNull IInstrumentationAttributes enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }
    }

    public static class KafkaCommitScopeContext
            extends DirectNestedSpanContext<KafkaConsumerContexts.CommitScopeContext>
            implements IKafkaConsumerContexts.IKafkaCommitScopeContext {

        public KafkaCommitScopeContext(@NonNull KafkaConsumerContexts.CommitScopeContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }
    }
}
