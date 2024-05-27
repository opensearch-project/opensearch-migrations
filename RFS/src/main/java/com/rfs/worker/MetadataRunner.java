package com.rfs.worker;

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

public class MetadataRunner implements Runner {
    private static final Logger logger = LogManager.getLogger(MetadataRunner.class);
    private final MetadataStep.SharedMembers members;

    public MetadataRunner(GlobalState globalState, CmsClient cmsClient, String snapshotName, GlobalMetadata.Factory metadataFactory,
            GlobalMetadataCreator_OS_2_11 metadataCreator, Transformer transformer) {
        this.members = new MetadataStep.SharedMembers(globalState, cmsClient, snapshotName, metadataFactory, metadataCreator, transformer);
    }

    @Override
    public void run() {
        WorkerStep nextStep = null;
        try {
            logger.info("Checking if work remains in the Metadata Phase...");
            Optional<Metadata> metadataEntry = members.cmsClient.getMetadataEntry();
            
            if (metadataEntry.isEmpty() || metadataEntry.get().status != MetadataStatus.COMPLETED) {
                nextStep = new MetadataStep.EnterPhase(members);

                while (nextStep != null) {
                    nextStep.run();
                    nextStep = nextStep.nextStep();
                }
            }

            logger.info("Metadata Phase is complete");
        } catch (Exception e) {
            logger.error("Metadata Migration Phase failed w/ an exception");
            logger.error(
                getPhaseFailureRecord(
                    members.globalState.getPhase(), 
                    nextStep, 
                    members.cmsEntry.map(bar -> (CmsEntry.Base) bar), 
                    e
                ).toString()
            );

            throw e;
        }

        
    }    
}