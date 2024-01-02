package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.IInstrumentationAttributes;
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

    class MetricNames {
        public static final String PARTITIONS_ASSIGNED_EVENT_COUNT = "partitionsAssigned";
        public static final String PARTITIONS_REVOKED_EVENT_COUNT = "partitionsRevoked";
        public static final String ACTIVE_PARTITIONS_ASSIGNED_COUNT = "numPartitionsAssigned";
    }

    interface IAsyncListeningContext extends IInstrumentationAttributes {
        default String getScopeName() { return ScopeNames.KAFKA_CONSUMER_SCOPE; }
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

    /**
     * Context for the KafkaConsumer's bookkeeping around and including the commit service call
     */
    interface ICommitScopeContext extends IKafkaConsumerScope {
        @Override
        default String getActivityName() { return ActivityNames.COMMIT; }
    }

    /**
     * Context for ONLY the service call to Kafka to perform the commit.
     */
    interface IKafkaCommitScopeContext extends IKafkaConsumerScope {
        @Override
        default String getActivityName() { return ActivityNames.KAFKA_COMMIT; }
    }
}
