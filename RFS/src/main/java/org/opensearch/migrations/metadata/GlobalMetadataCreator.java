package org.opensearch.migrations.metadata;

import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import com.rfs.models.GlobalMetadata;

public interface GlobalMetadataCreator {
    public GlobalMetadataCreatorResults create(GlobalMetadata metadata, IClusterMetadataContext context);
}
