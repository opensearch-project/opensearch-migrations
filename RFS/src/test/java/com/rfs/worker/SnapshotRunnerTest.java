package com.rfs.worker;

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

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
        RfsException testException = new RfsException("Unit test");

        doThrow(testException).when(cmsClient).getSnapshotEntry(snapshotName);
        when(globalState.getPhase()).thenReturn(GlobalState.Phase.SNAPSHOT_IN_PROGRESS);
        when(snapshotCreator.getSnapshotName()).thenReturn(snapshotName);

        // Run the test
        final var e = assertThrows(Exception.class, () -> SnapshotRunner.runAndWaitForCompletion(snapshotCreator));
    }
}
