package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public class KafkaConsumerContexts {

    private KafkaConsumerContexts() {}

    public static class PollScopeContext extends BaseNestedSpanContext<
        RootReplayerContext,
        IScopedInstrumentationAttributes> implements IKafkaConsumerContexts.IPollScopeContext {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public final LongCounter pollsEntered;
            public final LongCounter pollsWokenByQueuedInput;
            public final LongCounter wakeupsIssued;
            public final LongCounter wakeupsCoalesced;
            public final LongCounter wakeupsDeferred;
            public final LongCounter wakeupsAbsorbedByProtectedOperation;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                // Counted as the context opens, so another thread can see that a poll is in progress; a span
                // is only exported once it ends.
                pollsEntered = meter.counterBuilder(IKafkaConsumerContexts.MetricNames.POLLS_ENTERED).build();
                pollsWokenByQueuedInput = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.POLLS_WOKEN_BY_QUEUED_INPUT).build();
                // The wakeup counters live on the poll scope because every wakeup exists to affect a poll,
                // whichever phase the submission arrived in.
                wakeupsIssued = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.WAKEUPS_ISSUED).build();
                wakeupsCoalesced = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.WAKEUPS_COALESCED).build();
                wakeupsDeferred = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.WAKEUPS_DEFERRED).build();
                wakeupsAbsorbedByProtectedOperation = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.WAKEUPS_ABSORBED_BY_PROTECTED_OPERATION).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().pollInstruments;
        }

        public PollScopeContext(
            @NonNull RootReplayerContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope, enclosingScope);
            initializeSpan();
            meterIncrementEvent(getMetrics().pollsEntered);
        }

        @Override
        public void onWokenByQueuedInput() {
            meterIncrementEvent(getMetrics().pollsWokenByQueuedInput);
        }
    }

    public static class RebalanceCallbackScopeContext extends BaseNestedSpanContext<
        RootReplayerContext,
        IScopedInstrumentationAttributes> implements IKafkaConsumerContexts.IRebalanceCallbackScopeContext {

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public final LongCounter deferredWakeupsIssuedOnExit;
            public final LongCounter generationsRetired;
            public final LongCounter generationsRetiredWithoutCommit;
            public final LongCounter retiredGenerationRecordsCommitted;
            public final LongCounter retiredGenerationRecordsRead;
            public final LongCounter revocationsCleanedBeforeDeadline;
            public final LongCounter revocationsReachingDeadline;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                deferredWakeupsIssuedOnExit = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.DEFERRED_WAKEUPS_ISSUED_ON_CALLBACK_EXIT).build();
                generationsRetired = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.GENERATIONS_RETIRED).build();
                generationsRetiredWithoutCommit = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.GENERATIONS_RETIRED_WITHOUT_COMMIT).build();
                retiredGenerationRecordsCommitted = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.RETIRED_GENERATION_RECORDS_COMMITTED).build();
                retiredGenerationRecordsRead = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.RETIRED_GENERATION_RECORDS_READ).build();
                revocationsCleanedBeforeDeadline = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.REVOCATIONS_CLEANED_BEFORE_DEADLINE).build();
                revocationsReachingDeadline = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.REVOCATIONS_REACHING_DEADLINE).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().rebalanceCallbackInstruments;
        }

        public RebalanceCallbackScopeContext(@NonNull RootReplayerContext rootScope) {
            super(rootScope, null);
            initializeSpan();
        }

        @Override
        public void onIssuedDeferredWakeupOnExit() {
            meterIncrementEvent(getMetrics().deferredWakeupsIssuedOnExit);
        }

        @Override
        public void onGraceWaitEnded(boolean everyGenerationReportedCleanup) {
            meterIncrementEvent(everyGenerationReportedCleanup
                ? getMetrics().revocationsCleanedBeforeDeadline
                : getMetrics().revocationsReachingDeadline);
        }

        /**
         * The generation goes on the span, the totals go on counters. A generation attribute on a metric
         * would be one series per generation per partition forever, while the question operators ask —
         * how often a generation retires having committed nothing — needs no per-generation series.
         */
        public static final AttributeKey<String> RETIRED_GENERATION_ATTRIBUTE =
            AttributeKey.stringKey("retiredGeneration");
        public static final AttributeKey<Long> RETIRED_RECORDS_COMMITTED_ATTRIBUTE =
            AttributeKey.longKey("retiredGenerationRecordsCommitted");
        public static final AttributeKey<Long> RETIRED_RECORDS_READ_ATTRIBUTE =
            AttributeKey.longKey("retiredGenerationRecordsRead");

        @Override
        public void onGenerationRetired(String generationLabel, long recordsCommitted, long recordsRead) {
            setAttribute(RETIRED_GENERATION_ATTRIBUTE, generationLabel);
            setTraceAttribute(RETIRED_RECORDS_COMMITTED_ATTRIBUTE, recordsCommitted);
            setTraceAttribute(RETIRED_RECORDS_READ_ATTRIBUTE, recordsRead);
            recordGenerationRetiredMetrics(getMetrics(), recordsCommitted, recordsRead);
        }

        public static void recordGenerationRetiredMetrics(
            MetricInstruments instruments,
            long recordsCommitted,
            long recordsRead
        ) {
            instruments.generationsRetired.add(1);
            instruments.retiredGenerationRecordsCommitted.add(recordsCommitted);
            instruments.retiredGenerationRecordsRead.add(recordsRead);
            if (recordsCommitted == 0) {
                // Counted rather than derived, so the condition procCommit §9.5 names is one series to alarm
                // on. A run of these on one partition is the head-of-line stall.
                instruments.generationsRetiredWithoutCommit.add(1);
            }
        }
    }

    public static class CommitScopeContext extends BaseNestedSpanContext<
        RootReplayerContext,
        IScopedInstrumentationAttributes> implements IKafkaConsumerContexts.ICommitScopeContext {

        @Override
        public IKafkaConsumerContexts.IKafkaCommitScopeContext createNewKafkaCommitContext() {
            return new KafkaConsumerContexts.KafkaCommitScopeContext(this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            /**
             * Commit callbacks that arrived for a generation no longer held. Diagnostic only per
             * {@code kafkaLLD §5.7}, but worth a series: a sustained rate means commits keep resolving after
             * their generation was revoked, which is the shape of a rebalance storm.
             */
            public final LongCounter lateCommitCallbacks;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                lateCommitCallbacks = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.LATE_COMMIT_CALLBACKS).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().commitInstruments;
        }

        public CommitScopeContext(
            @NonNull RootReplayerContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

    public static class KafkaCommitScopeContext extends DirectNestedSpanContext<
        RootReplayerContext,
        KafkaConsumerContexts.CommitScopeContext,
        IKafkaConsumerContexts.ICommitScopeContext> implements IKafkaConsumerContexts.IKafkaCommitScopeContext {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().kafkaCommitInstruments;
        }

        public KafkaCommitScopeContext(@NonNull KafkaConsumerContexts.CommitScopeContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

    }
}
