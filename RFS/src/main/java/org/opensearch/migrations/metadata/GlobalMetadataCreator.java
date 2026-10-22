package org.opensearch.migrations.metadata;

import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

public interface GlobalMetadataCreator {
    /**
     * Removes metadata that the target writer will skip before it reaches the transformation
     * pipeline. Implementations that do not support early selection may keep the default.
     */
    default TemplateSelection selectTemplates(GlobalMetadata metadata) {
        return new TemplateSelection(metadata, emptyResults());
    }

    public GlobalMetadataCreatorResults create(
        GlobalMetadata metadata,
        MigrationMode mode,
        IClusterMetadataContext context);

    @SuppressWarnings("java:S100") // Sonar treats the generated record constructor as a method
    record TemplateSelection(GlobalMetadata metadata, GlobalMetadataCreatorResults skippedResults) {
        public GlobalMetadataCreatorResults merge(GlobalMetadataCreatorResults createdResults) {
            return GlobalMetadataCreatorResults.builder()
                .legacyTemplates(concat(createdResults.getLegacyTemplates(), skippedResults.getLegacyTemplates()))
                .indexTemplates(concat(createdResults.getIndexTemplates(), skippedResults.getIndexTemplates()))
                .componentTemplates(concat(
                    createdResults.getComponentTemplates(),
                    skippedResults.getComponentTemplates()
                ))
                .build();
        }

        private static List<CreationResult> concat(List<CreationResult> created, List<CreationResult> skipped) {
            var createdResults = created == null ? List.<CreationResult>of() : created;
            var skippedResults = skipped == null ? List.<CreationResult>of() : skipped;
            return Stream.concat(createdResults.stream(), skippedResults.stream()).toList();
        }
    }

    private static GlobalMetadataCreatorResults emptyResults() {
        return GlobalMetadataCreatorResults.builder()
            .legacyTemplates(List.of())
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .build();
    }
}
