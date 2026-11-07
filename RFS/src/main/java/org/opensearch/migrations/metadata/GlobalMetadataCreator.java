package org.opensearch.migrations.metadata;

import java.util.List;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

public interface GlobalMetadataCreator {
    /**
     * @param serverlessCollections the OpenSearch Serverless collections behind a per-account
     *                              endpoint to create the metadata in, empty when the target is
     *                              not collection-routed
     */
    public GlobalMetadataCreatorResults create(
        GlobalMetadata metadata,
        MigrationMode mode,
        IClusterMetadataContext context,
        List<String> serverlessCollections);
}
