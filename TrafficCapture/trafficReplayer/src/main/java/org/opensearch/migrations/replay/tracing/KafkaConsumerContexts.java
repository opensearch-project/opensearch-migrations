package org.opensearch.migrations.replay.tracing;

import lombok.NonNull;
import org.opensearch.migrations.replay.kafka.TrackingKafkaConsumer;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

public class KafkaConsumerContexts {

    private KafkaConsumerContexts() {}

    public static class TouchScopeContext extends DirectNestedSpanContext<IInstrumentationAttributes>
        implements IKafkaConsumerContexts.ITouchScopeContext
    {
        public TouchScopeContext(@NonNull IInstrumentationAttributes enclosingScope) {
            super(enclosingScope);
            setCurrentSpan();
        }
    }

    public static class PollScopeContext extends DirectNestedSpanContext<IInstrumentationAttributes>
        implements IKafkaConsumerContexts.IPollScopeContext {
        public PollScopeContext(@NonNull IInstrumentationAttributes enclosingScope) {
            super(enclosingScope);
            setCurrentSpan();
        }
    }

    public static class CommitScopeContext extends DirectNestedSpanContext<IInstrumentationAttributes>
        implements IKafkaConsumerContexts.ICommitScopeContext {
        public CommitScopeContext(@NonNull IInstrumentationAttributes enclosingScope) {
            super(enclosingScope);
            setCurrentSpan();
        }
    }

    public static class KafkaCommitScopeContext
            extends DirectNestedSpanContext<KafkaConsumerContexts.CommitScopeContext>
            implements IKafkaConsumerContexts.IKafkaCommitScopeContext {

        public KafkaCommitScopeContext(@NonNull KafkaConsumerContexts.CommitScopeContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan();
        }
    }
}
