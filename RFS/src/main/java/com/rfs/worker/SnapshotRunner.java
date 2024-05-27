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
    public void run() {
        WorkerStep nextStep = null;

        try {
            logger.info("Checking if work remains in the Snapshot Phase...");
            Optional<Snapshot> snapshotEntry = members.cmsClient.getSnapshotEntry(members.snapshotCreator.getSnapshotName());
            
            if (snapshotEntry.isEmpty() || snapshotEntry.get().status != SnapshotStatus.COMPLETED) {
                nextStep = new SnapshotStep.EnterPhase(members, snapshotEntry);

                while (nextStep != null) {
                    nextStep.run();
                    nextStep = nextStep.nextStep();
                }
            }

            logger.info("Snapshot Phase is complete");
        } catch (Exception e) {
            logger.error("Snapshot Phase failed w/ an exception");
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
