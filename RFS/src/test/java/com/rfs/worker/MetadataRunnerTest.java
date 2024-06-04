package com.rfs.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.rfs.cms.CmsClient;
import com.rfs.common.GlobalMetadata;
import com.rfs.common.RfsException;
import com.rfs.common.SnapshotCreator;
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


        MetadataRunner testRunner = new MetadataRunner(globalState, cmsClient, snapshotName, metadataFactory, metadataCreator, transformer);

        // Run the test
        try {
            testRunner.run();
        } catch (MetadataRunner.MetadataMigrationPhaseFailed e) {
            assertEquals(GlobalState.Phase.METADATA_IN_PROGRESS, e.phase);
            assertEquals(null, e.nextStep);
            assertEquals(Optional.empty(), e.cmsEntry);
            assertEquals(testException, e.e);

        } catch (Exception e) {
            fail("Unexpected exception thrown: " + e.getClass().getName());
        }
    }
    
}