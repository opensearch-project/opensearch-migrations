package com.rfs.worker;

import com.rfs.models.GlobalMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class MetadataRunner {

    private final String snapshotName;
    private final GlobalMetadata.Factory metadataFactory;
    private final GlobalMetadataCreator_OS_2_11 metadataCreator;
    private final Transformer transformer;

    public void migrateMetadata() {
        log.info("Migrating the Templates...");
        var globalMetadata = metadataFactory.fromRepo(snapshotName);
        var transformedRoot = transformer.transformGlobalMetadata(globalMetadata);
        metadataCreator.create(transformedRoot);
        log.info("Templates migration complete");
    }
}
