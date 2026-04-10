package org.opensearch.migrations.bulkload.tracing;

import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IWorkCoordinationContexts {

    class ActivityNames {
        public static final String COORDINATION_INITIALIZATION = "workCoordinationInitialization";
        public static final String CREATE_UNASSIGNED_WORK_ITEM = "createUnassignedWork";
        public static final String PENDING_WORK_CHECK = "pendingWorkCheck";
        public static final String SYNC_REFRESH_CLUSTER = "refreshCluster";
        public static final String ACQUIRE_SPECIFIC_WORK = "acquireSpecificWorkItem";
        public static final String COMPLETE_WORK = "completeWork";
        public static final String ACQUIRE_NEXT_WORK = "acquireNextWorkItem";
        public static final String CREATE_SUCCESSOR_WORK_ITEMS = "createSuccessorWorkItems";

        private ActivityNames() {}
    }

    class MetricNames {
        public static final String NEXT_WORK_ASSIGNED = "nextWorkAssignedCount";
        public static final String NO_NEXT_WORK_AVAILABLE = "noNextWorkAvailableCount";
        public static final String RECOVERABLE_CLOCK_ERROR = "recoverableClockErrorCount";
        public static final String DRIFT_ERROR = "fatalDriftErrorCount";

        private MetricNames() {}
    }

    interface IRetryableActivityContext extends IScopedInstrumentationAttributes {
        void recordRetry();

        void recordFailure();
    }

    interface IInitializeCoordinatorStateContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.COORDINATION_INITIALIZATION;
    }

    interface ICreateUnassignedWorkItemContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.CREATE_UNASSIGNED_WORK_ITEM;
    }

    interface IPendingWorkItemsContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.PENDING_WORK_CHECK;

        IRefreshContext getRefreshContext();
    }

    interface IRefreshContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.SYNC_REFRESH_CLUSTER;
    }

    interface IBaseAcquireWorkContext extends IRetryableActivityContext {}

    interface IAcquireSpecificWorkContext extends IBaseAcquireWorkContext {
        String ACTIVITY_NAME = ActivityNames.ACQUIRE_SPECIFIC_WORK;

        ICreateSuccessorWorkItemsContext getCreateSuccessorWorkItemsContext();
    }

    interface IAcquireNextWorkItemContext extends IBaseAcquireWorkContext {
        String ACTIVITY_NAME = ActivityNames.ACQUIRE_NEXT_WORK;

        IRefreshContext getRefreshContext();

        void recordAssigned();

        void recordNothingAvailable();

        void recordRecoverableClockError();

        void recordFailure(OpenSearchWorkCoordinator.PotentialClockDriftDetectedException e);

        ICreateSuccessorWorkItemsContext getCreateSuccessorWorkItemsContext();

    }

    interface ICompleteWorkItemContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.COMPLETE_WORK;

        IRefreshContext getRefreshContext();
    }

    interface ICreateSuccessorWorkItemsContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.CREATE_SUCCESSOR_WORK_ITEMS;
        IRefreshContext getRefreshContext();
        ICompleteWorkItemContext getCompleteWorkItemContext();
        ICreateUnassignedWorkItemContext getCreateUnassignedWorkItemContext();
    }

    interface IScopedWorkContext<C extends IBaseAcquireWorkContext> extends IScopedInstrumentationAttributes {
        C createOpeningContext();

        ICompleteWorkItemContext createCloseContext();
    }
}
