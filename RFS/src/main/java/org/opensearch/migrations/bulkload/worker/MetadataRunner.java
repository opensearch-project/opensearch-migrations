package org.opensearch.migrations.bulkload.worker;

import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
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
    private final boolean allowExisting;

    public GlobalMetadataCreatorResults migrateMetadata(MigrationMode mode, IClusterMetadataContext context) {
        log.info("Migrating the Templates...");
        var globalMetadata = metadataFactory.fromRepo(snapshotName);
        var transformedRoot = transformer.transformGlobalMetadata(globalMetadata);
        var results = metadataCreator.create(transformedRoot, mode, context);
        log.info("Templates migration complete");
        if (allowExisting) {
            return suppressAlreadyExists(results);
        }
        return results;
    }

    private static List<CreationResult> filterAlreadyExists(List<CreationResult> items) {
        return items.stream()
            .map(r -> r.getFailureType() == CreationFailureType.ALREADY_EXISTS
                ? CreationResult.builder().name(r.getName()).build()
                : r)
            .collect(Collectors.toList());
    }

    private static GlobalMetadataCreatorResults suppressAlreadyExists(GlobalMetadataCreatorResults results) {
        return GlobalMetadataCreatorResults.builder()
            .legacyTemplates(filterAlreadyExists(results.getLegacyTemplates()))
            .componentTemplates(filterAlreadyExists(results.getComponentTemplates()))
            .indexTemplates(filterAlreadyExists(results.getIndexTemplates()))
            .build();
    }
}
