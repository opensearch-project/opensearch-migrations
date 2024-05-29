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
    public void runInternal() {
        WorkerStep nextStep = null;
        try {
            Optional<Metadata> metadataEntry = members.cmsClient.getMetadataEntry();
            
            if (metadataEntry.isEmpty() || metadataEntry.get().status != MetadataStatus.COMPLETED) {
                nextStep = new MetadataStep.EnterPhase(members);

                while (nextStep != null) {
                    nextStep.run();
                    nextStep = nextStep.nextStep();
                }
            }
        } catch (Exception e) {
            throw new MetadataMigrationPhaseFailed(
                members.globalState.getPhase(), 
                nextStep, 
                members.cmsEntry.map(bar -> (CmsEntry.Base) bar), 
                e
            );
        }        
    }

    @Override
    public String getPhaseName() {
        return "Metadata Migration";
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    public static class MetadataMigrationPhaseFailed extends Runner.PhaseFailed {
        public MetadataMigrationPhaseFailed(GlobalState.Phase phase, WorkerStep nextStep, Optional<CmsEntry.Base> cmsEntry, Exception e) {
            super("Metadata Migration Phase failed", phase, nextStep, cmsEntry, e);
        }
    }
}