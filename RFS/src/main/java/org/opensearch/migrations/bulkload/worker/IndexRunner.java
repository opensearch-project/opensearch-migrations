package org.opensearch.migrations.bulkload.worker;

import java.util.ArrayList;
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
                List<CreationResult> creationResults;
                if (skipCreation.test(index.getName())) {
                    log.atInfo()
                        .setMessage("Index {} was not part of the allowlist and will not be migrated.")
                        .addArgument(index.getName())
                        .log();
                    creationResults = List.of(CreationResult.builder()
                        .name(index.getName())
                        .failureType(CreationFailureType.SKIPPED_DUE_TO_FILTER)
                        .build());
                } else {
                    creationResults = createIndex(index.getName(), mode, context);
                }

                creationResults.forEach(results::index);

                var indexMetadata = metadataFactory.fromRepo(snapshotName, index.getName());
                indexMetadata.getAliases().fieldNames().forEachRemaining(alias -> {
                    var aliasResult = CreationResult.builder().name(alias);
                    aliasResult.failureType(creationResults.get(0).getFailureType());
                    results.alias(aliasResult.build());
                });
            });
        return results.build();
    }

    private List<CreationResult> createIndex(String indexName, MigrationMode mode, ICreateIndexContext context) {
        var originalIndexMetadata = metadataFactory.fromRepo(snapshotName, indexName);
        var indexMetadata = originalIndexMetadata.deepCopy();
        List<CreationResult> creationResults = new ArrayList<>();
        try {
            List<IndexMetadata> transformedMetadataList = transformer.transformIndexMetadata(indexMetadata);
            for (IndexMetadata transformedMetadata : transformedMetadataList) {
                try {
                    creationResults.add(indexCreator.create(transformedMetadata, mode, context));
                } catch (Exception e) {
                    creationResults.add(CreationResult.builder()
                        .name(indexName)
                        .exception(new IndexTransformationException(indexName, e))
                        .failureType(CreationFailureType.UNABLE_TO_TRANSFORM_FAILURE)
                        .build());
                }
            }
        } catch (Exception e) {
            creationResults.add(CreationResult.builder()
                .name(indexName)
                .exception(new IndexTransformationException(indexName, e))
                .failureType(CreationFailureType.UNABLE_TO_TRANSFORM_FAILURE)
                .build());
        }
        return creationResults;
    } 
}
