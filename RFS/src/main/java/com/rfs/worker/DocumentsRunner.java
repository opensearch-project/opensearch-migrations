package com.rfs.worker;

import java.util.Optional;

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

    private final DocumentsStep.SharedMembers members;

    public DocumentsRunner(GlobalState globalState, CmsClient cmsClient, String snapshotName, IndexMetadata.Factory metadataFactory,
                ShardMetadata.Factory shardMetadataFactory, SnapshotShardUnpacker unpacker, LuceneDocumentsReader reader,
                DocumentReindexer reindexer) {
        this.members = new DocumentsStep.SharedMembers(globalState, cmsClient, snapshotName, metadataFactory, shardMetadataFactory, unpacker, reader, reindexer);
    }

    @Override
    public void runInternal() {
        WorkerStep nextStep = null;
        try {
            nextStep = new DocumentsStep.EnterPhase(members);

            while (nextStep != null) {
                nextStep.run();
                nextStep = nextStep.nextStep();
            }
        } catch (Exception e) {
            throw new DocumentsMigrationPhaseFailed(
                members.globalState.getPhase(), 
                nextStep, 
                members.cmsEntry.map(bar -> (CmsEntry.Base) bar), 
                e
            );
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
            super("Documents Migration Phase failed", phase, nextStep, cmsEntry, e);
        }
    }  
    
}
