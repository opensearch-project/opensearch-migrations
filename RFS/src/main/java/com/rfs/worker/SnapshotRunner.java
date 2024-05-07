package com.rfs.worker;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry.Snapshot;
import com.rfs.cms.CmsEntry.SnapshotStatus;
import com.rfs.common.SnapshotCreator;

public class SnapshotRunner {
    private static final Logger logger = LogManager.getLogger(SnapshotRunner.class);
    private final CmsClient cmsClient;
    private final GlobalState globalState;
    private final SnapshotCreator snapshotCreator;

    public SnapshotRunner(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
        this.globalState = globalState;
        this.cmsClient = cmsClient;
        this.snapshotCreator = snapshotCreator;
    }

    public void run() throws Exception {
        logger.info("Checking if work remains in the Snapshot Phase...");
        Snapshot snapshotEntry = cmsClient.getSnapshotEntry(snapshotCreator.getSnapshotName());
        
        if (snapshotEntry == null || snapshotEntry.status != SnapshotStatus.COMPLETED) {
            WorkerStep nextState = new SnapshotStep.EnterPhase(globalState, cmsClient, snapshotCreator, snapshotEntry);

            while (nextState != null) {
                nextState.run();
                nextState = nextState.nextStep();
            }
        }

        logger.info("Snapshot Phase is complete");
    }
    
}
