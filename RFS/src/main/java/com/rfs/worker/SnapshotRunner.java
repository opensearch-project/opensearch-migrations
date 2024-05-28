package com.rfs.worker;

import org.apache.logging.log4j.Logger;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.CmsEntry.Snapshot;
import com.rfs.cms.CmsEntry.SnapshotStatus;
import com.rfs.common.SnapshotCreator;

public class SnapshotRunner implements Runner {
    private static final Logger logger = LogManager.getLogger(SnapshotRunner.class);
    private final SnapshotStep.SharedMembers members;

    public SnapshotRunner(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
        this.members = new SnapshotStep.SharedMembers(globalState, cmsClient, snapshotCreator);
    }

    @Override
    public void runInternal() {
        WorkerStep nextStep = null;

        try {
            Optional<Snapshot> snapshotEntry = members.cmsClient.getSnapshotEntry(members.snapshotCreator.getSnapshotName());
            
            if (snapshotEntry.isEmpty() || snapshotEntry.get().status != SnapshotStatus.COMPLETED) {
                nextStep = new SnapshotStep.EnterPhase(members, snapshotEntry);

                while (nextStep != null) {
                    nextStep.run();
                    nextStep = nextStep.nextStep();
                }
            }
        } catch (Exception e) {
            throw new SnapshotPhaseFailed(
                members.globalState.getPhase(), 
                nextStep, 
                members.cmsEntry.map(bar -> (CmsEntry.Base) bar), 
                e
            );
        }        
    }

    @Override
    public String getPhaseName() {
        return "Snapshot";
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    public static class SnapshotPhaseFailed extends Runner.PhaseFailed {
        public SnapshotPhaseFailed(GlobalState.Phase phase, WorkerStep nextStep, Optional<CmsEntry.Base> cmsEntry, Exception e) {
            super("Snapshot Phase failed", phase, nextStep, cmsEntry, e);
        }
    }
}
