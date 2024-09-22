package org.opensearch.migrations.bulkload.tracing;

import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public abstract class IWorkCoordinationContexts {

    public static class ActivityNames {
        public static final String COORDINATION_INITIALIZATION = "workCoordinationInitialization";
        public static final String CREATE_UNASSIGNED_WORK_ITEM = "createUnassignedWork";
        public static final String PENDING_WORK_CHECK = "pendingWorkCheck";
        public static final String SYNC_REFRESH_CLUSTER = "refreshCluster";
        public static final String ACQUIRE_SPECIFIC_WORK = "acquireSpecificWorkItem";
        public static final String COMPLETE_WORK = "completeWork";
        public static final String ACQUIRE_NEXT_WORK = "acquireNextWorkItem";

        private ActivityNames() {}
    }

    public static class MetricNames {
        public static final String NEXT_WORK_ASSIGNED = "nextWorkAssignedCount";
        public static final String NO_NEXT_WORK_AVAILABLE = "noNextWorkAvailableCount";
        public static final String RECOVERABLE_CLOCK_ERROR = "recoverableClockErrorCount";
        public static final String DRIFT_ERROR = "fatalDriftErrorCount";

        private MetricNames() {}
    }

    public interface IRetryableActivityContext extends IScopedInstrumentationAttributes {
        void recordRetry();

        void recordFailure();
    }

    public interface IInitializeCoordinatorStateContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.COORDINATION_INITIALIZATION;
    }

    public interface ICreateUnassignedWorkItemContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.CREATE_UNASSIGNED_WORK_ITEM;
    }

    public interface IPendingWorkItemsContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.PENDING_WORK_CHECK;

        IRefreshContext getRefreshContext();
    }

    public interface IRefreshContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.SYNC_REFRESH_CLUSTER;
    }

    public interface IBaseAcquireWorkContext extends IRetryableActivityContext {}

    public interface IAcquireSpecificWorkContext extends IBaseAcquireWorkContext {
        String ACTIVITY_NAME = ActivityNames.ACQUIRE_SPECIFIC_WORK;
    }

    public interface IAcquireNextWorkItemContext extends IBaseAcquireWorkContext {
        String ACTIVITY_NAME = ActivityNames.ACQUIRE_NEXT_WORK;

        IRefreshContext getRefreshContext();

        void recordAssigned();

        void recordNothingAvailable();

        void recordRecoverableClockError();

        void recordFailure(OpenSearchWorkCoordinator.PotentialClockDriftDetectedException e);
    }

    public interface ICompleteWorkItemContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.COMPLETE_WORK;

        IRefreshContext getRefreshContext();
    }

    public interface IScopedWorkContext<C extends IBaseAcquireWorkContext> extends IScopedInstrumentationAttributes {
        C createOpeningContext();

        ICompleteWorkItemContext createCloseContet();
    }
}
