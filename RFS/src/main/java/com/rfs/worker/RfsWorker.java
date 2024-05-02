package com.rfs.worker;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry.Snapshot;
import com.rfs.cms.CmsEntry.SnapshotStatus;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.SnapshotCreator.SnapshotCreationFailed;

public class RfsWorker {
    private static final Logger logger = LogManager.getLogger(RfsWorker.class);
    private final CmsClient cmsClient;
    private final GlobalData globalState;
    private final SnapshotCreator snapshotCreator;

    public RfsWorker(GlobalData globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
        this.globalState = globalState;
        this.cmsClient = cmsClient;
        this.snapshotCreator = snapshotCreator;
    }

    public void run() throws Exception {
        logger.info("Running RfsWorker");

        while (true) {
            logger.info("Checking if work remains in the Snapshot Phase...");
            Snapshot snapshotEntry = cmsClient.getSnapshotEntry(snapshotCreator.snapshotName);
            
            if (snapshotEntry == null || snapshotEntry.status != SnapshotStatus.COMPLETED) {
                WorkerState nextState = new SnapshotState.EnterPhase(globalState, cmsClient, snapshotCreator, snapshotEntry);

                while (nextState != null) {
                    nextState.run();
                    nextState = nextState.nextState();
                }
            }

            logger.info("Snapshot Phase complete");

            break;            
        }
    }
    
}
