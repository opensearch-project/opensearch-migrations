package com.rfs.tracing;

import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public abstract class IRfsContexts {


    public static class ActivityNames {
        private ActivityNames() {}

        public static final String HTTP_REQUEST = "httpRequest";
        public static final String CHECK_THEN_PUT_REQUESTS = "checkThenPutRequest";
        public static final String CREATE_SNAPSHOT = "createSnapshot";
        public static final String CREATE_INDEX = "createIndex";
        public static final String DOCUMENT_REINDEX = "documentReindex";
        public static final String MIGRATE_METADATA = "migrateMetadata";
        public static final String MIGRATE_INDEX_TEMPLATE = "migrateIndexTemplate";
    }

    public static class MetricNames {
        private MetricNames() {}
        public static final String BYTES_READ = "bytesRead";
        public static final String BYTES_SENT = "bytesSent";
    }

    public interface IRequestContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.HTTP_REQUEST;

        void addBytesSent(int i);
        void addBytesRead(int i);
    }

    public interface ICheckedIdempotentPutRequestContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.CHECK_THEN_PUT_REQUESTS;
        IRequestContext createCheckRequestContext();
        IRequestContext createPutContext();
    }

    public interface ICreateSnapshotContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.CREATE_SNAPSHOT;

        IRequestContext createRegisterRequest();

        IRequestContext createSnapshotContext();

        IRequestContext createGetSnapshotContext();
    }

    public interface IIndexTemplateContext extends ICheckedIdempotentPutRequestContext {
        String ACTIVITY_NAME = ActivityNames.MIGRATE_INDEX_TEMPLATE;
    }

    public interface IClusterMetadataContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.MIGRATE_METADATA;

        IIndexTemplateContext createMigrateLegacyIndexTemplateContext();

        ICheckedIdempotentPutRequestContext createComponentTemplateContext();

        ICheckedIdempotentPutRequestContext createMigrateIndexTemplateContext();
    }

    public interface ICreateIndexContext extends ICheckedIdempotentPutRequestContext {
        String ACTIVITY_NAME = ActivityNames.CREATE_INDEX;
    }

    public interface IDocumentReindexContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.DOCUMENT_REINDEX;

        IRequestContext createBulkRequest();

        IRequestContext createRefreshContext();
    }

    public interface IWorkingStateContext extends IInstrumentationAttributes {

        IRequestContext createGetSnapshotEntryContext();
        IRequestContext createCreateSnapshotEntryDocumentContext();
        IRequestContext createUpdateSnapshotEntryContext();

        IRequestContext createCreateMetadataEntryDocumentContext();

        IRequestContext createGetMetadataEntryDocument();

        IRequestContext createInitialMetadataMigrationStatusDocumentContext();

        IRequestContext createUpdateMetadataMigrationStatusDocumentContext();
    }
}
