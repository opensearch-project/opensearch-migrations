package com.rfs.worker;

import java.time.Instant;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.OpenSearchCmsClient;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.IndexMetadata;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.RfsException;
import com.rfs.common.ShardMetadata;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotShardUnpacker;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

public class DocumentsStep {
    @RequiredArgsConstructor
    public static class SharedMembers {
        protected final GlobalState globalState;
        protected final CmsClient cmsClient;
        protected final String snapshotName;
        protected final long maxShardSizeBytes;
        protected final IndexMetadata.Factory metadataFactory;
        protected final ShardMetadata.Factory shardMetadataFactory;
        protected final SnapshotShardUnpacker unpacker;
        protected final LuceneDocumentsReader reader;
        protected final DocumentReindexer reindexer;
        protected Optional<CmsEntry.Documents> cmsEntry = Optional.empty();
        protected Optional<CmsEntry.DocumentsWorkItem> cmsWorkEntry = Optional.empty();

        // Convient ways to check if the CMS entries are present before retrieving them.  In some places, it's fine/expected
        // for the CMS entry to be missing, but in others, it's a problem.
        public CmsEntry.Documents getCmsEntryNotMissing() {
            return cmsEntry.orElseThrow(
                () -> new MissingDocumentsEntry()
            );
        }
        public CmsEntry.DocumentsWorkItem getCmsWorkEntryNotMissing() {
            return cmsWorkEntry.orElseThrow(
                () -> new MissingDocumentsEntry()
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
            logger.info("Documents Migration not yet completed, entering Documents Phase...");
            members.globalState.updatePhase(GlobalState.Phase.DOCUMENTS_IN_PROGRESS);
        }

        @Override
        public WorkerStep nextStep() {
            return new GetEntry(members);
        }
    }

    /*
     * Gets the current Documents Migration entry from the CMS, if it exists
     */
    public static class GetEntry extends Base {

        public GetEntry(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Pulling the Documents Migration entry from the CMS, if it exists...");
            members.cmsEntry = members.cmsClient.getDocumentsEntry();
        }

        @Override
        public WorkerStep nextStep() {
            if (members.cmsEntry.isEmpty()) {
                return new CreateEntry(members);
            } 
            
            CmsEntry.Documents currentEntry = members.cmsEntry.get();
            switch (currentEntry.status) {
                case SETUP:
                    // TODO: This uses the client-side clock to evaluate the lease expiration, when we should
                    // ideally be using the server-side clock.  Consider this a temporary solution until we find
                    // out how to use the server-side clock.
                    long leaseExpiryMillis = Long.parseLong(currentEntry.leaseExpiry);
                    Instant leaseExpiryInstant = Instant.ofEpochMilli(leaseExpiryMillis);
                    boolean leaseExpired = leaseExpiryInstant.isBefore(Instant.now());

                    // Don't try to acquire the lease if we're already at the max number of attempts
                    if (currentEntry.numAttempts >= CmsEntry.Documents.MAX_ATTEMPTS && leaseExpired) {
                        return new ExitPhaseFailed(members, new MaxAttemptsExceeded());
                    }

                    if (leaseExpired) {
                        return new AcquireLease(members, currentEntry);
                    } 
                    
                    logger.info("Documents Migration entry found, but there's already a valid work lease on it");
                    return new RandomWait(members);

                case IN_PROGRESS:
                    return new GetDocumentsToMigrate(members);
                case COMPLETED:
                    return new ExitPhaseSuccess(members);
                case FAILED:
                    return new ExitPhaseFailed(members, new FoundFailedDocumentsMigration());
                default:
                    throw new IllegalStateException("Unexpected documents migration status: " + currentEntry.status);
            }
        }
    }

    public static class CreateEntry extends Base {

        public CreateEntry(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Documents Migration CMS Entry not found, attempting to create it...");
            members.cmsEntry = members.cmsClient.createDocumentsEntry();
            logger.info("Documents Migration CMS Entry created");
        }

        @Override
        public WorkerStep nextStep() {
            // Set up the documents work entries if we successfully created the CMS entry; otherwise, circle back to the beginning
            if (members.cmsEntry.isPresent()) {
                return new SetupDocumentsWorkEntries(members);
            } else {
                return new GetEntry(members);
            }
        }
    }

    public static class AcquireLease extends Base {
        protected final CmsEntry.Base entry;
        protected Optional<CmsEntry.Base> leasedEntry = Optional.empty();

        public AcquireLease(SharedMembers members, CmsEntry.Base entry) {
            super(members);
            this.entry = entry;
        }

        protected long getNowMs() {
            return Instant.now().toEpochMilli();
        }

