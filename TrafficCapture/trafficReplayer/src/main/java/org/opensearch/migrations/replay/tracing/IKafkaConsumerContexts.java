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
        private MetricNames() {}

        public static final String PARTITIONS_ASSIGNED_EVENT_COUNT = "partitionsAssigned";
        public static final String PARTITIONS_REVOKED_EVENT_COUNT = "partitionsRevoked";
        public static final String ACTIVE_PARTITIONS_ASSIGNED_COUNT = "numPartitionsAssigned";
    }

    interface IAsyncListeningContext extends IInstrumentationAttributes {}

    interface IKafkaConsumerScope extends IScopedInstrumentationAttributes {}

    interface ITouchScopeContext extends IKafkaConsumerScope {
        String ACTIVITY_NAME = ActivityNames.TOUCH;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        IPollScopeContext createNewPollContext();
    }

    interface IPollScopeContext extends IKafkaConsumerScope {
        String ACTIVITY_NAME = ActivityNames.KAFKA_POLL;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    /**
     * Context for the KafkaConsumer's bookkeeping around and including the commit service call
     */
    interface ICommitScopeContext extends IKafkaConsumerScope {
        String ACTIVITY_NAME = ActivityNames.COMMIT;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        IKafkaCommitScopeContext createNewKafkaCommitContext();
    }

    /**
     * Context for ONLY the service call to Kafka to perform the commit.
     */
    interface IKafkaCommitScopeContext extends IKafkaConsumerScope {
        String ACTIVITY_NAME = ActivityNames.KAFKA_COMMIT;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }
}
