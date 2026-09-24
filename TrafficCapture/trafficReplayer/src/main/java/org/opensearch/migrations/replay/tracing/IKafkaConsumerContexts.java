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
        public static final String REBALANCE_CALLBACK = "rebalanceCallback";
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
        public static final String SUPERSEDED_TRAFFIC_RECORDS_DISCARDED =
            "supersededTrafficRecordsDiscarded";

        public static final String POLLS_ENTERED = "kafkaSourcePollsEntered";
        public static final String POLLS_WOKEN_BY_QUEUED_INPUT = "kafkaSourcePollsWokenByQueuedInput";
        public static final String WAKEUPS_ISSUED = "kafkaSourceWakeupsIssued";
        public static final String WAKEUPS_COALESCED = "kafkaSourceWakeupsCoalesced";
        public static final String WAKEUPS_DEFERRED = "kafkaSourceWakeupsDeferred";
        public static final String LATE_COMMIT_CALLBACKS = "kafkaSourceLateCommitCallbacks";
        public static final String GENERATIONS_RETIRED = "kafkaSourceGenerationsRetired";
        public static final String GENERATIONS_RETIRED_WITHOUT_COMMIT =
            "kafkaSourceGenerationsRetiredWithoutCommit";
        public static final String RETIRED_GENERATION_RECORDS_COMMITTED =
            "kafkaSourceRetiredGenerationRecordsCommitted";
        public static final String RETIRED_GENERATION_RECORDS_READ = "kafkaSourceRetiredGenerationRecordsRead";
        public static final String DEFERRED_WAKEUPS_ISSUED_ON_CALLBACK_EXIT =
            "kafkaSourceDeferredWakeupsIssuedOnCallbackExit";
    }

    interface IAsyncListeningContext extends IInstrumentationAttributes {}

    interface ILivenessScanContext extends IInstrumentationAttributes {
        void recordCycle(int recordsScanned, long bytesDiscarded, Duration latency);

        void recordVerdict(LivenessScanVerdict verdict);

        void recordSupersededTrafficDiscarded();
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

        /** Records that this poll ended because a queued input woke it rather than by reaching its timeout. */
        void onWokenByQueuedInput();
    }

    /**
     * A rebalance callback as a scope of its own. Nothing measured this before, and it is the window during
     * which a wakeup must not be delivered, so its duration is what shows a deferral actually spanned it.
     */
    interface IRebalanceCallbackScopeContext extends IKafkaConsumerScope {
        String ACTIVITY_NAME = ActivityNames.REBALANCE_CALLBACK;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        /** Records that a wakeup deferred during the callback was issued as it returned. */
        void onIssuedDeferredWakeupOnExit();

        /**
         * Records a partition generation being retired, per {@code kafkaLLD §15.4}.
         *
         * <p>The generation identity goes on the span and the totals go on counters. Putting the generation on
         * a metric attribute instead would make its cardinality unbounded — one series per generation per
         * partition, forever — while the question operators actually ask is "how often does a generation
         * retire having committed nothing", which needs no per-generation series to answer.
         *
         * @param generationLabel the retiring generation, for the span
         * @param recordsCommitted how far the committed position advanced over the generation's whole life,
         *                         not only during the grace interval. Zero is the signal {@code procCommit
         *                         §9.5} describes
         * @param recordsRead every record the generation delivered to replay intake, which against
         *                    {@code recordsCommitted} gives the re-work this retirement cost
         */
        void onGenerationRetired(String generationLabel, long recordsCommitted, long recordsRead);
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