        @Override
        public void run() {

            if (entry instanceof CmsEntry.Documents) {
                CmsEntry.Documents currentCmsEntry = (CmsEntry.Documents) entry;
                logger.info("Attempting to acquire lease on Documents Migration entry...");            
                CmsEntry.Documents updatedEntry = new CmsEntry.Documents(
                    currentCmsEntry.status,
                    // Set the next CMS entry based on the current one
                    // TODO: Should be using the server-side clock here
                    CmsEntry.Documents.getLeaseExpiry(getNowMs(), currentCmsEntry.numAttempts + 1),
                    currentCmsEntry.numAttempts + 1
                );
                members.cmsEntry = members.cmsClient.updateDocumentsEntry(updatedEntry, currentCmsEntry);
                leasedEntry = members.cmsEntry.map(bar -> (CmsEntry.Base) bar);
            } else if (entry instanceof CmsEntry.DocumentsWorkItem) {
                CmsEntry.DocumentsWorkItem currentCmsEntry = (CmsEntry.DocumentsWorkItem) entry;
                logger.info("Attempting to acquire lease on Documents Work Item entry...");            
                CmsEntry.DocumentsWorkItem updatedEntry = new CmsEntry.DocumentsWorkItem(
                    currentCmsEntry.indexName,
                    currentCmsEntry.shardId,
                    currentCmsEntry.status,
                    // Set the next CMS entry based on the current one
                    // TODO: Should be using the server-side clock here
                    CmsEntry.DocumentsWorkItem.getLeaseExpiry(getNowMs(), currentCmsEntry.numAttempts + 1),
                    currentCmsEntry.numAttempts + 1
                );
                members.cmsWorkEntry = members.cmsClient.updateDocumentsWorkItem(updatedEntry, currentCmsEntry);
                leasedEntry = members.cmsWorkEntry.map(bar -> (CmsEntry.Base) bar);
            } else {
                throw new IllegalStateException("Unexpected CMS entry type: " + entry.getClass().getName());
            }

            if (leasedEntry.isPresent()) {
                logger.info("Lease acquired");
            } else {
                logger.info("Failed to acquire lease");
            }
        }

        @Override
        public WorkerStep nextStep() {
            // Do work if we acquired the lease; otherwise, circle back to the beginning after a backoff
            if (leasedEntry.isPresent()) {
                if (leasedEntry.get() instanceof CmsEntry.Documents) {
                    return new SetupDocumentsWorkEntries(members);
                } else if (entry instanceof CmsEntry.DocumentsWorkItem) {
                    return new MigrateDocuments(members);
                } else {
                    throw new IllegalStateException("Unexpected CMS entry type: " + entry.getClass().getName());
                }                
            } else {
                return new RandomWait(members);
            }
        }
    }

    public static class SetupDocumentsWorkEntries extends Base {

        public SetupDocumentsWorkEntries(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            CmsEntry.Documents leasedCmsEntry = members.getCmsEntryNotMissing();

            logger.info("Setting the worker's current work item to be creating the documents work entries...");
            members.globalState.updateWorkItem(new OpenSearchWorkItem(OpenSearchCmsClient.CMS_INDEX_NAME, OpenSearchCmsClient.CMS_DOCUMENTS_DOC_ID));
            logger.info("Work item set");

            logger.info("Setting up the Documents Work Items...");
            SnapshotRepo.Provider repoDataProvider = members.metadataFactory.getRepoDataProvider();
            for (SnapshotRepo.Index index : repoDataProvider.getIndicesInSnapshot(members.snapshotName)) {
                IndexMetadata.Data indexMetadata = members.metadataFactory.fromRepo(members.snapshotName, index.getName());
                logger.info("Index " + indexMetadata.getName() + " has " + indexMetadata.getNumberOfShards() + " shards");
                for (int shardId = 0; shardId < indexMetadata.getNumberOfShards(); shardId++) {
                    logger.info("Creating Documents Work Item for index: " + indexMetadata.getName() + ", shard: " + shardId);
                    members.cmsClient.createDocumentsWorkItem(indexMetadata.getName(), shardId);
                }
            }
            logger.info("Finished setting up the Documents Work Items.");

            logger.info("Updating the Documents Migration entry to indicate setup has been completed...");
            CmsEntry.Documents updatedEntry = new CmsEntry.Documents(
                CmsEntry.DocumentsStatus.IN_PROGRESS,
                leasedCmsEntry.leaseExpiry,
                leasedCmsEntry.numAttempts
            );

            members.cmsEntry = members.cmsClient.updateDocumentsEntry(updatedEntry, leasedCmsEntry);
            logger.info("Documents Migration entry updated");

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
                logger.warn("Completed creating the documents work entries but failed to update the Documents Migration entry; retrying...");
                return new GetEntry(members);
            }

