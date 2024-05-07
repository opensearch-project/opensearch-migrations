package com.rfs.worker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.CmsEntry.SnapshotStatus;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.SnapshotCreator.SnapshotCreationFailed;


public class SnapshotStep {
    public static abstract class Base implements WorkerStep {
        protected final Logger logger = LogManager.getLogger(getClass());
        protected final CmsClient cmsClient;
        protected final GlobalState globalState;
        protected final SnapshotCreator snapshotCreator;
    
        public Base(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            this.globalState = globalState;
            this.cmsClient = cmsClient;
            this.snapshotCreator = snapshotCreator;
        }
    
        @Override
        public abstract void run();
    
        @Override
        public abstract WorkerStep nextStep();
    }

    /*
     * Updates the Worker's phase to indicate we're doing work on a Snapshot
     */
    public static class EnterPhase extends Base {
        private final CmsEntry.Snapshot snapshotEntry;

        public EnterPhase(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator, CmsEntry.Snapshot snapshotEntry) {
            super(globalState, cmsClient, snapshotCreator);
            this.snapshotEntry = snapshotEntry;
        }

        @Override
        public void run() {
            logger.info("Snapshot not yet completed, entering Snapshot Phase...");
            globalState.updatePhase(GlobalState.Phase.SNAPSHOT_IN_PROGRESS);
        }

        @Override
        public WorkerStep nextStep() {
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

    /*
     * Idempotently create a CMS Entry for the Snapshot
     */
    public static class CreateEntry extends Base {
        public CreateEntry(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        @Override
        public void run() {
            logger.info("Snapshot CMS Entry not found, attempting to create it...");
            cmsClient.createSnapshotEntry(snapshotCreator.getSnapshotName());
            logger.info("Snapshot CMS Entry created");
        }

        @Override
        public WorkerStep nextStep() {
            return new InitiateSnapshot(globalState, cmsClient, snapshotCreator);
        }
    }

    /*
     * Idempotently initiate the Snapshot creation process
     */
    public static class InitiateSnapshot extends Base {
        public InitiateSnapshot(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        @Override
        public void run() {
            logger.info("Attempting to initiate the snapshot...");
            snapshotCreator.registerRepo();
            snapshotCreator.createSnapshot();

            logger.info("Snapshot in progress...");
            cmsClient.updateSnapshotEntry(snapshotCreator.getSnapshotName(), SnapshotStatus.IN_PROGRESS);
        }

        @Override
        public WorkerStep nextStep() {
            return new WaitForSnapshot(globalState, cmsClient, snapshotCreator);
        }
    }

    /*
     * Wait for the Snapshot to complete, regardless of whether we initiated it or not
     */
    public static class WaitForSnapshot extends Base {
        private SnapshotCreationFailed e = null;

        public WaitForSnapshot(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        protected void waitABit() throws InterruptedException {
            logger.info("Snapshot not finished yet; sleeping for 5 seconds...");
            Thread.sleep(5000);
        }

        @Override
        public void run() {
            try{
                while (!snapshotCreator.isSnapshotFinished()) {
                    waitABit();
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for Snapshot to complete", e);
                this.e = new SnapshotCreationFailed(snapshotCreator.getSnapshotName());
            } catch (SnapshotCreationFailed e) {
                this.e = e;
            } 
        }

        @Override
        public WorkerStep nextStep() {
            if (e == null) {
                return new ExitPhaseSuccess(globalState, cmsClient, snapshotCreator);                
            } else {
                return new ExitPhaseSnapshotFailed(globalState, cmsClient, snapshotCreator, e);
            }
        }
    }

    /*
     * Update the CMS Entry and the Worker's phase to indicate the Snapshot completed successfully
     */
    public static class ExitPhaseSuccess extends Base {
        public ExitPhaseSuccess(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        @Override
        public void run() {
            cmsClient.updateSnapshotEntry(snapshotCreator.getSnapshotName(), SnapshotStatus.COMPLETED);
            globalState.updatePhase(GlobalState.Phase.SNAPSHOT_COMPLETED);
            logger.info("Snapshot completed, exiting Snapshot Phase...");
        }

        @Override
        public WorkerStep nextStep() {
            return null;
        }
    }

    /*
     * Update the CMS Entry and the Worker's phase to indicate the Snapshot completed unsuccessfully
     */
    public static class ExitPhaseSnapshotFailed extends Base {
        private final SnapshotCreationFailed e;

        public ExitPhaseSnapshotFailed(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator, SnapshotCreationFailed e) {
            super(globalState, cmsClient, snapshotCreator);
            this.e = e;
        }

        @Override
        public void run() {
            logger.error("Snapshot creation failed");
            cmsClient.updateSnapshotEntry(snapshotCreator.getSnapshotName(), SnapshotStatus.FAILED);
            globalState.updatePhase(GlobalState.Phase.SNAPSHOT_FAILED);
            throw e;
        }

        @Override
        public WorkerStep nextStep() {
            return null;
        }
    }
}
