package org.opensearch.migrations.reindexer.tracing;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public abstract class IDocumentMigrationContexts {

    public static class ActivityNames {
        private ActivityNames() {}

        public static final String DOCUMENT_REINDEX = "documentReindex";
        public static final String SHARD_SETUP_ATTEMPT = "shardSetupAttempt";
        public static final String ADD_SHARD_WORK_ITEM = "addShardWorkItem";
    }

    public static class MetricNames {
        private MetricNames() {}
    }

    public interface IShardSetupAttemptContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.SHARD_SETUP_ATTEMPT;

        IWorkCoordinationContexts.IAcquireSpecificWorkContext createWorkAcquisitionContext();

        IWorkCoordinationContexts.ICompleteWorkItemContext createWorkCompletionContext();

        IAddShardWorkItemContext createShardWorkItemContext();
    }

    public interface IAddShardWorkItemContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.ADD_SHARD_WORK_ITEM;

        IWorkCoordinationContexts.ICreateUnassignedWorkItemContext createUnassignedWorkItemContext();
    }

    public interface IDocumentReindexContext
        extends
            IWorkCoordinationContexts.IScopedWorkContext<IWorkCoordinationContexts.IAcquireNextWorkItemContext> {
        String ACTIVITY_NAME = ActivityNames.DOCUMENT_REINDEX;

        IRfsContexts.IRequestContext createBulkRequest();

        IRfsContexts.IRequestContext createRefreshContext();
    }
}
