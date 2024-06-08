package com.rfs.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.rfs.cms.CmsClient;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.IndexMetadata;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.RfsException;
import com.rfs.common.ShardMetadata;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;

public class DocumentsRunnerTest {

    @Test
    void run_encountersAnException_asExpected() {
        // Setup
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        String snapshotName = "testSnapshot";
        
        IndexMetadata.Factory metadataFactory = Mockito.mock(IndexMetadata.Factory.class);
        ShardMetadata.Factory shardMetadataFactory = Mockito.mock(ShardMetadata.Factory.class);
        SnapshotShardUnpacker unpacker = Mockito.mock(SnapshotShardUnpacker.class);
        LuceneDocumentsReader reader = Mockito.mock(LuceneDocumentsReader.class);
        DocumentReindexer reindexer = Mockito.mock(DocumentReindexer.class);
        RfsException testException = new RfsException("Unit test");

        doThrow(testException).when(cmsClient).getDocumentsEntry();
        when(globalState.getPhase()).thenReturn(GlobalState.Phase.DOCUMENTS_IN_PROGRESS);        

        // Run the test
        DocumentsRunner testRunner = new DocumentsRunner(globalState, cmsClient, snapshotName, metadataFactory, shardMetadataFactory, unpacker, reader, reindexer);
        final var e = assertThrows(DocumentsRunner.DocumentsMigrationPhaseFailed.class, () -> testRunner.run());

        // Verify the results
        assertEquals(GlobalState.Phase.DOCUMENTS_IN_PROGRESS, e.phase);
        assertEquals(DocumentsStep.GetEntry.class, e.nextStep.getClass());
        assertEquals(Optional.empty(), e.cmsEntry);
        assertEquals(testException, e.e);
    }    
}
