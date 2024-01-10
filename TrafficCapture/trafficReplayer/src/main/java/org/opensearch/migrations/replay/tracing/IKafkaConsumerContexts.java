package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.IInstrumentConstructor;
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
        private MetricNames() {}
        public static final String PARTITIONS_ASSIGNED_EVENT_COUNT = "partitionsAssigned";
        public static final String PARTITIONS_REVOKED_EVENT_COUNT = "partitionsRevoked";
        public static final String ACTIVE_PARTITIONS_ASSIGNED_COUNT = "numPartitionsAssigned";
    }

    interface IAsyncListeningContext<S extends IRootReplayerContext> extends IInstrumentationAttributes<S> {
        String SCOPE_NAME = ScopeNames.KAFKA_CONSUMER_SCOPE;
        @Override default String getScopeName() { return SCOPE_NAME; }
    }
    interface IKafkaConsumerScope<S extends IInstrumentConstructor<S>> extends IScopedInstrumentationAttributes<S> {
        String SCOPE_NAME = ScopeNames.KAFKA_CONSUMER_SCOPE;
        @Override default String getScopeName() { return SCOPE_NAME; }
    }
    interface ITouchScopeContext<S extends IInstrumentConstructor<S>> extends IKafkaConsumerScope<S> {
        String ACTIVITY_NAME = ActivityNames.TOUCH;
        @Override
        default String getActivityName() { return ACTIVITY_NAME; }
    }
    interface IPollScopeContext<S extends IInstrumentConstructor<S>> extends IKafkaConsumerScope<S> {
        String ACTIVITY_NAME = ActivityNames.KAFKA_POLL;
        @Override
        default String getActivityName() { return ACTIVITY_NAME; }
    }

    /**
     * Context for the KafkaConsumer's bookkeeping around and including the commit service call
     */
    interface ICommitScopeContext<S extends IInstrumentConstructor<S>> extends IKafkaConsumerScope<S> {
        String ACTIVITY_NAME = ActivityNames.COMMIT;
        @Override
        default String getActivityName() { return ACTIVITY_NAME; }
    }

    /**
     * Context for ONLY the service call to Kafka to perform the commit.
     */
    interface IKafkaCommitScopeContext<S extends IInstrumentConstructor<S>> extends IKafkaConsumerScope<S>{
        String ACTIVITY_NAME = ActivityNames.KAFKA_COMMIT;
        @Override
        default String getActivityName() { return ACTIVITY_NAME; }
    }
}
