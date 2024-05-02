package com.rfs.worker;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry.Snapshot;
import com.rfs.cms.CmsEntry.SnapshotStatus;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.SnapshotCreator.SnapshotCreationFailed;
import com.rfs.worker.SnapshotState.ExitPhaseSnapshotFailed;
import com.rfs.worker.SnapshotState.ExitPhaseSuccess;
import com.rfs.worker.SnapshotState.WaitForSnapshot;

import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
public class SnapshotStateTest {

    @Test
    void EnterPhase_run_AsExpected() {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        Snapshot snapshotEntry = Mockito.mock(Snapshot.class);

        // Run the test
        SnapshotState.EnterPhase enterPhase = new SnapshotState.EnterPhase(globalState, cmsClient, snapshotCreator, snapshotEntry);
        enterPhase.run();

        // Check the results
        Mockito.verify(globalState, times(1)).updatePhase(GlobalData.Phase.SNAPSHOT_IN_PROGRESS);
    }

    static Stream<Arguments> provideEnterPhaseNextArgs() {
        return Stream.of(
            Arguments.of(null, SnapshotState.CreateEntry.class),
            Arguments.of(new Snapshot("test", SnapshotStatus.NOT_STARTED), SnapshotState.InitiateSnapshot.class),
            Arguments.of(new Snapshot("test", SnapshotStatus.IN_PROGRESS), SnapshotState.WaitForSnapshot.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideEnterPhaseNextArgs")
    void EnterPhase_nextState_AsExpected(Snapshot snapshotEntry, Class<?> expected) {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);

        // Run the test
        SnapshotState.EnterPhase enterPhase = new SnapshotState.EnterPhase(globalState, cmsClient, snapshotCreator, snapshotEntry);
        WorkerState nextState = enterPhase.nextState();

        // Check the results
        assertEquals(expected, nextState.getClass());
    }

    @Test
    void CreateEntry_run_AsExpected() {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotState.CreateEntry createPhase = new SnapshotState.CreateEntry(globalState, cmsClient, snapshotCreator);
        createPhase.run();

        // Check the results
        Mockito.verify(cmsClient, times(1)).createSnapshotEntry("test");
    }

    @Test
    void CreateEntry_nextState_AsExpected() {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);

        // Run the test
        SnapshotState.CreateEntry createPhase = new SnapshotState.CreateEntry(globalState, cmsClient, snapshotCreator);
        WorkerState nextState = createPhase.nextState();

        // Check the results
        assertEquals(SnapshotState.InitiateSnapshot.class, nextState.getClass());
    }

    @Test
    void InitiateSnapshot_run_AsExpected() {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotState.InitiateSnapshot initiatePhase = new SnapshotState.InitiateSnapshot(globalState, cmsClient, snapshotCreator);
        initiatePhase.run();

        // Check the results
        Mockito.verify(snapshotCreator, times(1)).registerRepo();
        Mockito.verify(snapshotCreator, times(1)).createSnapshot();
        Mockito.verify(cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.IN_PROGRESS);
    }

    @Test
    void InitiateSnapshot_nextState_AsExpected() {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);

        // Run the test
        SnapshotState.InitiateSnapshot initiatePhase = new SnapshotState.InitiateSnapshot(globalState, cmsClient, snapshotCreator);
        WorkerState nextState = initiatePhase.nextState();

        // Check the results
        assertEquals(SnapshotState.WaitForSnapshot.class, nextState.getClass());
    }

    public static class TestableWaitForSnapshot extends WaitForSnapshot {
        public TestableWaitForSnapshot(GlobalData globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        protected void waitABit() throws InterruptedException {
            // Do nothing
        }
    }

    @Test
    void WaitForSnapshot_successful_AsExpected() {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.isSnapshotFinished())
            .thenReturn(false)
            .thenReturn(true);

        // Run the test
        SnapshotState.WaitForSnapshot waitPhase = new TestableWaitForSnapshot(globalState, cmsClient, snapshotCreator);
        waitPhase.run();
        WorkerState nextState = waitPhase.nextState();

        // Check the results
        Mockito.verify(snapshotCreator, times(2)).isSnapshotFinished();
        assertEquals(SnapshotState.ExitPhaseSuccess.class, nextState.getClass());
    }

    @Test
    void WaitForSnapshot_failedSnapshot_AsExpected() {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.isSnapshotFinished())
            .thenReturn(false)
            .thenThrow(new SnapshotCreationFailed("test"));

        // Run the test
        SnapshotState.WaitForSnapshot waitPhase = new TestableWaitForSnapshot(globalState, cmsClient, snapshotCreator);
        waitPhase.run();
        WorkerState nextState = waitPhase.nextState();

        // Check the results
        Mockito.verify(snapshotCreator, times(2)).isSnapshotFinished();
        assertEquals(SnapshotState.ExitPhaseSnapshotFailed.class, nextState.getClass());
    }

    @Test
    void ExitPhaseSuccess_AsExpected() {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotState.ExitPhaseSuccess exitPhase = new ExitPhaseSuccess(globalState, cmsClient, snapshotCreator);
        exitPhase.run();
        WorkerState nextState = exitPhase.nextState();

        // Check the results
        Mockito.verify(cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.COMPLETED);
        Mockito.verify(globalState, times(1)).updatePhase(GlobalData.Phase.SNAPSHOT_COMPLETED);
        assertEquals(null, nextState);
    }

    @Test
    void ExitPhaseSnapshotFailed_AsExpected() {
        // Set up the test
        GlobalData globalState = Mockito.mock(GlobalData.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.getSnapshotName()).thenReturn("test");
        SnapshotCreationFailed e = new SnapshotCreationFailed("test");

        // Run the test
        SnapshotState.ExitPhaseSnapshotFailed exitPhase = new ExitPhaseSnapshotFailed(globalState, cmsClient, snapshotCreator, e);
        assertThrows(SnapshotCreationFailed.class, () -> {
            exitPhase.run();
        });

        // Check the results
        Mockito.verify(cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.FAILED);
        Mockito.verify(globalState, times(1)).updatePhase(GlobalData.Phase.SNAPSHOT_FAILED);
    }
    
}
