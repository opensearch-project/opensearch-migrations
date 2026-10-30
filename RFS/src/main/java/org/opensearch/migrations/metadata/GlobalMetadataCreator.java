package org.opensearch.migrations.metadata;

import java.util.List;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

public interface GlobalMetadataCreator {
    public GlobalMetadataCreatorResults create(
        GlobalMetadata metadata,
        MigrationMode mode,
        IClusterMetadataContext context);

    /**
     * Creates the global metadata in each OpenSearch Serverless collection behind a per-account
     * endpoint. An empty list means the target is not collection-routed.
     */
    default GlobalMetadataCreatorResults create(
        GlobalMetadata metadata,
        MigrationMode mode,
        IClusterMetadataContext context,
        List<String> serverlessCollections
    ) {
        if (!serverlessCollections.isEmpty()) {
            throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support collection routing");
        }
        return create(metadata, mode, context);
    }
}
