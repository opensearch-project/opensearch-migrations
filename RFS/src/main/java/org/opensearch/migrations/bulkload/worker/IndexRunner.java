package org.opensearch.migrations.bulkload.worker;

import java.util.List;
import java.util.function.BiConsumer;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.FilterScheme;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.metadata.CreationResult;
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

        repoDataProvider.getIndicesInSnapshot(snapshotName)
            .stream()
            .filter(FilterScheme.filterIndicesByAllowList(indexAllowlist, logger))
            .forEach(index -> {
                var indexName = index.getName();
                var indexMetadata = metadataFactory.fromRepo(snapshotName, indexName);
                var transformedRoot = transformer.transformIndexMetadata(indexMetadata);
                var indexResult = indexCreator.create(transformedRoot, mode, context);
                results.indexName(indexResult);
                transformedRoot.getAliases().fieldNames().forEachRemaining( alias -> {
                    var aliasResult = CreationResult.builder().name(alias);
                    aliasResult.failureType(indexResult.getFailureType());
                    results.alias(aliasResult.build());
                });
            });
        return results.build();
    }
}
