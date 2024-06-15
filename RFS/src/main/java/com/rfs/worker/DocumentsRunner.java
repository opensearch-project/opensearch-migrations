package com.rfs.worker;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.rfs.cms.IWorkCoordinator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.IndexMetadata;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.ShardMetadata;
import com.rfs.common.SnapshotShardUnpacker;

public class DocumentsRunner implements Runner {
    private static final Logger logger = LogManager.getLogger(DocumentsRunner.class);
    public static final String ALL_INDEX_MANIFEST = "all_index_manifest";

    IWorkCoordinator workCoordinator;
    Instant expirationTime;

    public DocumentsRunner(GlobalState globalState, IWorkCoordinator workCoordinator,
                           CmsClient cmsClient, String snapshotName, IndexMetadata.Factory metadataFactory,
                ShardMetadata.Factory shardMetadataFactory, SnapshotShardUnpacker unpacker, LuceneDocumentsReader reader,
                DocumentReindexer reindexer) {
        this.workCoordinator = workCoordinator;
        setupDocumentWorkItemsIfNecessary(metadataFactory);
        //this.members = new DocumentsStep.SharedMembers(globalState, cmsClient, snapshotName, metadataFactory, shardMetadataFactory, unpacker, reader, reindexer);
    }

    @Override
    public void runInternal() throws IOException {
        var workItem = workCoordinator.acquireNextWorkItem(Duration.ofMinutes(10));
        if (workItem == null) {
            return;
        }
        this.expirationTime = workItem.getLeaseExpirationTime();

    }

    private void setupKill(IWorkCoordinator.WorkItemAndDuration workItem) {

    }

    private void setupDocumentWorkItemsIfNecessary(IndexMetadata.Factory metadataFactory) {
        try {
            var workItem = workCoordinator.createOrUpdateLeaseForWorkItem(ALL_INDEX_MANIFEST, Duration.ofMinutes(5));
            metadataFactory.fromRepo()
            workCoordinator.completeWorkItem(ALL_INDEX_MANIFEST);
        } catch (IWorkCoordinator.LeaseNotAcquiredException | IOException e) {

        }
    }

    @Override
    public String getPhaseName() {
        return "Documents Migration";
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    public static class DocumentsMigrationPhaseFailed extends Runner.PhaseFailed {
        public DocumentsMigrationPhaseFailed(GlobalState.Phase phase, WorkerStep nextStep, Optional<CmsEntry.Base> cmsEntry, Exception e) {
            super("Documents Migration Phase failed", phase, cmsEntry, e);
        }
    }  
    
}
