package org.opensearch.migrations.bulkload.worker;

import java.util.List;
import java.util.function.BiConsumer;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.FilterScheme;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.transformers.Transformer;
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
        SnapshotRepo.Provider repoDataProvider = metadataFactory.getRepoDataProvider();
        // TODO - parallelize this, maybe ~400-1K requests per thread and do it asynchronously

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
                var created = indexCreator.create(transformedRoot, mode, context);
                if (created) {
                    log.atDebug().setMessage("Index {} created successfully").addArgument(indexName).log();
                    results.indexName(indexName);
                    transformedRoot.getAliases().fieldNames().forEachRemaining(results::alias);
                } else {
                    log.atWarn().setMessage("Index {} already existed; no work required").addArgument(indexName).log();
                }
            });
        return results.build();
    }
}
