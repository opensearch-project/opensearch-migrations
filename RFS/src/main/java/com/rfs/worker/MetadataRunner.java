package com.rfs.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.CmsEntry.Metadata;
import com.rfs.cms.CmsEntry.MetadataStatus;
import com.rfs.common.GlobalMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;

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
        var root = globalMetadata.toObjectNode();
        var transformedRoot = transformer.transformGlobalMetadata(root);
        metadataCreator.create(transformedRoot);
        log.info("Templates migration complete");
    }
}