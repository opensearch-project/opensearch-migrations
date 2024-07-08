package org.opensearch.migrations.reindexer.tracing;

import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.IWorkCoordinationContexts;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public abstract class IDocumentMigrationContexts {

    public static class ActivityNames {
        private ActivityNames() {
        }
        public static final String DOCUMENT_REINDEX = "documentReindex";
        public static final String SHARD_SETUP = "shardSetup";
    }

    public static class MetricNames {
        private MetricNames() {}
    }

    public interface IShardSetupContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.SHARD_SETUP;

        IWorkCoordinationContexts.IAcquireSpecificWorkContext createWorkAcquisitionContext();
        IWorkCoordinationContexts.ICompleteWorkItemContext createWorkCompletionContext();
        IWorkCoordinationContexts.ICreateUnassignedWorkItemContext createShardWorkItemContext();
    }

    public interface IDocumentReindexContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.DOCUMENT_REINDEX;

        IRfsContexts.IRequestContext createBulkRequest();

        IRfsContexts.IRequestContext createRefreshContext();
    }


}