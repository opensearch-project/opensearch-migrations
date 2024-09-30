package org.opensearch.migrations.metadata;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

public interface GlobalMetadataCreator {
    public GlobalMetadataCreatorResults create(
        GlobalMetadata metadata,
        MigrationMode mode,
        IClusterMetadataContext context);
}
