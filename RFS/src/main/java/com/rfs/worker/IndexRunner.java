package com.rfs.worker;

import java.util.List;
import java.util.function.BiConsumer;

import org.opensearch.migrations.metadata.IndexCreator;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

import com.rfs.common.FilterScheme;
import com.rfs.common.SnapshotRepo;
import com.rfs.models.IndexMetadata;
import com.rfs.transformers.Transformer;
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

    public IndexMetadataResults migrateIndices(ICreateIndexContext context) {
        SnapshotRepo.Provider repoDataProvider = metadataFactory.getRepoDataProvider();
        // TODO - parallelize this, maybe ~400-1K requests per thread and do it asynchronously

        BiConsumer<String, Boolean> logger = (indexName, accepted) -> {
            if (!accepted) {
                log.info("Index " + indexName + " rejected by allowlist");
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
                var resultOp = indexCreator.create(transformedRoot, context);
                resultOp.ifPresentOrElse(
                    value -> log.info("Index " + indexName + " created successfully"),
                    () -> log.info("Index " + indexName + " already existed; no work required")
                );
                results.indexName(indexName);
                transformedRoot.getAliases().fieldNames().forEachRemaining(results::alias);
            });
        return results.build();
    }
}
