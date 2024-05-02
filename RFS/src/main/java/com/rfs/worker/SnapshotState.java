package com.rfs.worker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.CmsEntry.SnapshotStatus;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.SnapshotCreator.SnapshotCreationFailed;


public class SnapshotState {
    public static abstract class Base implements WorkerState {
        protected final Logger logger = LogManager.getLogger(getClass());
        protected final CmsClient cmsClient;
        protected final GlobalData globalState;
        protected final SnapshotCreator snapshotCreator;
    
        public Base(GlobalData globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            this.globalState = globalState;
            this.cmsClient = cmsClient;
            this.snapshotCreator = snapshotCreator;
        }
    
        @Override
        public abstract void run();
    
        @Override
        public abstract WorkerState nextState();
    }


    public static class EnterPhase extends Base {
        private final CmsEntry.Snapshot snapshotEntry;

        public EnterPhase(GlobalData globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator, CmsEntry.Snapshot snapshotEntry) {
            super(globalState, cmsClient, snapshotCreator);
            this.snapshotEntry = snapshotEntry;
        }

        @Override
        public void run() {
            logger.info("Snapshot not yet completed, entering Snapshot Phase...");
            globalState.updatePhase(GlobalData.Phase.SNAPSHOT_IN_PROGRESS);
        }

        @Override
        public WorkerState nextState() {
            if (snapshotEntry == null) {
                return new CreateEntry(globalState, cmsClient, snapshotCreator);
            } else {
                switch (snapshotEntry.status) {
                    case NOT_STARTED:
                        return new InitiateSnapshot(globalState, cmsClient, snapshotCreator);
                    case IN_PROGRESS:
                        return new WaitForSnapshot(globalState, cmsClient, snapshotCreator);
                    default:
                        throw new IllegalStateException("Unexpected snapshot status: " + snapshotEntry.status);
                }
            }
        }
    }

    public static class CreateEntry extends Base {
        public CreateEntry(GlobalData globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        @Override
        public void run() {
            logger.info("Snapshot CMS Entry not found, attempting to create it...");
            cmsClient.createSnapshotEntry(snapshotCreator.snapshotName);
            logger.info("Snapshot CMS Entry created");
        }

        @Override
        public WorkerState nextState() {
            return new InitiateSnapshot(globalState, cmsClient, snapshotCreator);
        }
    }

    public static class InitiateSnapshot extends Base {
        public InitiateSnapshot(GlobalData globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        @Override
        public void run() {
            logger.info("Attempting to initiate the snapshot...");
            snapshotCreator.registerRepo();
            snapshotCreator.createSnapshot();

            logger.info("Snapshot in progress...");
            cmsClient.updateSnapshotEntry(snapshotCreator.snapshotName, SnapshotStatus.IN_PROGRESS);
        }

        @Override
        public WorkerState nextState() {
            return new WaitForSnapshot(globalState, cmsClient, snapshotCreator);
        }
    }

    public static class WaitForSnapshot extends Base {
        private SnapshotCreationFailed e = null;

        public WaitForSnapshot(GlobalData globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        @Override
        public void run() {
            try{
                while (!snapshotCreator.isSnapshotFinished()) {
                    logger.info("Snapshot not finished yet; sleeping for 5 seconds...");
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for Snapshot to complete", e);
                this.e = snapshotCreator.new SnapshotCreationFailed(snapshotCreator.snapshotName);
            } catch (SnapshotCreationFailed e) {
                this.e = e;
            } 
        }

        @Override
        public WorkerState nextState() {
            if (e == null) {
                return new ExitPhaseSuccess(globalState, cmsClient, snapshotCreator);                
            } else {
                return new ExitPhaseSnapshotFailed(globalState, cmsClient, snapshotCreator, e);
            }
        }
    }

    public static class ExitPhaseSuccess extends Base {
        public ExitPhaseSuccess(GlobalData globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        @Override
        public void run() {
            cmsClient.updateSnapshotEntry(snapshotCreator.snapshotName, SnapshotStatus.COMPLETED);
            globalState.updatePhase(GlobalData.Phase.SNAPSHOT_COMPLETED);
            logger.info("Snapshot completed, exiting Snapshot Phase...");
        }

        @Override
        public WorkerState nextState() {
            return null;
        }
    }

    public static class ExitPhaseSnapshotFailed extends Base {
        private final SnapshotCreationFailed e;

        public ExitPhaseSnapshotFailed(GlobalData globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator, SnapshotCreationFailed e) {
            super(globalState, cmsClient, snapshotCreator);
            this.e = e;
        }

        @Override
        public void run() {
            logger.error("Snapshot creation failed");
            cmsClient.updateSnapshotEntry(snapshotCreator.snapshotName, SnapshotStatus.FAILED);
            globalState.updatePhase(GlobalData.Phase.SNAPSHOT_FAILED);
            throw e;
        }

        @Override
        public WorkerState nextState() {
            return null;
        }
    }
}
