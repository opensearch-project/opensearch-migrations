package com.rfs.worker;

import java.util.List;
import java.util.function.BiConsumer;

import com.rfs.common.FilterScheme;
import com.rfs.common.SnapshotRepo;
import com.rfs.models.IndexMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class IndexRunner {

    private final String snapshotName;
    private final IndexMetadata.Factory metadataFactory;
    private final IndexCreator_OS_2_11 indexCreator;
    private final Transformer transformer;
    private final List<String> indexAllowlist;

    public void migrateIndices() {
        SnapshotRepo.Provider repoDataProvider = metadataFactory.getRepoDataProvider();
        // TODO - parallelize this, maybe ~400-1K requests per thread and do it asynchronously

        BiConsumer<String, Boolean> logger = (indexName, accepted) -> {
            if (!accepted) {
                log.info("Index " + indexName + " rejected by allowlist");
            }
        };
        repoDataProvider.getIndicesInSnapshot(snapshotName)
            .stream()
            .filter(FilterScheme.filterIndicesByAllowList(indexAllowlist, logger))
            .peek(index -> {
                var indexMetadata = metadataFactory.fromRepo(snapshotName, index.getName());
                var transformedRoot = transformer.transformIndexMetadata(indexMetadata);
                var resultOp = indexCreator.create(transformedRoot, index.getName(), indexMetadata.getId());
                resultOp.ifPresentOrElse(
                    value -> log.info("Index " + index.getName() + " created successfully"),
                    () -> log.info("Index " + index.getName() + " already existed; no work required")
                );
            })
            .count(); // Force the stream to execute
    }
}
