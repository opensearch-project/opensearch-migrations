package org.opensearch.migrations.reindexer.tracing;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IDocumentMigrationContexts {

    class ActivityNames {
        private ActivityNames() {}

        public static final String DOCUMENT_REINDEX = "documentReindex";
        public static final String SHARD_SETUP_ATTEMPT = "shardSetupAttempt";
        public static final String ADD_SHARD_WORK_ITEM = "addShardWorkItem";
    }

    class MetricNames {
        private MetricNames() {}

        public static final String SHARD_DURATION = "pipelineShardDuration";
        public static final String DOCS_MIGRATED = "pipelineDocsMigrated";
        public static final String BYTES_MIGRATED = "pipelineBytesMigrated";
        public static final String PIPELINE_ERRORS = "pipelineErrors";
    }

    interface IShardSetupAttemptContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.SHARD_SETUP_ATTEMPT;

        IWorkCoordinationContexts.IAcquireSpecificWorkContext createWorkAcquisitionContext();

        IWorkCoordinationContexts.ICompleteWorkItemContext createWorkCompletionContext();

        IAddShardWorkItemContext createShardWorkItemContext();

        IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext createSuccessorWorkItemsContext();
    }

    interface IAddShardWorkItemContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.ADD_SHARD_WORK_ITEM;

        IWorkCoordinationContexts.ICreateUnassignedWorkItemContext createUnassignedWorkItemContext();
    }

    interface IDocumentReindexContext
        extends
            IWorkCoordinationContexts.IScopedWorkContext<IWorkCoordinationContexts.IAcquireNextWorkItemContext> {
        String ACTIVITY_NAME = ActivityNames.DOCUMENT_REINDEX;

        IRfsContexts.IRequestContext createBulkRequest();

        IRfsContexts.IRequestContext createRefreshContext();

        IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext createSuccessorWorkItemsContext();

        void recordShardDuration(long durationMs);

        void recordDocsMigrated(long count);

        void recordBytesMigrated(long count);

        void recordPipelineError();

    }
}
