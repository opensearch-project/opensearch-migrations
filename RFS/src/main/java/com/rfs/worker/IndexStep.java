package com.rfs.worker;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.OpenSearchCmsClient;
import com.rfs.common.IndexMetadata;
import com.rfs.common.RfsException;
import com.rfs.common.SnapshotRepo;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;

public class IndexStep {

    public static class SharedMembers {
        protected final GlobalState globalState;
        protected final CmsClient cmsClient;
        protected final String snapshotName;
        protected final IndexMetadata.Factory metadataFactory;
        protected final IndexCreator_OS_2_11 indexCreator;
        protected final Transformer transformer;
        protected Optional<CmsEntry.Index> cmsEntry;

        public SharedMembers(GlobalState globalState, CmsClient cmsClient, String snapshotName, IndexMetadata.Factory metadataFactory,
                IndexCreator_OS_2_11 indexCreator, Transformer transformer) {
            this.globalState = globalState;
            this.cmsClient = cmsClient;
            this.snapshotName = snapshotName;
            this.metadataFactory = metadataFactory;
            this.indexCreator = indexCreator;
            this.transformer = transformer;
            this.cmsEntry = Optional.empty();
        }

        // A convient way to check if the CMS entry is present before retrieving it.  In some places, it's fine/expected
        // for the CMS entry to be missing, but in others, it's a problem.
        public CmsEntry.Index getCmsEntryNotMissing() {
            return cmsEntry.orElseThrow(
                () -> new MissingIndexEntry()
            );
        }
    }

    public static abstract class Base implements WorkerStep {
        protected final Logger logger = LogManager.getLogger(getClass());
        protected final SharedMembers members;
    
        public Base(SharedMembers members) {
            this.members = members;
        }
    }

    /*
     * Updates the Worker's phase to indicate we're doing work on an Index Migration
     */
    public static class EnterPhase extends Base {
        public EnterPhase(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Index Migration not yet completed, entering Index Phase...");
            members.globalState.updatePhase(GlobalState.Phase.INDEX_IN_PROGRESS);
        }

        @Override
        public WorkerStep nextStep() {
            return new GetEntry(members);
        }
    }

    /*
     * Gets the current Index Migration entry from the CMS, if it exists
     */
    public static class GetEntry extends Base {

        public GetEntry(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Pulling the Index Migration entry from the CMS, if it exists...");
            members.cmsEntry = members.cmsClient.getIndexEntry();
        }

        @Override
        public WorkerStep nextStep() {
            if (members.cmsEntry.isEmpty()) {
                return new CreateEntry(members);
            } 
            
            CmsEntry.Index currentEntry = members.cmsEntry.get();            
            switch (currentEntry.status) {
                case SETUP:
                    // TODO: This uses the client-side clock to evaluate the lease expiration, when we should
                    // ideally be using the server-side clock.  Consider this a temporary solution until we find
                    // out how to use the server-side clock.
                    long leaseExpiryMillis = Long.parseLong(currentEntry.leaseExpiry);
                    Instant leaseExpiryInstant = Instant.ofEpochMilli(leaseExpiryMillis);
                    boolean leaseExpired = leaseExpiryInstant.isBefore(Instant.now());

                    // Don't try to acquire the lease if we're already at the max number of attempts
                    if (currentEntry.numAttempts >= CmsEntry.Index.MAX_ATTEMPTS && leaseExpired) {
                        return new ExitPhaseFailed(members, new MaxAttemptsExceeded());
                    }

                    if (leaseExpired) {
                        return new AcquireLease(members);
                    } 
                    
                    logger.info("Index Migration entry found, but there's already a valid work lease on it");
                    return new RandomWait(members);

                case IN_PROGRESS:
                    return new GetIndicesToMigrate(members);
                case COMPLETED:
                    return new ExitPhaseSuccess(members);
                case FAILED:
                    return new ExitPhaseFailed(members, new FoundFailedIndexMigration());
                default:
                    throw new IllegalStateException("Unexpected index migration status: " + currentEntry.status);
            }
        }
    }

    public static class CreateEntry extends Base {

        public CreateEntry(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Index Migration CMS Entry not found, attempting to create it...");
            members.cmsEntry = members.cmsClient.createIndexEntry();
            logger.info("Index Migration CMS Entry created");
        }

        @Override
        public WorkerStep nextStep() {
            // Set up the index work entries if we successfully created the CMS entry; otherwise, circle back to the beginning
            if (members.cmsEntry.isPresent()) {
                return new SetupIndexWorkEntries(members);
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
            CmsEntry.Index lastCmsEntry = members.getCmsEntryNotMissing();

            logger.info("Current Index Migration work lease appears to have expired; attempting to acquire it...");
            
            CmsEntry.Index updatedEntry = new CmsEntry.Index(
                lastCmsEntry.status,
                // Set the next CMS entry based on the current one
                // TODO: Should be using the server-side clock here
                CmsEntry.Index.getLeaseExpiry(getNowMs(), lastCmsEntry.numAttempts + 1),
                lastCmsEntry.numAttempts + 1
            );
            members.cmsEntry = members.cmsClient.updateIndexEntry(updatedEntry, lastCmsEntry);

            if (members.cmsEntry.isPresent()) {
                logger.info("Lease acquired");
            } else {
                logger.info("Failed to acquire lease");
            }
        }

        @Override
        public WorkerStep nextStep() {
            // Set up the index work entries if we acquired the lease; otherwise, circle back to the beginning after a backoff
            if (members.cmsEntry.isPresent()) {
                return new SetupIndexWorkEntries(members);
            } else {
                return new RandomWait(members);
            }
        }
    }
    
