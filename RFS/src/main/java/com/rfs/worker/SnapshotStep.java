package com.rfs.worker;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.CmsEntry.SnapshotStatus;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.SnapshotCreator.SnapshotCreationFailed;


public class SnapshotStep {
    public static class SharedMembers {
        protected final CmsClient cmsClient;
        protected final GlobalState globalState;
        protected final SnapshotCreator snapshotCreator;
        protected Optional<CmsEntry.Snapshot> cmsEntry;

        public SharedMembers(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            this.globalState = globalState;
            this.cmsClient = cmsClient;
            this.snapshotCreator = snapshotCreator;
            this.cmsEntry = Optional.empty();
        }
    }

    public static abstract class Base implements WorkerStep {
        protected final Logger logger = LogManager.getLogger(getClass());
        protected final SharedMembers members;
    
        public Base(SharedMembers members) {
            this.members = members;
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

        public EnterPhase(SharedMembers members, Optional<CmsEntry.Snapshot> currentEntry) {
            super(members);
            this.members.cmsEntry = currentEntry;
        }

        @Override
        public void run() {
            logger.info("Snapshot not yet completed, entering Snapshot Phase...");
            members.globalState.updatePhase(GlobalState.Phase.SNAPSHOT_IN_PROGRESS);
        }

        @Override
        public WorkerStep nextStep() {
            if (members.cmsEntry.isEmpty()) {
                return new CreateEntry(members);
            } 
            
            CmsEntry.Snapshot currentEntry = members.cmsEntry.get();
            switch (currentEntry.status) {
                case NOT_STARTED:
                    return new InitiateSnapshot(members);
                case IN_PROGRESS:
                    return new WaitForSnapshot(members);
                default:
                    throw new IllegalStateException("Unexpected snapshot status: " + currentEntry.status);
            }
        }
    }

    /*
     * Idempotently create a CMS Entry for the Snapshot
     */
    public static class CreateEntry extends Base {
        public CreateEntry(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Snapshot CMS Entry not found, attempting to create it...");
            members.cmsClient.createSnapshotEntry(members.snapshotCreator.getSnapshotName());
            logger.info("Snapshot CMS Entry created");
        }

        @Override
        public WorkerStep nextStep() {
            return new InitiateSnapshot(members);
        }
    }

    /*
     * Idempotently initiate the Snapshot creation process
     */
    public static class InitiateSnapshot extends Base {
        public InitiateSnapshot(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Attempting to initiate the snapshot...");
            members.snapshotCreator.registerRepo();
            members.snapshotCreator.createSnapshot();

            logger.info("Snapshot in progress...");
            members.cmsClient.updateSnapshotEntry(members.snapshotCreator.getSnapshotName(), SnapshotStatus.IN_PROGRESS);
        }

        @Override
        public WorkerStep nextStep() {
            return new WaitForSnapshot(members);
        }
    }

    /*
     * Wait for the Snapshot to complete, regardless of whether we initiated it or not
     */
    public static class WaitForSnapshot extends Base {
        private SnapshotCreationFailed e = null;

        public WaitForSnapshot(SharedMembers members) {
            super(members);
        }

        protected void waitABit() throws InterruptedException {
            logger.info("Snapshot not finished yet; sleeping for 5 seconds...");
            Thread.sleep(5000);
        }

        @Override
        public void run() {
            try{
                while (!members.snapshotCreator.isSnapshotFinished()) {
                    waitABit();
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for Snapshot to complete", e);
                throw new SnapshotCreationFailed(members.snapshotCreator.getSnapshotName());
            } catch (SnapshotCreationFailed e) {
                this.e = e;
            } 
        }

        @Override
        public WorkerStep nextStep() {
            if (e == null) {
                return new ExitPhaseSuccess(members);                
            } else {
                return new ExitPhaseSnapshotFailed(members, e);
            }
        }
    }

    /*
     * Update the CMS Entry and the Worker's phase to indicate the Snapshot completed successfully
     */
    public static class ExitPhaseSuccess extends Base {
        public ExitPhaseSuccess(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            members.cmsClient.updateSnapshotEntry(members.snapshotCreator.getSnapshotName(), SnapshotStatus.COMPLETED);
            members.globalState.updatePhase(GlobalState.Phase.SNAPSHOT_COMPLETED);
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

        public ExitPhaseSnapshotFailed(SharedMembers members, SnapshotCreationFailed e) {
            super(members);
            this.e = e;
        }

        @Override
        public void run() {
            logger.error("Snapshot creation failed");
            members.cmsClient.updateSnapshotEntry(members.snapshotCreator.getSnapshotName(), SnapshotStatus.FAILED);
            members.globalState.updatePhase(GlobalState.Phase.SNAPSHOT_FAILED);
        }

        @Override
        public WorkerStep nextStep() {
            throw e;
        }
    }
}
