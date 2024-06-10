package com.rfs.worker;

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.rfs.cms.CmsClient;
import com.rfs.common.GlobalMetadata;
import com.rfs.common.RfsException;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;

class MetadataRunnerTest {

    @Test
    void run_encountersAnException_asExpected() {
        // Setup
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        String snapshotName = "testSnapshot";
        GlobalMetadata.Factory metadataFactory = Mockito.mock(GlobalMetadata.Factory.class);
        GlobalMetadataCreator_OS_2_11 metadataCreator = Mockito.mock(GlobalMetadataCreator_OS_2_11.class);
        Transformer transformer = Mockito.mock(Transformer.class);
        RfsException testException = new RfsException("Unit test");

        doThrow(testException).when(cmsClient).getMetadataEntry();
        when(globalState.getPhase()).thenReturn(GlobalState.Phase.METADATA_IN_PROGRESS);

        // Run the test
        MetadataRunner testRunner = new MetadataRunner(globalState, cmsClient, snapshotName, metadataFactory, metadataCreator, transformer);
        final var e = assertThrows(MetadataRunner.MetadataMigrationPhaseFailed.class, () -> testRunner.run());

        // Verify the results
        assertEquals(GlobalState.Phase.METADATA_IN_PROGRESS, e.phase);
        assertEquals(null, e.nextStep);
        assertEquals(Optional.empty(), e.cmsEntry);
        assertEquals(testException, e.getCause());
    }
    
}