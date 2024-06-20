package com.rfs.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.rfs.cms.CmsClient;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.RfsException;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.models.IndexMetadata;
import com.rfs.models.ShardMetadata;

public class DocumentsRunnerTest {

    @Test
    void run_encountersAnException_asExpected() {
        // Setup
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        String snapshotName = "testSnapshot";
        long maxShardSizeBytes = 50 * 1024 * 1024 * 1024L;
        
        IndexMetadata.Factory metadataFactory = Mockito.mock(IndexMetadata.Factory.class);
        ShardMetadata.Factory shardMetadataFactory = Mockito.mock(ShardMetadata.Factory.class);
        SnapshotShardUnpacker.Factory unpackerFactory = Mockito.mock(SnapshotShardUnpacker.Factory.class);
        LuceneDocumentsReader reader = Mockito.mock(LuceneDocumentsReader.class);
        DocumentReindexer reindexer = Mockito.mock(DocumentReindexer.class);
        RfsException testException = new RfsException("Unit test");

        doThrow(testException).when(cmsClient).getDocumentsEntry();
        when(globalState.getPhase()).thenReturn(GlobalState.Phase.DOCUMENTS_IN_PROGRESS);        

        // Run the test
        DocumentsRunner testRunner = new DocumentsRunner(globalState, cmsClient, snapshotName, maxShardSizeBytes, metadataFactory, shardMetadataFactory, unpackerFactory, reader, reindexer);
        final var e = assertThrows(DocumentsRunner.DocumentsMigrationPhaseFailed.class, () -> testRunner.run());

        // Verify the results
        assertEquals(GlobalState.Phase.DOCUMENTS_IN_PROGRESS, e.phase);
        assertEquals(DocumentsStep.GetEntry.class, e.nextStep.getClass());
        assertEquals(Optional.empty(), e.cmsEntry);
        assertEquals(testException, e.getCause());
    }    
}
