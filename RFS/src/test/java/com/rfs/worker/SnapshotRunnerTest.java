package com.rfs.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.rfs.cms.CmsClient;
import com.rfs.common.RfsException;
import com.rfs.common.SnapshotCreator;

class SnapshotRunnerTest {

    @Test
    void run_encountersAnException_asExpected() {
        // Setup
        String snapshotName = "snapshotName";
        GlobalState globalState = mock(GlobalState.class);
        CmsClient cmsClient = mock(CmsClient.class);
        SnapshotCreator snapshotCreator = mock(SnapshotCreator.class);
        SnapshotRunner testRunner = new SnapshotRunner(globalState, cmsClient, snapshotCreator);
        RfsException testException = new RfsException("Unit test");

        doThrow(testException).when(cmsClient).getSnapshotEntry(snapshotName);
        when(globalState.getPhase()).thenReturn(GlobalState.Phase.SNAPSHOT_IN_PROGRESS);
        when(snapshotCreator.getSnapshotName()).thenReturn(snapshotName);

        // Run the test
        try {
            testRunner.run();
        } catch (SnapshotRunner.SnapshotPhaseFailed e) {
            assertEquals(GlobalState.Phase.SNAPSHOT_IN_PROGRESS, e.phase);
            assertEquals(null, e.nextStep);
            assertEquals(Optional.empty(), e.cmsEntry);
            assertEquals(testException, e.e);

        } catch (Exception e) {
            fail("Unexpected exception thrown: " + e.getClass().getName());
        }
    }
    
}
