package org.opensearch.migrations.bulkload.worker;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.GlobalMetadataCreator.TemplateSelection;
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

    public GlobalMetadataCreatorResults migrateMetadata(MigrationMode mode, IClusterMetadataContext context) {
        return migrateMetadata(mode, context, selectTemplates());
    }

    public TemplateSelection selectTemplates() {
        var globalMetadata = metadataFactory.fromRepo(snapshotName);
        return metadataCreator.selectTemplates(globalMetadata);
    }

    public GlobalMetadataCreatorResults migrateMetadata(
        MigrationMode mode,
        IClusterMetadataContext context,
        TemplateSelection selection
    ) {
        log.info("Migrating the Templates...");
        var transformedRoot = transformer.transformGlobalMetadata(selection.metadata());
        var createdResults = metadataCreator.create(transformedRoot, mode, context);
        var results = selection.merge(createdResults);
        log.info("Templates migration complete");
        return results;
    }
}
