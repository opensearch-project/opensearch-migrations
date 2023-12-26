package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IKafkaConsumerContexts {

    class ScopeNames {
        private ScopeNames() {}
        public static final String KAFKA_CONSUMER_SCOPE = "TrackingKafkaConsumer";
    }

    class ActivityNames {
        private ActivityNames() {}
        public static final String TOUCH = "touch";
        public static final String KAFKA_POLL = "kafkaPoll";
        public static final String COMMIT = "commit";
        public static final String KAFKA_COMMIT = "kafkaCommit";
    }

    interface IKafkaConsumerScope extends IScopedInstrumentationAttributes {
        @Override
        default String getScopeName() { return ScopeNames.KAFKA_CONSUMER_SCOPE; }
    }
    interface ITouchScopeContext extends IKafkaCommitScopeContext {
        @Override
        default String getActivityName() { return ActivityNames.TOUCH; }
    }
    interface IPollScopeContext extends IKafkaConsumerScope {
        @Override
        default String getActivityName() { return ActivityNames.KAFKA_POLL; }
    }

    interface ICommitScopeContext extends IKafkaConsumerScope {
        @Override
        default String getActivityName() { return ActivityNames.COMMIT; }
    }

    interface IKafkaCommitScopeContext extends IKafkaConsumerScope {
        @Override
        default String getActivityName() { return ActivityNames.KAFKA_COMMIT; }
    }
}