    public static class SetupIndexWorkEntries extends Base {

        public SetupIndexWorkEntries(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            // We only get here if we acquired the lock, so we know the CMS entry should not be missing
            CmsEntry.Index lastCmsEntry = members.getCmsEntryNotMissing();

            logger.info("Setting the worker's current work item to be creating the index work entries...");
            members.globalState.updateWorkItem(new OpenSearchWorkItem(OpenSearchCmsClient.CMS_INDEX_NAME, OpenSearchCmsClient.CMS_INDEX_DOC_ID));
            logger.info("Work item set");

            logger.info("Setting up the Index Work Items...");
            SnapshotRepo.Provider repoDataProvider = members.metadataFactory.getRepoDataProvider();
            for (SnapshotRepo.Index index : repoDataProvider.getIndicesInSnapshot(members.snapshotName)) {
                IndexMetadata.Data indexMetadata = members.metadataFactory.fromRepo(members.snapshotName, index.getName());
                logger.info("Creating Index Work Item for index: " + indexMetadata.getName());
                members.cmsClient.createIndexWorkItem(indexMetadata.getName(), indexMetadata.getNumberOfShards());
            }
            logger.info("Finished setting up the Index Work Items.");

            logger.info("Updating the Index Migration entry to indicate setup has been completed...");
            CmsEntry.Index updatedEntry = new CmsEntry.Index(
                CmsEntry.IndexStatus.IN_PROGRESS,
                lastCmsEntry.leaseExpiry,
                lastCmsEntry.numAttempts
            );

            members.cmsEntry = members.cmsClient.updateIndexEntry(updatedEntry, lastCmsEntry);
            logger.info("Index Migration entry updated");

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
                logger.warn("Completed creating the index work entries but failed to update the Index Migration entry; retrying...");
                return new GetEntry(members);
            }
            return new GetIndicesToMigrate(members);
        }
    }

    public static class GetIndicesToMigrate extends Base {
        public static final int MAX_WORK_ITEMS = 10; //Arbitrarily chosen

        protected List<CmsEntry.IndexWorkItem> workItems;

        public GetIndicesToMigrate(SharedMembers members) {
            super(members);
            workItems = List.of();
        }

        @Override
        public void run() {
            logger.info("Pulling a list of indices to migrate from the CMS...");
            workItems = members.cmsClient.getAvailableIndexWorkItems(MAX_WORK_ITEMS);
            logger.info("Pulled " + workItems.size() + " indices to migrate:");
            logger.info(workItems.toString());
        }

        @Override
        public WorkerStep nextStep() {
            if (workItems.isEmpty()) {
                return new ExitPhaseSuccess(members);
            } else {
                return new MigrateIndices(members, workItems);
            }
        }
    }

    public static class MigrateIndices extends Base {
        protected final List<CmsEntry.IndexWorkItem> workItems;

        public MigrateIndices(SharedMembers members, List<CmsEntry.IndexWorkItem> workItems) {
            super(members);
            this.workItems = workItems;
        }

