package com.rfs.worker;

import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.SnapshotRepo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.common.IndexMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;

@Slf4j
@AllArgsConstructor
public class IndexRunner {

    private final String snapshotName;
    private final IndexMetadata.Factory metadataFactory;
    private final IndexCreator_OS_2_11 indexCreator;
    private final Transformer transformer;

    public void migrateIndices() {
        SnapshotRepo.Provider repoDataProvider = metadataFactory.getRepoDataProvider();
        // TODO - parallelize this, maybe ~400-1K requests per thread and do it asynchronously
        for (SnapshotRepo.Index index : repoDataProvider.getIndicesInSnapshot(snapshotName)) {
            var indexMetadata = metadataFactory.fromRepo(snapshotName, index.getName());
            var root = indexMetadata.toObjectNode();
            var transformedRoot = transformer.transformIndexMetadata(root);
            var resultOp = indexCreator.create(transformedRoot, index.getName(), indexMetadata.getId());
            resultOp.ifPresentOrElse(value -> log.info("Index " + index.getName() + " created successfully"),
                    () -> log.info("Index " + index.getName() + " already existed; no work required")
            );
        }
    }
}
