package com.rfs.worker;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.common.IndexMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;

public class IndexRunner implements Runner {
    private static final Logger logger = LogManager.getLogger(IndexRunner.class);

    private final IndexStep.SharedMembers members;

    public IndexRunner(GlobalState globalState, CmsClient cmsClient, String snapshotName, IndexMetadata.Factory metadataFactory, 
            IndexCreator_OS_2_11 indexCreator, Transformer transformer) {
        this.members = new IndexStep.SharedMembers(globalState, cmsClient, snapshotName, metadataFactory, indexCreator, transformer);
    }

    @Override
    public void runInternal() {
        WorkerStep nextStep = null;
        try {
            nextStep = new IndexStep.EnterPhase(members);

            while (nextStep != null) {
                nextStep.run();
                nextStep = nextStep.nextStep();
            }
        } catch (Exception e) {
            throw new IndexMigrationPhaseFailed(
                members.globalState.getPhase(),
                members.cmsEntry.map(bar -> (CmsEntry.Base) bar), 
                e
            );
        }
    }

    @Override
    public String getPhaseName() {
        return "Index Migration";
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    public static class IndexMigrationPhaseFailed extends Runner.PhaseFailed {
        public IndexMigrationPhaseFailed(GlobalState.Phase phase, Optional<CmsEntry.Base> cmsEntry, Exception e) {
            super("Index Migration Phase failed", phase, cmsEntry, e);
        }
    }    
}
