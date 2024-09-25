package org.opensearch.migrations.metadata.tracing;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IMetadataMigrationContexts {

    interface ITemplateContext extends IRfsContexts.ICheckedIdempotentPutRequestContext {
        String ACTIVITY_NAME = ActivityNames.MIGRATE_INDEX_TEMPLATE;
    }

    interface IClusterMetadataContext extends IScopedInstrumentationAttributes {
        String ACTIVITY_NAME = ActivityNames.MIGRATE_METADATA;

        ITemplateContext createMigrateLegacyTemplateContext();

        IRfsContexts.ICheckedIdempotentPutRequestContext createComponentTemplateContext();

        IRfsContexts.ICheckedIdempotentPutRequestContext createMigrateTemplateContext();
    }

    interface ICreateIndexContext extends IRfsContexts.ICheckedIdempotentPutRequestContext {
        String ACTIVITY_NAME = ActivityNames.CREATE_INDEX;
    }

    class ActivityNames {
        public static final String CREATE_SNAPSHOT = "createSnapshot";
        public static final String CREATE_INDEX = "createIndex";
        public static final String MIGRATE_METADATA = "migrateMetadata";
        public static final String MIGRATE_INDEX_TEMPLATE = "migrateIndexTemplate";

        private ActivityNames() {}
    }

    class MetricNames {
        private MetricNames() {}
    }

}
