package org.opensearch.migrations.metadata.tracing;

import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import com.rfs.tracing.IRfsContexts;

public abstract class IMetadataMigrationContexts {

    public interface ITemplateContext extends IRfsContexts.ICheckedIdempotentPutRequestContext {
        String ACTIVITY_NAME = ActivityNames.MIGRATE_INDEX_TEMPLATE;
    }

    public interface IClusterMetadataContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.MIGRATE_METADATA;

        ITemplateContext createMigrateLegacyTemplateContext();

        IRfsContexts.ICheckedIdempotentPutRequestContext createComponentTemplateContext();

        IRfsContexts.ICheckedIdempotentPutRequestContext createMigrateTemplateContext();
    }

    public interface ICreateIndexContext extends IRfsContexts.ICheckedIdempotentPutRequestContext {
        String ACTIVITY_NAME = ActivityNames.CREATE_INDEX;
    }

    public static class ActivityNames {
        public static final String CREATE_SNAPSHOT = "createSnapshot";
        public static final String CREATE_INDEX = "createIndex";
        public static final String MIGRATE_METADATA = "migrateMetadata";
        public static final String MIGRATE_INDEX_TEMPLATE = "migrateIndexTemplate";

        private ActivityNames() {}
    }

    public static class MetricNames {
        private MetricNames() {}
    }

}
