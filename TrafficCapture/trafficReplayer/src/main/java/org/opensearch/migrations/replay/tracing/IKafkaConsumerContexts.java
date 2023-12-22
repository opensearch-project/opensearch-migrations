package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.IInstrumentationAttributes;

public interface IKafkaConsumerContexts {
    interface IKafkaConsumerScope extends IInstrumentationAttributes {
        @Override
        default String getScopeName() { return IReplayContexts.KAFKA_CONSUMER_SCOPE; }
    }
    interface ITouchScopeContext extends IKafkaCommitScopeContext {}
    interface IPollScopeContext extends IKafkaConsumerScope {}

    interface ICommitScopeContext extends IKafkaConsumerScope {}

    interface IKafkaCommitScopeContext extends IKafkaConsumerScope {}
}
