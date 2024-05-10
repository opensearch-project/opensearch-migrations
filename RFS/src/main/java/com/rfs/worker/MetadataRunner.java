package com.rfs.worker;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry.Metadata;
import com.rfs.cms.CmsEntry.MetadataStatus;
import com.rfs.common.GlobalMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;

public class MetadataRunner {
    private static final Logger logger = LogManager.getLogger(SnapshotRunner.class);
    private final CmsClient cmsClient;
    private final GlobalState globalState;
    private final String snapshotName;
    private final GlobalMetadata.Factory metadataFactory;
    private final GlobalMetadataCreator_OS_2_11 metadataCreator;
    private final Transformer transformer;

    public MetadataRunner(GlobalState globalState, CmsClient cmsClient, String snapshotName, GlobalMetadata.Factory metadataFactory,
            GlobalMetadataCreator_OS_2_11 metadataCreator, Transformer transformer) {
        this.globalState = globalState;
        this.cmsClient = cmsClient;
        this.snapshotName = snapshotName;
        this.metadataFactory = metadataFactory;
        this.metadataCreator = metadataCreator;
        this.transformer = transformer;
    }

    public void run() throws Exception {
        logger.info("Checking if work remains in the Metadata Phase...");
        Metadata metadataEntry = cmsClient.getMetadataEntry();
        
        if (metadataEntry == null || metadataEntry.status != MetadataStatus.COMPLETED) {
            MetadataStep.SharedMembers members = new MetadataStep.SharedMembers(
                globalState, 
                cmsClient, 
                snapshotName, 
                metadataFactory, 
                metadataCreator, 
                transformer
            );
            WorkerStep nextState = new MetadataStep.EnterPhase(members);

            while (nextState != null) {
                nextState.run();
                nextState = nextState.nextStep();
            }
        }

        logger.info("Metadata Phase is complete");
    }    
}