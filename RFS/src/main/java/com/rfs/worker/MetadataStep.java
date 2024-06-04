package com.rfs.worker;

import java.time.Instant;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.OpenSearchCmsClient;
import com.rfs.common.GlobalMetadata;
import com.rfs.common.RfsException;
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
        protected Optional<CmsEntry.Metadata> cmsEntry;

        public SharedMembers(GlobalState globalState, CmsClient cmsClient, String snapshotName, GlobalMetadata.Factory metadataFactory,
                GlobalMetadataCreator_OS_2_11 metadataCreator, Transformer transformer) {
            this.globalState = globalState;
            this.cmsClient = cmsClient;
            this.snapshotName = snapshotName;
            this.metadataFactory = metadataFactory;
            this.metadataCreator = metadataCreator;
            this.transformer = transformer;
            this.cmsEntry = Optional.empty();
        }

        // A convient way to check if the CMS entry is present before retrieving it.  In some places, it's fine/expected
        // for the CMS entry to be missing, but in others, it's a problem.
        public CmsEntry.Metadata getCmsEntryNotMissing() {
            return cmsEntry.orElseThrow(
                () -> new MissingMigrationEntry("The Metadata Migration CMS entry we expected to be stored in local memory was empty")
            );
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

        public GetEntry(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Pulling the Metadata Migration entry from the CMS, if it exists...");
            members.cmsEntry = members.cmsClient.getMetadataEntry();
        }

        @Override
        public WorkerStep nextStep() {
            if (members.cmsEntry.isEmpty()) {
                return new CreateEntry(members);
            } 
            
            CmsEntry.Metadata currentEntry = members.cmsEntry.get();            
            switch (currentEntry.status) {
                case IN_PROGRESS:
                    // TODO: This uses the client-side clock to evaluate the lease expiration, when we should
                    // ideally be using the server-side clock.  Consider this a temporary solution until we find
                    // out how to use the server-side clock.
                    long leaseExpiryMillis = Long.parseLong(currentEntry.leaseExpiry);
                    Instant leaseExpiryInstant = Instant.ofEpochMilli(leaseExpiryMillis);
                    boolean leaseExpired = leaseExpiryInstant.isBefore(Instant.now());

                    // Don't try to acquire the lease if we're already at the max number of attempts
                    if (currentEntry.numAttempts >= CmsEntry.Metadata.MAX_ATTEMPTS && leaseExpired) {
                        return new ExitPhaseFailed(members, new MaxAttemptsExceeded());
                    }

                    if (leaseExpired) {
                        return new AcquireLease(members);
                    } 
                    
                    logger.info("Metadata Migration entry found, but there's already a valid work lease on it");
                    return new RandomWait(members);
                case COMPLETED:
                    return new ExitPhaseSuccess(members);
                case FAILED:
                    return new ExitPhaseFailed(members, new FoundFailedMetadataMigration());
                default:
                    throw new IllegalStateException("Unexpected metadata migration status: " + currentEntry.status);
            }
        }
    }

    public static class CreateEntry extends Base {

        public CreateEntry(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Metadata Migration CMS Entry not found, attempting to create it...");
            members.cmsEntry = members.cmsClient.createMetadataEntry();
            logger.info("Metadata Migration CMS Entry created");
        }

        @Override
        public WorkerStep nextStep() {
            // Migrate the templates if we successfully created the CMS entry; otherwise, circle back to the beginning
            if (members.cmsEntry.isPresent()) {
                return new MigrateTemplates(members);
            } else {
                return new GetEntry(members);
            }
        }
    }

    public static class AcquireLease extends Base {

        public AcquireLease(SharedMembers members) {
            super(members);
        }

        protected long getNowMs() {
            return Instant.now().toEpochMilli();
        }

        @Override
        public void run() {
            // We only get here if we know we want to acquire the lock, so we know the CMS entry should not be null
            CmsEntry.Metadata currentCmsEntry = members.getCmsEntryNotMissing();

            logger.info("Current Metadata Migration work lease appears to have expired; attempting to acquire it...");

            // Set the next CMS entry based on the current one
            members.cmsEntry = members.cmsClient.updateMetadataEntry(
                CmsEntry.MetadataStatus.IN_PROGRESS,
                // TODO: Should be using the server-side clock here
                CmsEntry.Metadata.getLeaseExpiry(getNowMs(), currentCmsEntry.numAttempts + 1),
                currentCmsEntry.numAttempts + 1
            );

            if (members.cmsEntry.isPresent()) {
                logger.info("Lease acquired");
            } else {
                logger.info("Failed to acquire lease");
            }
        }

        @Override
        public WorkerStep nextStep() {
            // Migrate the templates if we acquired the lease; otherwise, circle back to the beginning after a backoff
            if (members.cmsEntry.isPresent()) {
                return new MigrateTemplates(members);
            } else {
                return new RandomWait(members);
            }
        }
    }

    public static class MigrateTemplates extends Base {

        public MigrateTemplates(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            // We only get here if we acquired the lock, so we know the CMS entry should not be null
            CmsEntry.Metadata currentCmsEntry = members.getCmsEntryNotMissing();

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
            members.cmsEntry = members.cmsClient.updateMetadataEntry(
                CmsEntry.MetadataStatus.COMPLETED,
                currentCmsEntry.leaseExpiry,
                currentCmsEntry.numAttempts
            );
            logger.info("Metadata Migration entry updated");

            logger.info("Clearing the worker's current work item...");
            members.globalState.updateWorkItem(null);
            logger.info("Work item cleared");
        }

        @Override
        public WorkerStep nextStep() {
            if (members.cmsEntry.isEmpty()) {
                // In this scenario, we've done all the work, but failed to update the CMS entry so that we know we've
                // done the work.  We circle back around to try again, which is made more reasonable by the fact we
                // don't re-migrate templates that already exist on the target cluster.  If we didn't circle back
                // around, there would be a chance that the CMS entry would never be marked as completed.
                //
                // The CMS entry's retry limit still applies in this case, so there's a limiting factor here.
                logger.warn("Completed migrating the templates but failed to update the Metadata Migration entry; retrying...");
                return new GetEntry(members);
            }
            return new ExitPhaseSuccess(members);
        }
    }

    public static class RandomWait extends Base {
        private final static int WAIT_TIME_MS = 5 * 1000; // arbitrarily chosen

        public RandomWait(SharedMembers members) {
            super(members);
        }

        protected void waitABit() {
            try {
                Thread.sleep(WAIT_TIME_MS);
            } catch (InterruptedException e) {
                logger.error("Interrupted while performing a wait", e);
                throw new MetadataMigrationFailed("Interrupted");
            }
        }

        @Override
        public void run() {
            logger.info("Backing off for " + WAIT_TIME_MS  + " milliseconds before checking the Metadata Migration entry again...");
            waitABit();            
        }

        @Override
        public WorkerStep nextStep() {
            return new GetEntry(members);
        }
    }

    public static class ExitPhaseSuccess extends Base {
        public ExitPhaseSuccess(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Metadata Migration completed, exiting Metadata Phase...");
            members.globalState.updatePhase(GlobalState.Phase.METADATA_COMPLETED);
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
            // We either failed the Metadata Migration or found it had already been failed; either way this
            // should not be null
            CmsEntry.Metadata currentCmsEntry = members.getCmsEntryNotMissing();

            logger.error("Metadata Migration failed");
            members.cmsClient.updateMetadataEntry(
                CmsEntry.MetadataStatus.FAILED,
                currentCmsEntry.leaseExpiry,
                currentCmsEntry.numAttempts
            );
            members.globalState.updatePhase(GlobalState.Phase.METADATA_FAILED);
        }

        @Override
        public WorkerStep nextStep() {
            throw e;
        }
    }
    public static class MissingMigrationEntry extends RfsException {
        public MissingMigrationEntry(String message) {
            super("The Metadata Migration CMS entry we expected to be stored in local memory was null."
                + "  This should never happen."
            );
        }
    }

    public static class MetadataMigrationFailed extends RfsException {
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
