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

// REBUILD-LIMBO-START(G3)
// LivenessScanContext: dead. Nothing measures a liveness scan.
// AsyncListeningContext, TouchScopeContext: blocked on RootReplayerContext. TouchScopeContext also belongs to
// the back-pressure model G5 replaces. To restore either, retarget its root as the live contexts below are.
/*
    public static class LivenessScanContext implements IKafkaConsumerContexts.ILivenessScanContext {
        public static final AttributeKey<String> VERDICT_ATTRIBUTE = AttributeKey.stringKey("verdict");

        @Getter
        @NonNull
        public final RootReplayerContext enclosingScope;
        @Getter
        @Setter
        Exception observedExceptionToIncludeInMetrics;

        public static class MetricInstruments extends CommonMetricInstruments {
            public final LongCounter scanCounter;
            public final LongHistogram distance;
            public final DoubleHistogram latency;
            public final LongCounter bytesDiscarded;
            public final LongCounter verdictCounter;
            public final LongCounter supersededTrafficDiscarded;

            private MetricInstruments(Meter meter) {
                super(meter, "livenessScan");
                scanCounter = meter.counterBuilder(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_COUNT)
                    .setUnit("scans")
                    .build();
                distance = meter.histogramBuilder(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_DISTANCE)
                    .ofLongs()
                    .setUnit("records")
                    .build();
                latency = meter.histogramBuilder(IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_LATENCY)
                    .setUnit("ms")
                    .build();
                bytesDiscarded = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_BYTES_DISCARDED
                ).setUnit("By").build();
                verdictCounter = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.LIVENESS_SCAN_VERDICT_COUNT
                ).setUnit("verdicts").build();
                supersededTrafficDiscarded = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.SUPERSEDED_TRAFFIC_RECORDS_DISCARDED
                ).setUnit("records").build();
            }
        }

        public LivenessScanContext(@NonNull RootReplayerContext enclosingScope) {
            this.enclosingScope = enclosingScope;
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return enclosingScope.livenessScanInstruments;
        }

        @Override
        public void recordCycle(int recordsScanned, long discardedBytes, Duration duration) {
            meterIncrementEvent(getMetrics().scanCounter);
            meterHistogram(getMetrics().distance, recordsScanned);
            meterHistogramMillis(getMetrics().latency, duration);
            meterIncrementEvent(getMetrics().bytesDiscarded, discardedBytes);
        }

        @Override
        public void recordVerdict(@NonNull IKafkaConsumerContexts.LivenessScanVerdict verdict) {
            meterIncrementEvent(
                getMetrics().verdictCounter,
                1,
                Attributes.builder().put(VERDICT_ATTRIBUTE, verdict.metricLabel())
            );
        }

        @Override
        public void recordSupersededTrafficDiscarded() {
            meterIncrementEvent(getMetrics().supersededTrafficDiscarded);
        }
    }

    public static class AsyncListeningContext implements IKafkaConsumerContexts.IAsyncListeningContext {
        @Getter
        @NonNull
        public final RootReplayerContext enclosingScope;
        @Getter
        @Setter
        Exception observedExceptionToIncludeInMetrics;

        public AsyncListeningContext(@NonNull RootReplayerContext enclosingScope) {
            this.enclosingScope = enclosingScope;
        }

        public static class MetricInstruments extends CommonMetricInstruments {
            public final LongCounter kafkaPartitionsRevokedCounter;
            public final LongCounter kafkaPartitionsAssignedCounter;
            public final LongUpDownCounter kafkaActivePartitionsCounter;

            private MetricInstruments(Meter meter) {
                super(meter, "asyncKafkaProcessing");
                kafkaPartitionsRevokedCounter = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.PARTITIONS_REVOKED_EVENT_COUNT
                ).build();
                kafkaPartitionsAssignedCounter = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.PARTITIONS_ASSIGNED_EVENT_COUNT
                ).build();
                kafkaActivePartitionsCounter = meter.upDownCounterBuilder(
                    IKafkaConsumerContexts.MetricNames.ACTIVE_PARTITIONS_ASSIGNED_COUNT
                ).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter);
        }

        @NonNull
        public MetricInstruments getMetrics() {
            return enclosingScope.asyncListeningInstruments;
        }

        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            meterIncrementEvent(getMetrics().kafkaPartitionsRevokedCounter);
            onPartitionsAssignedChanged(-1 * partitions.size());
        }

        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            meterIncrementEvent(getMetrics().kafkaPartitionsAssignedCounter);
            onPartitionsAssignedChanged(partitions.size());
        }

        private void onPartitionsAssignedChanged(int delta) {
            meterDeltaEvent(getMetrics().kafkaActivePartitionsCounter, delta);
        }
    }

    public static class TouchScopeContext extends DirectNestedSpanContext<
        RootReplayerContext,
        TrafficSourceContexts.BackPressureBlockContext,
        ITrafficSourceContexts.IBackPressureBlockContext> implements IKafkaConsumerContexts.ITouchScopeContext {
        @Override
        public IKafkaConsumerContexts.IPollScopeContext createNewPollContext() {
            return new KafkaConsumerContexts.PollScopeContext(getRootInstrumentationScope(), this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public TouchScopeContext(@NonNull TrafficSourceContexts.BackPressureBlockContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().touchInstruments;
        }
    }

*/
// REBUILD-LIMBO-END(G3)
    // REBUILD-LIMBO-NOTE(G3): root type stands in for RootReplayerContext, which already declares
    // pollInstruments, commitInstruments and kafkaCommitInstruments under these names.
    public static class PollScopeContext extends BaseNestedSpanContext<
        KafkaSourceRootContext,
        IScopedInstrumentationAttributes> implements IKafkaConsumerContexts.IPollScopeContext {
        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public final LongCounter pollsEntered;
            public final LongCounter pollsWokenByQueuedInput;
            public final LongCounter wakeupsIssued;
            public final LongCounter wakeupsCoalesced;
            public final LongCounter wakeupsDeferred;

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
            @NonNull KafkaSourceRootContext rootScope,
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

    // REBUILD-LIMBO-NOTE(G3): root type; RootReplayerContext also needs a rebalanceCallbackInstruments field.
    public static class RebalanceCallbackScopeContext extends BaseNestedSpanContext<
        KafkaSourceRootContext,
        IScopedInstrumentationAttributes> implements IKafkaConsumerContexts.IRebalanceCallbackScopeContext {

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public final LongCounter deferredWakeupsIssuedOnExit;
            public final LongCounter generationsRetired;
            public final LongCounter generationsRetiredWithoutCommit;
            /**
             * Retired while a commit it had submitted was still unresolved, so whether that commit landed
             * is unknown. Kept apart from the zero-commit counter deliberately: reporting an unknown
             * outcome as "committed nothing" would be a false alarm on the one signal §9.5 asks operators
             * to watch.
             */
            public final LongCounter generationsRetiredWithUnknownCommit;
            public final LongCounter retiredGenerationRecordsCommitted;
            public final LongCounter retiredGenerationRecordsRead;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                deferredWakeupsIssuedOnExit = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.DEFERRED_WAKEUPS_ISSUED_ON_CALLBACK_EXIT).build();
                generationsRetired = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.GENERATIONS_RETIRED).build();
                generationsRetiredWithoutCommit = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.GENERATIONS_RETIRED_WITHOUT_COMMIT).build();
                generationsRetiredWithUnknownCommit = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.GENERATIONS_RETIRED_WITH_UNKNOWN_COMMIT).build();
                retiredGenerationRecordsCommitted = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.RETIRED_GENERATION_RECORDS_COMMITTED).build();
                retiredGenerationRecordsRead = meter.counterBuilder(
                    IKafkaConsumerContexts.MetricNames.RETIRED_GENERATION_RECORDS_READ).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().rebalanceCallbackInstruments;
        }

        public RebalanceCallbackScopeContext(@NonNull KafkaSourceRootContext rootScope) {
            super(rootScope, null);
            initializeSpan();
        }

        @Override
        public void onIssuedDeferredWakeupOnExit() {
            meterIncrementEvent(getMetrics().deferredWakeupsIssuedOnExit);
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
        public void onGenerationRetired(
            String generationLabel,
            long recordsCommitted,
            long recordsRead,
            boolean commitOutcomeUnknown
        ) {
            setAttribute(RETIRED_GENERATION_ATTRIBUTE, generationLabel);
            setTraceAttribute(RETIRED_RECORDS_COMMITTED_ATTRIBUTE, recordsCommitted);
            setTraceAttribute(RETIRED_RECORDS_READ_ATTRIBUTE, recordsRead);
            meterIncrementEvent(getMetrics().generationsRetired);
            meterIncrementEvent(getMetrics().retiredGenerationRecordsCommitted, recordsCommitted);
            meterIncrementEvent(getMetrics().retiredGenerationRecordsRead, recordsRead);
            if (commitOutcomeUnknown) {
                // Not a zero-commit retirement even when nothing was credited: the submission may well have
                // landed, and counting it as a stall would fire the alarm §9.5 exists for on a case that is
                // merely unresolved.
                meterIncrementEvent(getMetrics().generationsRetiredWithUnknownCommit);
            } else if (recordsCommitted == 0) {
                // Counted rather than derived, so the condition procCommit §9.5 names is one series to alarm
                // on. A run of these on one partition is the head-of-line stall.
                meterIncrementEvent(getMetrics().generationsRetiredWithoutCommit);
            }
        }
    }

    // REBUILD-LIMBO-NOTE(G3): root type stands in for RootReplayerContext.
    public static class CommitScopeContext extends BaseNestedSpanContext<
        KafkaSourceRootContext,
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
            @NonNull KafkaSourceRootContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope, enclosingScope);
            initializeSpan();
        }
    }

    // REBUILD-LIMBO-NOTE(G3): root type stands in for RootReplayerContext.
    public static class KafkaCommitScopeContext extends DirectNestedSpanContext<
        KafkaSourceRootContext,
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
