package com.rfs.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.rfs.cms.CmsClient;
import com.rfs.common.IndexMetadata;
import com.rfs.common.RfsException;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;

public class IndexRunnerTest {

    @Test
    void run_encountersAnException_asExpected() {
        // Setup
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        String snapshotName = "testSnapshot";
        IndexMetadata.Factory metadataFactory = Mockito.mock(IndexMetadata.Factory.class);
        IndexCreator_OS_2_11 creator = Mockito.mock(IndexCreator_OS_2_11.class);
        Transformer transformer = Mockito.mock(Transformer.class);
        RfsException testException = new RfsException("Unit test");

        doThrow(testException).when(cmsClient).getIndexEntry();
        when(globalState.getPhase()).thenReturn(GlobalState.Phase.INDEX_IN_PROGRESS);        

        // Run the test
        IndexRunner testRunner = new IndexRunner(globalState, cmsClient, snapshotName, metadataFactory, creator, transformer);
        final var e = assertThrows(IndexRunner.IndexMigrationPhaseFailed.class, () -> testRunner.run());

        // Verify the results
        assertEquals(GlobalState.Phase.INDEX_IN_PROGRESS, e.phase);
        assertEquals(IndexStep.GetEntry.class, e.nextStep.getClass());
        assertEquals(Optional.empty(), e.cmsEntry);
        assertEquals(testException, e.getCause());
    }    
}