            return new GetDocumentsToMigrate(members);
        }
    }

    public static class GetDocumentsToMigrate extends Base {

        public GetDocumentsToMigrate(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            logger.info("Seeing if there are any docs left to migration according to the CMS...");
            members.cmsWorkEntry = members.cmsClient.getAvailableDocumentsWorkItem();
            members.cmsWorkEntry.ifPresentOrElse(
                (item) -> logger.info("Found some docs to migrate"),
                () -> logger.info("No docs found to migrate")
            );
        }

        @Override
        public WorkerStep nextStep() {
            // No work left to do
            if (members.cmsWorkEntry.isEmpty()) {
                return new ExitPhaseSuccess(members);
            }
            return new MigrateDocuments(members);
        }
    }

    public static class MigrateDocuments extends Base {

        public MigrateDocuments(SharedMembers members) {
            super(members);
        }

        @Override
        public void run() {
            CmsEntry.DocumentsWorkItem workItem = members.getCmsWorkEntryNotMissing();

            /*
            * Try to migrate the documents.  We should have a unique lease on the entry that guarantees that we're the only
            * one working on it.  However, we apply some care to ensure that even if that's not the case, something fairly 
            * reasonable happens.
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
            * fails because .
            */
            if (workItem.numAttempts > CmsEntry.DocumentsWorkItem.MAX_ATTEMPTS) {
                logger.warn("Documents Work Item (Index: " + workItem.indexName + ", Shard: " + workItem.shardId + ") has exceeded the maximum number of attempts; marking it as failed...");
                CmsEntry.DocumentsWorkItem updatedEntry = new CmsEntry.DocumentsWorkItem(
                    workItem.indexName,
                    workItem.shardId,
                    CmsEntry.DocumentsWorkItemStatus.FAILED,
                    workItem.leaseExpiry,
                    workItem.numAttempts
                );

                // We use optimistic locking here in the unlikely event someone else is working on this task despite the
                // leasing system and managed to complete the task; in that case we want this update to bounce.
                members.cmsWorkEntry = members.cmsClient.updateDocumentsWorkItem(updatedEntry, workItem);
                members.cmsWorkEntry.ifPresentOrElse(
                    value -> logger.info("Documents Work Item (Index: " + workItem.indexName + ", Shard: " + workItem.shardId + ") marked as failed"),
                    () ->logger.warn("Unable to mark Documents Work Item (Index: " + workItem.indexName + ", Shard: " + workItem.shardId + ") as failed")
                );
                return;
            }

            logger.info("Setting the worker's current work item to be migrating the docs...");
            members.globalState.updateWorkItem(new OpenSearchWorkItem(
                OpenSearchCmsClient.CMS_INDEX_NAME,
                OpenSearchCmsClient.getDocumentsWorkItemDocId(workItem.indexName, workItem.shardId)
            ));
            logger.info("Work item set");

            ShardMetadata.Data shardMetadata = null;
            try {
                logger.info("Migrating docs: Index " + workItem.indexName + ", Shard " + workItem.shardId);
                shardMetadata = members.shardMetadataFactory.fromRepo(members.snapshotName, workItem.indexName, workItem.shardId);

                logger.info("Shard size: " + shardMetadata.getTotalSizeBytes());
                if (shardMetadata.getTotalSizeBytes() > members.maxShardSizeBytes) {
                    throw new ShardTooLarge(shardMetadata.getTotalSizeBytes(), members.maxShardSizeBytes);
                }

                members.unpacker.unpack(shardMetadata);
                
                Flux<Document> documents = members.reader.readDocuments(shardMetadata.getIndexName(), shardMetadata.getShardId());

                final ShardMetadata.Data finalShardMetadata = shardMetadata; // Define in local context for the lambda
                members.reindexer.reindex(shardMetadata.getIndexName(), documents)
                    .doOnError(error -> logger.error("Error during reindexing: " + error))
                    .doOnSuccess(done -> logger.info("Reindexing completed for Index " + finalShardMetadata.getIndexName() + ", Shard " + finalShardMetadata.getShardId()))
                    // Wait for the reindexing to complete before proceeding
                    .block();
                logger.info("Docs migrated");

                logger.info("Updating the Documents Work Item to indicate it has been completed...");           
                CmsEntry.DocumentsWorkItem updatedEntry = new CmsEntry.DocumentsWorkItem(
                    workItem.indexName,
                    workItem.shardId,
                    CmsEntry.DocumentsWorkItemStatus.COMPLETED,
                    workItem.leaseExpiry,
                    workItem.numAttempts
                );
                
                members.cmsWorkEntry = Optional.of(members.cmsClient.updateDocumentsWorkItemForceful(updatedEntry));
                logger.info("Documents Work Item updated");
            } catch (Exception e) {
                logger.info("Failed to documents: Index " + workItem.indexName + ", Shard " + workItem.shardId, e);
                logger.info("Updating the Documents Work Item with incremented attempt count...");
                CmsEntry.DocumentsWorkItem updatedEntry = new CmsEntry.DocumentsWorkItem(
                    workItem.indexName,
                    workItem.shardId,
                    workItem.status,
                    workItem.leaseExpiry,
                    workItem.numAttempts + 1
                );

                // We use optimistic locking here in the unlikely event someone else is working on this task despite the
                // leasing system and managed to complete the task; in that case we want this update to bounce.
                members.cmsWorkEntry = members.cmsClient.updateDocumentsWorkItem(updatedEntry, workItem);
                members.cmsWorkEntry.ifPresentOrElse(
                    value -> logger.info("Documents Work Item (Index: " + workItem.indexName + ", Shard: " + workItem.shardId + ") attempt count was incremented"),
                    () ->logger.info("Unable to increment attempt count of Documents Work Item (Index: " + workItem.indexName + ", Shard: " + workItem.shardId + ")")
                );
            } finally {
                if (shardMetadata != null) {
                    logger.info("Cleaning up the unpacked shard...");
                    members.unpacker.cleanUp(shardMetadata);
                    logger.info("Shard cleaned up");
                }
            }

            logger.info("Clearing the worker's current work item...");
            members.globalState.updateWorkItem(null);
            logger.info("Work item cleared");
        }

