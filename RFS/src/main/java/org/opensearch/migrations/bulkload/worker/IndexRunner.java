package org.opensearch.migrations.bulkload.worker;

import java.util.List;

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
        var results = IndexMetadataResults.builder();
        var skipCreation = FilterScheme.filterByAllowList(indexAllowlist).negate();

        repoDataProvider.getIndicesInSnapshot(snapshotName)
            .stream()
            .forEach(index -> {
                CreationResult creationResult;
                if (skipCreation.test(index.getName())) {
                    log.atInfo()
                        .setMessage("Index {} was not part of the allowlist and will not be migrated.")
                        .addArgument(index.getName())
                        .log();
                    creationResult = CreationResult.builder()
                        .name(index.getName())
                        .failureType(CreationFailureType.SKIPPED_DUE_TO_FILTER)
                        .build();
                } else {
                    creationResult = createIndex(index.getName(), mode, context);
                }

                results.index(creationResult);

                var indexMetadata = metadataFactory.fromRepo(snapshotName, index.getName());
                indexMetadata.getAliases().fieldNames().forEachRemaining(alias -> {
                    var aliasResult = CreationResult.builder().name(alias);
                    aliasResult.failureType(creationResult.getFailureType());
                    results.alias(aliasResult.build());
                });
            });
        return results.build();
    }

    private CreationResult createIndex(String indexName, MigrationMode mode, ICreateIndexContext context) {
        var originalIndexMetadata = metadataFactory.fromRepo(snapshotName, indexName);
        var indexMetadata = originalIndexMetadata.deepCopy();
        try {
            indexMetadata = transformer.transformIndexMetadata(indexMetadata);
            return indexCreator.create(indexMetadata, mode, context);
        } catch (Exception e) {
            return CreationResult.builder()
                .name(indexName)
                .exception(new IndexTransformationException(indexName, e))
                .failureType(CreationFailureType.UNABLE_TO_TRANSFORM_FAILURE)
                .build();
        }
    } 
}
