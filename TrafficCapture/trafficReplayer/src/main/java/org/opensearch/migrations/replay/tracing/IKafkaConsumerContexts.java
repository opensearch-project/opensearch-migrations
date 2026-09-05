package org.opensearch.migrations.replay.tracing;

import java.time.Duration;

import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IKafkaConsumerContexts {

    enum LivenessScanVerdict {
        FOLLOW_UP_FOUND("follow_up_found"),
        CONFIRMED_ABSENT("confirmed_absent"),
        INCONCLUSIVE("inconclusive");

        private final String metricLabel;

        LivenessScanVerdict(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

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
        public static final String LIVENESS_SCAN_COUNT = "livenessScanCount";
        public static final String LIVENESS_SCAN_DISTANCE = "livenessScanDistance";
        public static final String LIVENESS_SCAN_LATENCY = "livenessScanLatency";
        public static final String LIVENESS_SCAN_BYTES_DISCARDED = "livenessScanBytesDiscarded";
        public static final String LIVENESS_SCAN_VERDICT_COUNT = "livenessScanVerdictCount";
    }

    interface IAsyncListeningContext extends IInstrumentationAttributes {}

    interface ILivenessScanContext extends IInstrumentationAttributes {
        void recordCycle(int recordsScanned, long bytesDiscarded, Duration latency);

        void recordVerdict(LivenessScanVerdict verdict);
    }

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