        @Override
        public WorkerStep nextStep() {
            return new GetDocumentsToMigrate(members);
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
                throw new DocumentsMigrationFailed("Interrupted");
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
            logger.info("Marking the Documents Migration as completed...");
            CmsEntry.Documents lastCmsEntry = members.getCmsEntryNotMissing();
            CmsEntry.Documents updatedEntry = new CmsEntry.Documents(
                CmsEntry.DocumentsStatus.COMPLETED,
                lastCmsEntry.leaseExpiry,
                lastCmsEntry.numAttempts
            );
            members.cmsClient.updateDocumentsEntry(updatedEntry, lastCmsEntry);
            logger.info("Documents Migration marked as completed");

            logger.info("Documents Migration completed, exiting Documents Phase...");
            members.globalState.updatePhase(GlobalState.Phase.DOCUMENTS_COMPLETED);
        }

        @Override
        public WorkerStep nextStep() {
            return null;
        }
    }

    public static class ExitPhaseFailed extends Base {
        protected final DocumentsMigrationFailed e;

        public ExitPhaseFailed(SharedMembers members,  DocumentsMigrationFailed e) {
            super(members);
            this.e = e;
        }

        @Override
        public void run() {
            // We either failed the Documents Migration or found it had already been failed; either way this
            // should not be missing
            CmsEntry.Documents lastCmsEntry = members.getCmsEntryNotMissing();

            logger.error("Documents Migration failed");
            CmsEntry.Documents updatedEntry = new CmsEntry.Documents(
                CmsEntry.DocumentsStatus.FAILED,
                lastCmsEntry.leaseExpiry,
                lastCmsEntry.numAttempts
            );
            members.cmsClient.updateDocumentsEntry(updatedEntry, lastCmsEntry);
            members.globalState.updatePhase(GlobalState.Phase.DOCUMENTS_FAILED);
        }

        @Override
        public WorkerStep nextStep() {
            throw e;
        }
    }

    public static class ShardTooLarge extends RfsException {
        public ShardTooLarge(long shardSizeBytes, long maxShardSize) {
            super("The shard size of " + shardSizeBytes + " bytes exceeds the maximum shard size of " + maxShardSize + " bytes");
        }
    }

    public static class DocumentsMigrationFailed extends RfsException {
        public DocumentsMigrationFailed(String message) {
            super("The Documents Migration has failed.  Reason: " + message);
        }
    }

    public static class MissingDocumentsEntry extends RfsException {
        public MissingDocumentsEntry() {
            super("The Documents Migration CMS entry we expected to be stored in local memory was null."
                + "  This should never happen."
            );
        }
    }

    public static class FoundFailedDocumentsMigration extends DocumentsMigrationFailed {
        public FoundFailedDocumentsMigration() {
            super("We checked the status in the CMS and found it had failed.  Aborting.");
        }
    }

    public static class MaxAttemptsExceeded extends DocumentsMigrationFailed {
        public MaxAttemptsExceeded() {
            super("We reached the limit of " + CmsEntry.Documents.MAX_ATTEMPTS + " attempts to complete the Documents Migration");
        }
    }
    
}
