package org.opensearch.migrations.bulkload.tracing;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;
import org.opensearch.migrations.tracing.BaseSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

public class WorkCoordinationContexts extends IWorkCoordinationContexts {
    private WorkCoordinationContexts() {}

    @AllArgsConstructor
    public static class RetryLabels {
        CommonScopedMetricInstruments.ScopeLabels scopeLabels;
        public final String retry;
        public final String failure;
    }

    private static RetryLabels autoLabels(String activityName) {
        return new RetryLabels(
            CommonScopedMetricInstruments.fromActivityName(activityName),
            activityName + "Retries",
            activityName + "Failures"
        );
    }

    public static class RetryMetricInstruments extends CommonScopedMetricInstruments {
        public final LongCounter retryCounter;
        public final LongCounter failureCounter;

        private RetryMetricInstruments(Meter meter, RetryLabels retryLabels) {
            super(meter, retryLabels.scopeLabels);
            retryCounter = meter.counterBuilder(retryLabels.retry).build();
            failureCounter = meter.counterBuilder(retryLabels.failure).build();
        }
    }

    public interface RetryableActivityContextMetricMixin<T extends RetryMetricInstruments>
        extends
            IRetryableActivityContext {
        T getRetryMetrics();

        default T getMetrics() {
            return getRetryMetrics();
        }

        default void recordRetry() {
            meterIncrementEvent(getRetryMetrics().retryCounter);
        }

        default void recordFailure() {
            meterIncrementEvent(getRetryMetrics().failureCounter);
        }
    }