        @Override
        public void run() {
            logger.info("Migrating current batch of indices...");
            for (CmsEntry.IndexWorkItem workItem : workItems) {
                /*
                 * Try to migrate the index.
                 * 
                 * If we succeed, we forcefully mark it as completed.  When we do so, we don't care if someone else has changed
                 * the record in the meantime; *we* completed it successfully and that's what matters.  Because this is the only
                 * forceful operation on the entry, the other operations are safe to be non-forceful.
                 * 
                 * If it's already exceeded the number of attempts, we attempt to mark it as failed.  If someone else
                 * has updated the entry in the meantime, we just move on to the next work item.  This is safe because
                 * it means someone else has either marked it as completed or failed, and either is fine.
                 * 
                 * If we fail to migrate it, we attempt to increment the attempt count.  It's fine if the increment
                 * fails because we guarantee that we'll attempt the work at least N times, not exactly N times.
                 */
                if (workItem.numAttempts > CmsEntry.IndexWorkItem.ATTEMPTS_SOFT_LIMIT) {
                    logger.warn("Index Work Item " + workItem.name + " has exceeded the maximum number of attempts; marking it as failed...");
                    CmsEntry.IndexWorkItem updatedEntry = new CmsEntry.IndexWorkItem(
                        workItem.name,
                        CmsEntry.IndexWorkItemStatus.FAILED,
                        workItem.numAttempts,
                        workItem.numShards
                    );

                    members.cmsClient.updateIndexWorkItem(updatedEntry, workItem).ifPresentOrElse(
                        value -> logger.info("Index Work Item " + workItem.name + " marked as failed"),
                        () ->logger.info("Unable to mark Index Work Item " + workItem.name + " as failed")
                    );
                    continue;
                }

                try {
                    logger.info("Migrating index: " + workItem.name);
                    IndexMetadata.Data indexMetadata = members.metadataFactory.fromRepo(members.snapshotName, workItem.name);

                    ObjectNode root = indexMetadata.toObjectNode();
                    ObjectNode transformedRoot = members.transformer.transformIndexMetadata(root);

                    members.indexCreator.create(transformedRoot, workItem.name, indexMetadata.getId()).ifPresentOrElse(
                        value -> logger.info("Index " + workItem.name + " created successfully"),
                        () -> logger.info("Index " + workItem.name + " already existed; no work required")
                    );

                    logger.info("Forcefully updating the Index Work Item to indicate it has been completed...");
                    CmsEntry.IndexWorkItem updatedEntry = new CmsEntry.IndexWorkItem(
                        workItem.name,
                        CmsEntry.IndexWorkItemStatus.COMPLETED,
                        workItem.numAttempts,
                        workItem.numShards
                    );
                    members.cmsClient.updateIndexWorkItemForceful(updatedEntry);
                    logger.info("Index Work Item updated");
                } catch (Exception e) {
                    logger.info("Failed to migrate index: " + workItem.name, e);
                    logger.info("Updating the Index Work Item with incremented attempt count...");
                    CmsEntry.IndexWorkItem updatedEntry = new CmsEntry.IndexWorkItem(
                        workItem.name,
                        workItem.status,
                        workItem.numAttempts + 1,
                        workItem.numShards
                    );

                    members.cmsClient.updateIndexWorkItem(updatedEntry, workItem).ifPresentOrElse(
                        value -> logger.info("Index Work Item " + workItem.name + " attempt count was incremented"),
                        () ->logger.info("Unable to increment attempt count of Index Work Item " + workItem.name)
                    );
                }
            }
        }

        @Override
        public WorkerStep nextStep() {
            return new GetIndicesToMigrate(members);
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
                throw new IndexMigrationFailed("Interrupted");
            }
        }

        @Override
        public void run() {
            logger.info("Backing off for " + WAIT_TIME_MS  + " milliseconds before checking the Index Migration entry again...");
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
            logger.info("Marking the Index Migration as completed...");
            CmsEntry.Index lastCmsEntry = members.getCmsEntryNotMissing();
            CmsEntry.Index updatedEntry = new CmsEntry.Index(
                CmsEntry.IndexStatus.COMPLETED,
                lastCmsEntry.leaseExpiry,
                lastCmsEntry.numAttempts
            );
            members.cmsClient.updateIndexEntry(updatedEntry, lastCmsEntry);
            logger.info("Index Migration marked as completed");

            logger.info("Index Migration completed, exiting Index Phase...");
            members.globalState.updatePhase(GlobalState.Phase.INDEX_COMPLETED);
        }

        @Override
        public WorkerStep nextStep() {
            return null;
        }
    }

    public static class ExitPhaseFailed extends Base {
        private final IndexMigrationFailed e;

        public ExitPhaseFailed(SharedMembers members, IndexMigrationFailed e) {
            super(members);
            this.e = e;
        }

        @Override
        public void run() {
            // We either failed the Index Migration or found it had already been failed; either way this
            // should not be missing
            CmsEntry.Index lastCmsEntry = members.getCmsEntryNotMissing();

            logger.error("Index Migration failed");
            CmsEntry.Index updatedEntry = new CmsEntry.Index(
                CmsEntry.IndexStatus.FAILED,
                lastCmsEntry.leaseExpiry,
                lastCmsEntry.numAttempts
            );
            members.cmsClient.updateIndexEntry(updatedEntry, lastCmsEntry);
            members.globalState.updatePhase(GlobalState.Phase.INDEX_FAILED);
        }

        @Override
        public WorkerStep nextStep() {
            throw e;
        }
    }

    public static class IndexMigrationFailed extends RfsException {
        public IndexMigrationFailed(String message) {
            super("The Index Migration has failed.  Reason: " + message);
        }
    }

    public static class MissingIndexEntry extends RfsException {
        public MissingIndexEntry() {
            super("The Index Migration CMS entry we expected to be stored in local memory was null."
                + "  This should never happen."
            );
        }
    }

    public static class FoundFailedIndexMigration extends IndexMigrationFailed {
        public FoundFailedIndexMigration() {
            super("We checked the status in the CMS and found it had failed.  Aborting.");
        }
    }

    public static class MaxAttemptsExceeded extends IndexMigrationFailed {
        public MaxAttemptsExceeded() {
            super("We reached the limit of " + CmsEntry.Index.MAX_ATTEMPTS + " attempts to complete the Index Migration");
        }
    }
}
