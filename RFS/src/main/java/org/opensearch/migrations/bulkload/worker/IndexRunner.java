package org.opensearch.migrations.bulkload.worker;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.FilterScheme;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.transformers.IndexTransformationException;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.IndexCreator;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class IndexRunner {

    private final String snapshotName;
    private final IndexMetadata.Factory metadataFactory;
    private final IndexCreator indexCreator;
    private final Transformer transformer;
    private final List<String> indexAllowlist;

    public IndexMetadataResults migrateIndices(MigrationMode mode, ICreateIndexContext context) {
        var repoDataProvider = metadataFactory.getRepoDataProvider();
        BiConsumer<String, Boolean> logger = (indexName, accepted) -> {
            if (Boolean.FALSE.equals(accepted)) {
                log.atInfo().setMessage("Index {} rejected by allowlist").addArgument(indexName).log();
            }
        };
        var results = IndexMetadataResults.builder();

        // Set results for filtered items
        repoDataProvider.getIndicesInSnapshot(snapshotName)
                .stream()
                .filter(Predicate.not(FilterScheme.filterIndicesByAllowList(indexAllowlist, logger)))
                .forEach(index -> results.index(CreationResult.builder()
                        .name(index.getName())
                        .failureType(CreationFailureType.SKIPPED_DUE_TO_FILTER)
                        .build()));


        repoDataProvider.getIndicesInSnapshot(snapshotName)
            .stream()
            .filter(FilterScheme.filterIndicesByAllowList(indexAllowlist, logger))
            .forEach(index -> {
                var indexName = index.getName();
                var originalIndexMetadata = metadataFactory.fromRepo(snapshotName, indexName);

                CreationResult indexResult = null;
                var indexMetadata = originalIndexMetadata.deepCopy();
                try {
                    indexMetadata = transformer.transformIndexMetadata(indexMetadata);
                    indexResult = indexCreator.create(indexMetadata, mode, context);
                } catch (Throwable t) {
                    indexResult = CreationResult.builder()
                        .name(indexName)
                        .exception(new IndexTransformationException(indexName, t))
                        .failureType(CreationFailureType.UNABLE_TO_TRANSFORM_FAILURE)
                        .build();
                }

                var finalResult = indexResult;
                results.index(finalResult);

                indexMetadata.getAliases().fieldNames().forEachRemaining(alias -> {
                    var aliasResult = CreationResult.builder().name(alias);
                    aliasResult.failureType(finalResult.getFailureType());
                    results.alias(aliasResult.build());
                });
            });
        return results.build();
    }
}
