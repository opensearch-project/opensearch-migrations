package com.rfs.worker;

import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import com.rfs.models.GlobalMetadata;
import com.rfs.transformers.Transformer;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class MetadataRunner {

    private final String snapshotName;
    private final GlobalMetadata.Factory metadataFactory;
    private final GlobalMetadataCreator metadataCreator;
    private final Transformer transformer;

    public GlobalMetadataCreatorResults migrateMetadata(IClusterMetadataContext context) {
        log.info("Migrating the Templates...");
        var globalMetadata = metadataFactory.fromRepo(snapshotName);
        var transformedRoot = transformer.transformGlobalMetadata(globalMetadata);
        var results = metadataCreator.create(transformedRoot, context);
        log.info("Templates migration complete");
        return results;
    }
}
