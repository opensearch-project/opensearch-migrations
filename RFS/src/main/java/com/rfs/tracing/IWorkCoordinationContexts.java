package com.rfs.tracing;

import com.rfs.cms.OpenSearchWorkCoordinator;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public abstract class IWorkCoordinationContexts {

    public static class ActivityNames {
        public static final String COORDINATION_INITIALIZATION = "workCoordination";
        public static final String CREATE_UNASSIGNED_WORK_ITEM = "createUnassignedWork";
        public static final String PENDING_WORK_CHECK = "pendingWorkCheck";
        public static final String SYNC_REFRESH_CLUSTER = "refreshCluster";
        public static final String ACQUIRE_SPECIFIC_WORK = "acquireWork";
        public static final String COMPLETE_WORK = "completeWork";
        public static final String ACQUIRE_NEXT_WORK = "acquireNextWork";

        private ActivityNames() {
        }
    }

    public static class MetricNames {
        public static final String COORDINATION_INITIALIZATION_RETRIES = "workCoordinationInitRetries";
        public static final String COORDINATION_FAILURE = "woorkCoordinationFailure";
        public static final String NEXT_WORK_ASSIGNED = "nextWorkAssigned";
        public static final String NO_NEXT_WORK_AVAILABLE = "noNextWorkAvailable";
        public static final String RECOVERABLE_CLOCK_ERROR = "recoverableClockError";
        public static final String DRIFT_ERROR = "fatalDriftError";

        private MetricNames() {
        }
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

    public interface IAcquireSpecificWorkContext extends IRetryableActivityContext {
        String ACTIVITY_NAME = ActivityNames.ACQUIRE_SPECIFIC_WORK;
    }

    public interface IAcquireNextWorkItemContext extends IRetryableActivityContext {
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

    public interface IScopedWorkContext<OPENING_CONTEXT> extends IScopedInstrumentationAttributes {
        OPENING_CONTEXT createOpeningContext();
        ICompleteWorkItemContext createCloseContet();
    }
}