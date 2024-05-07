package com.rfs.worker;

import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.OpenSearchCmsClient;
import com.rfs.common.GlobalMetadata;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;


public class MetadataStep {
    public static class SharedMembers {
        protected final GlobalState globalState;
        protected final CmsClient cmsClient;
        protected final String snapshotName;
        protected final GlobalMetadata.Factory metadataFactory;
        protected final GlobalMetadataCreator_OS_2_11 metadataCreator;
        protected final Transformer transformer;

        public SharedMembers(GlobalState globalState, CmsClient cmsClient, String snapshotName, GlobalMetadata.Factory metadataFactory,
                GlobalMetadataCreator_OS_2_11 metadataCreator, Transformer transformer) {
            this.globalState = globalState;
            this.cmsClient = cmsClient;
            this.snapshotName = snapshotName;
            this.metadataFactory = metadataFactory;
            this.metadataCreator = metadataCreator;
            this.transformer = transformer;
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
     * Updates the Worker's phase to indicate we're doing work on a Metadata Migration
     */
    public static class EnterPhase extends Base {
        public EnterPhase(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Metadata Migration not yet completed, entering Metadata Phase...");
            members.globalState.updatePhase(GlobalState.Phase.METADATA_IN_PROGRESS);
        }

        @Override
        public WorkerStep nextStep() {
            return new GetEntry(members);
        }
    }

    /*
     * Gets the current Metadata Migration entry from the CMS, if it exists
     */
    public static class GetEntry extends Base {
        private CmsEntry.Metadata metadataEntry;

        public GetEntry(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Pulling the Metadata Migration entry from the CMS, if it exists...");
            this.metadataEntry = members.cmsClient.getMetadataEntry();
        }

        @Override
        public WorkerStep nextStep() {
            if (metadataEntry == null) {
                return new CreateEntry(members);
            } else {
                switch (metadataEntry.status) {
                    case IN_PROGRESS:
                        // TODO: This uses the client-side clock to evaluate the lease expiration, when we should
                        // ideally be using the server-side clock.  Consider this a temporary solution until we find
                        // out how to use the server-side clock.
                        long leaseExpiryMillis = Long.parseLong(metadataEntry.leaseExpiry);
                        Instant leaseExpiryInstant = Instant.ofEpochMilli(leaseExpiryMillis);
                        boolean leaseExpired = leaseExpiryInstant.isBefore(Instant.now());
                        if (leaseExpired) {
                            return new AcquireLease(members, metadataEntry);
                        } 

                        if (metadataEntry.numAttempts > CmsEntry.Metadata.MAX_ATTEMPTS) {
                            return new ExitPhaseFailed(members, new MaxAttemptsExceeded());
                        }
                        
                        logger.info("Metadata Migration entry found, but there's already a valid work lease on it");
                        return new RandomWait(members);
                    case COMPLETED:
                        return new ExitPhaseSuccess(members);
                    case FAILED:
                        return new ExitPhaseFailed(members, new FoundFailedMetadataMigration());
                    default:
                        throw new IllegalStateException("Unexpected metadata migration status: " + metadataEntry.status);
                }
            }
        }
    }

    public static class CreateEntry extends Base {
        private boolean createdEntry;

        public CreateEntry(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Metadata Migration CMS Entry not found, attempting to create it...");
            this.createdEntry = members.cmsClient.createMetadataEntry();
            logger.info("Metadata Migration CMS Entry created");
        }

        @Override
        public WorkerStep nextStep() {
            if (createdEntry) {
                return new MigrateTemplates(members);
            } else {
                return new GetEntry(members);
            }
        }
    }

    public static class AcquireLease extends Base {
        private final CmsEntry.Metadata existingEntry;
        private boolean acquiredLease;

        public AcquireLease(SharedMembers members, CmsEntry.Metadata existingEntry) {
            super(members);
            this.existingEntry = existingEntry;
        }

        @Override
        public void run() {
            logger.info("Current Metadata Migration work lease appears to have expired; attempting to acquire it...");

            // TODO: Should be using the server-side clock here
            this.acquiredLease = members.cmsClient.updateMetadataEntry(
                CmsEntry.MetadataStatus.IN_PROGRESS,
                existingEntry.numAttempts + 1,
                String.valueOf(Instant.now().plusMillis(CmsEntry.Metadata.METADATA_LEASE_MS).toEpochMilli())
            );

            if (acquiredLease) {
                logger.info("Lease acquired");
            } else {
                logger.info("Failed to acquire lease");
            }
        }

        @Override
        public WorkerStep nextStep() {
            if (acquiredLease) {
                return new MigrateTemplates(members);
            } else {
                return new RandomWait(members);
            }
        }
    }

    public static class MigrateTemplates extends Base {
        private boolean updatedEntry = false;
        private MetadataMigrationFailed e = null;

        public MigrateTemplates(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            try {
                logger.info("Setting the worker's current work item to be the Metadata Migration...");
                members.globalState.updateWorkItem(new OpenSearchWorkItem(OpenSearchCmsClient.CMS_INDEX_NAME, OpenSearchCmsClient.CMS_METADATA_DOC_ID));
                logger.info("Work item set");

                logger.info("Migrating the Templates...");
                GlobalMetadata.Data globalMetadata = members.metadataFactory.fromRepo(members.snapshotName);
                ObjectNode root = globalMetadata.toObjectNode();
                ObjectNode transformedRoot = members.transformer.transformGlobalMetadata(root);
                members.metadataCreator.create(transformedRoot);
                logger.info("Templates migration complete");

                logger.info("Updating the Metadata Migration entry to indicate completion...");
                updatedEntry = members.cmsClient.setMetadataMigrationStatus(CmsEntry.MetadataStatus.COMPLETED);
                logger.info("Metadata Migration entry updated");

                logger.info("Clearing the worker's current work item...");
                members.globalState.updateWorkItem(null);
                logger.info("Work item cleared");

            } catch (Exception e) {
                logger.error("Failed to migrate the Templates");
                this.e = new MetadataMigrationFailed(e.getMessage());
            }
        }

        @Override
        public WorkerStep nextStep() {
            if (!updatedEntry) {
                // In this scenario, we've done all the work, but failed to update the CMS entry so that we know we've
                // done the work.  We circle back around to try again, which is made more reasonable by the fact we
                // don't re-migrate templates that already exist on the target cluster.  If we didn't circle back
                // around, there would be a chance that the CMS entry would never be marked as completed.
                //
                // The CMS entry's retry limit still applies in this case, so there's a limiting factor here.
                logger.warn("Completed migrating the templates but failed to update the Metadata Migration entry; retrying...");
                return new GetEntry(members);
            }
            if (updatedEntry && e == null) {
                return new ExitPhaseSuccess(members);
            } else {
                return new ExitPhaseFailed(members, e);
            }
        }
    }

    public static class RandomWait extends Base {
        private final static int WAIT_TIME_MS = 5 * 1000; // arbitrarily chosen
        private MetadataMigrationFailed e = null;

        public RandomWait(SharedMembers members) {
            super(members);
        }

        protected void waitABit() throws InterruptedException {
            Thread.sleep(WAIT_TIME_MS);
        }

        @Override
        public void run() {
            logger.info("Backing off for " + WAIT_TIME_MS  + " milliseconds before checking the Metadata Migration entry again...");

            try {
                waitABit();
            } catch (InterruptedException e) {
                logger.error("Interrupted while performing a wait", e);
                this.e = new MetadataMigrationFailed("Interrupted");
            }
        }

        @Override
        public WorkerStep nextStep() {
            if (e == null) {
                return new GetEntry(members);
            } else {                
                return new ExitPhaseFailed(members, e);
            }
        }
    }

    public static class ExitPhaseSuccess extends Base {
        public ExitPhaseSuccess(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            members.cmsClient.setMetadataMigrationStatus(CmsEntry.MetadataStatus.COMPLETED);
            members.globalState.updatePhase(GlobalState.Phase.METADATA_COMPLETED);
            logger.info("Metadata Migration completed, exiting Metadata Phase...");
        }

        @Override
        public WorkerStep nextStep() {
            return null;
        }
    }

    public static class ExitPhaseFailed extends Base {
        private final MetadataMigrationFailed e;

        public ExitPhaseFailed(SharedMembers members, MetadataMigrationFailed e) {
            super(members);
            this.e = e;
        }

        @Override
        public void run() {
            logger.error("Metadata Migration failed");
            members.cmsClient.setMetadataMigrationStatus(CmsEntry.MetadataStatus.FAILED);
            members.globalState.updatePhase(GlobalState.Phase.METADATA_FAILED);
            throw e;
        }

        @Override
        public WorkerStep nextStep() {
            return null;
        }
    }

    public static class MetadataMigrationFailed extends RuntimeException {
        public MetadataMigrationFailed(String message) {
            super("The Metadata Migration has failed.  Reason: " + message);
        }
    }

    public static class FoundFailedMetadataMigration extends MetadataMigrationFailed {
        public FoundFailedMetadataMigration() {
            super("We checked the status in the CMS and found it had failed.  Aborting.");
        }
    }

    public static class MaxAttemptsExceeded extends MetadataMigrationFailed {
        public MaxAttemptsExceeded() {
            super("We reached the limit of " + CmsEntry.Metadata.MAX_ATTEMPTS + " attempts to complete the Metadata Migration");
        }
    }
}
