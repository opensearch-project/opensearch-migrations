package org.opensearch.migrations.bulkload.worker;

import java.util.List;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class MetadataRunner {

    private final String snapshotName;
    private final GlobalMetadata.Factory metadataFactory;
    private final GlobalMetadataCreator metadataCreator;
    private final Transformer transformer;
    /** Empty unless the target is a collection-routed serverless endpoint */
    private final List<String> serverlessCollections;

    public MetadataRunner(
        String snapshotName,
        GlobalMetadata.Factory metadataFactory,
        GlobalMetadataCreator metadataCreator,
        Transformer transformer
    ) {
        this(snapshotName, metadataFactory, metadataCreator, transformer, List.of());
    }

    public GlobalMetadataCreatorResults migrateMetadata(MigrationMode mode, IClusterMetadataContext context) {
        log.info("Migrating the Templates...");
        var globalMetadata = metadataFactory.fromRepo(snapshotName);
        var transformedRoot = transformer.transformGlobalMetadata(globalMetadata);
        var results = metadataCreator.create(transformedRoot, mode, context, serverlessCollections);
        log.info("Templates migration complete");
        return results;
    }
}