    @Getter
    public static class InitializeCoordinatorStateContext extends BaseSpanContext<RootWorkCoordinationContext>
        implements
            IInitializeCoordinatorStateContext,
            RetryableActivityContextMetricMixin<InitializeCoordinatorStateContext.MetricInstruments> {
        final IScopedInstrumentationAttributes enclosingScope;

        InitializeCoordinatorStateContext(RootWorkCoordinationContext rootScope) {
            this(rootScope, null);
        }

        InitializeCoordinatorStateContext(
            RootWorkCoordinationContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope);
            this.enclosingScope = enclosingScope;
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        public static class MetricInstruments extends RetryMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, autoLabels(activityName));
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getRetryMetrics() {
            return getRootInstrumentationScope().initializeMetrics;
        }
    }

    @Getter
    public static class CreateUnassignedWorkItemContext extends BaseSpanContext<RootWorkCoordinationContext>
        implements
            ICreateUnassignedWorkItemContext,
            RetryableActivityContextMetricMixin<CreateUnassignedWorkItemContext.MetricInstruments> {
        final IScopedInstrumentationAttributes enclosingScope;

        CreateUnassignedWorkItemContext(
            RootWorkCoordinationContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope);
            this.enclosingScope = enclosingScope;
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        public static class MetricInstruments extends RetryMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, autoLabels(activityName));
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getRetryMetrics() {
            return getRootInstrumentationScope().createUnassignedWorkMetrics;
        }
    }

    @Getter
    public static class PendingItems extends BaseSpanContext<RootWorkCoordinationContext>
        implements
            IPendingWorkItemsContext {
        final IScopedInstrumentationAttributes enclosingScope;

        PendingItems(RootWorkCoordinationContext rootScope) {
            this(rootScope, null);
        }

        PendingItems(RootWorkCoordinationContext rootScope, IScopedInstrumentationAttributes enclosingScope) {
            super(rootScope);
            this.enclosingScope = enclosingScope;
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        @Override
        public IRefreshContext getRefreshContext() {
            return new Refresh(rootInstrumentationScope, this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().pendingItemsMetrics;
        }
    }

    @Getter
    public static class Refresh extends BaseSpanContext<RootWorkCoordinationContext>
        implements
            IRefreshContext,
            RetryableActivityContextMetricMixin<Refresh.MetricInstruments> {
        final IScopedInstrumentationAttributes enclosingScope;

        Refresh(RootWorkCoordinationContext rootScope) {
            this(rootScope, null);
        }

        Refresh(RootWorkCoordinationContext rootScope, IScopedInstrumentationAttributes enclosingScope) {
            super(rootScope);
            this.enclosingScope = enclosingScope;
            initializeSpan(rootScope);
        }

        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        public static class MetricInstruments extends RetryMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, autoLabels(activityName));
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getRetryMetrics() {
            return getRootInstrumentationScope().refreshMetrics;
        }
    }

    @Getter
    public static class AcquireSpecificWorkContext extends BaseSpanContext<RootWorkCoordinationContext>
        implements
            IAcquireSpecificWorkContext,
            RetryableActivityContextMetricMixin<AcquireSpecificWorkContext.MetricInstruments> {
        final IScopedInstrumentationAttributes enclosingScope;

        AcquireSpecificWorkContext(
            RootWorkCoordinationContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope);
            this.enclosingScope = enclosingScope;
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        public static class MetricInstruments extends RetryMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, autoLabels(activityName));
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getRetryMetrics() {
            return getRootInstrumentationScope().acquireSpecificWorkMetrics;
        }
    }

    @Getter
    public static class AcquireNextWorkItemContext extends BaseSpanContext<RootWorkCoordinationContext>
        implements
            IAcquireNextWorkItemContext,
            RetryableActivityContextMetricMixin<AcquireNextWorkItemContext.MetricInstruments> {
        final IScopedInstrumentationAttributes enclosingScope;

        AcquireNextWorkItemContext(
            RootWorkCoordinationContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope);
            this.enclosingScope = enclosingScope;
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        @Override
        public IRefreshContext getRefreshContext() {
            return new Refresh(this.rootInstrumentationScope, this);
        }

        public static class MetricInstruments extends RetryMetricInstruments {
            public final LongCounter assignedCounter;
            public final LongCounter nothingAvailableCounter;
            public final LongCounter recoverableClockError;
            public final LongCounter driftError;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, autoLabels(activityName));
                assignedCounter = meter.counterBuilder(MetricNames.NEXT_WORK_ASSIGNED).build();
                nothingAvailableCounter = meter.counterBuilder(MetricNames.NO_NEXT_WORK_AVAILABLE).build();
                recoverableClockError = meter.counterBuilder(MetricNames.RECOVERABLE_CLOCK_ERROR).build();
                driftError = meter.counterBuilder(MetricNames.DRIFT_ERROR).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getRetryMetrics() {
            return getRootInstrumentationScope().acquireNextWorkMetrics;
        }

        @Override
        public void recordAssigned() {
            meterIncrementEvent(getRetryMetrics().assignedCounter);
        }

        @Override
        public void recordNothingAvailable() {
            meterIncrementEvent(getRetryMetrics().nothingAvailableCounter);
        }

        @Override
        public void recordRecoverableClockError() {
            meterIncrementEvent(getRetryMetrics().recoverableClockError);
        }

        @Override
        public void recordFailure(OpenSearchWorkCoordinator.PotentialClockDriftDetectedException e) {
            meterIncrementEvent(getRetryMetrics().driftError);
        }

    }

    @Getter
    public static class CompleteWorkItemContext extends BaseSpanContext<RootWorkCoordinationContext>
        implements
            ICompleteWorkItemContext,
            RetryableActivityContextMetricMixin<CompleteWorkItemContext.MetricInstruments> {
        final IScopedInstrumentationAttributes enclosingScope;

        CompleteWorkItemContext(
            RootWorkCoordinationContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope);
            this.enclosingScope = enclosingScope;
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        @Override
        public IRefreshContext getRefreshContext() {
            return new Refresh(this.rootInstrumentationScope, this);
        }

        public static class MetricInstruments extends RetryMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, autoLabels(activityName));
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getRetryMetrics() {
            return getRootInstrumentationScope().completeWorkMetrics;
        }
    }
}
