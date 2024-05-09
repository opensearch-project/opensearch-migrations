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
import com.rfs.worker.SnapshotStep.ExitPhaseSnapshotFailed;
import com.rfs.worker.SnapshotStep.ExitPhaseSuccess;
import com.rfs.worker.SnapshotStep.WaitForSnapshot;

import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
public class SnapshotStepTest {

    @Test
    void EnterPhase_run_AsExpected() {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        Snapshot snapshotEntry = Mockito.mock(Snapshot.class);

        // Run the test
        SnapshotStep.EnterPhase enterPhase = new SnapshotStep.EnterPhase(globalState, cmsClient, snapshotCreator, snapshotEntry);
        enterPhase.run();

        // Check the results
        Mockito.verify(globalState, times(1)).updatePhase(GlobalState.Phase.SNAPSHOT_IN_PROGRESS);
    }

    static Stream<Arguments> provideEnterPhaseNextArgs() {
        return Stream.of(
            Arguments.of(null, SnapshotStep.CreateEntry.class),
            Arguments.of(new Snapshot("test", SnapshotStatus.NOT_STARTED), SnapshotStep.InitiateSnapshot.class),
            Arguments.of(new Snapshot("test", SnapshotStatus.IN_PROGRESS), SnapshotStep.WaitForSnapshot.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideEnterPhaseNextArgs")
    void EnterPhase_nextState_AsExpected(Snapshot snapshotEntry, Class<?> expected) {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);

        // Run the test
        SnapshotStep.EnterPhase enterPhase = new SnapshotStep.EnterPhase(globalState, cmsClient, snapshotCreator, snapshotEntry);
        WorkerStep nextState = enterPhase.nextStep();

        // Check the results
        assertEquals(expected, nextState.getClass());
    }

    @Test
    void CreateEntry_run_AsExpected() {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotStep.CreateEntry createPhase = new SnapshotStep.CreateEntry(globalState, cmsClient, snapshotCreator);
        createPhase.run();

        // Check the results
        Mockito.verify(cmsClient, times(1)).createSnapshotEntry("test");
    }

    @Test
    void CreateEntry_nextState_AsExpected() {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);

        // Run the test
        SnapshotStep.CreateEntry createPhase = new SnapshotStep.CreateEntry(globalState, cmsClient, snapshotCreator);
        WorkerStep nextState = createPhase.nextStep();

        // Check the results
        assertEquals(SnapshotStep.InitiateSnapshot.class, nextState.getClass());
    }

    @Test
    void InitiateSnapshot_run_AsExpected() {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotStep.InitiateSnapshot initiatePhase = new SnapshotStep.InitiateSnapshot(globalState, cmsClient, snapshotCreator);
        initiatePhase.run();

        // Check the results
        Mockito.verify(snapshotCreator, times(1)).registerRepo();
        Mockito.verify(snapshotCreator, times(1)).createSnapshot();
        Mockito.verify(cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.IN_PROGRESS);
    }

    @Test
    void InitiateSnapshot_nextState_AsExpected() {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);

        // Run the test
        SnapshotStep.InitiateSnapshot initiatePhase = new SnapshotStep.InitiateSnapshot(globalState, cmsClient, snapshotCreator);
        WorkerStep nextState = initiatePhase.nextStep();

        // Check the results
        assertEquals(SnapshotStep.WaitForSnapshot.class, nextState.getClass());
    }

    public static class TestableWaitForSnapshot extends WaitForSnapshot {
        public TestableWaitForSnapshot(GlobalState globalState, CmsClient cmsClient, SnapshotCreator snapshotCreator) {
            super(globalState, cmsClient, snapshotCreator);
        }

        protected void waitABit() throws InterruptedException {
            // Do nothing
        }
    }

    @Test
    void WaitForSnapshot_successful_AsExpected() {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.isSnapshotFinished())
            .thenReturn(false)
            .thenReturn(true);

        // Run the test
        SnapshotStep.WaitForSnapshot waitPhase = new TestableWaitForSnapshot(globalState, cmsClient, snapshotCreator);
        waitPhase.run();
        WorkerStep nextState = waitPhase.nextStep();

        // Check the results
        Mockito.verify(snapshotCreator, times(2)).isSnapshotFinished();
        assertEquals(SnapshotStep.ExitPhaseSuccess.class, nextState.getClass());
    }

    @Test
    void WaitForSnapshot_failedSnapshot_AsExpected() {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.isSnapshotFinished())
            .thenReturn(false)
            .thenThrow(new SnapshotCreationFailed("test"));

        // Run the test
        SnapshotStep.WaitForSnapshot waitPhase = new TestableWaitForSnapshot(globalState, cmsClient, snapshotCreator);
        waitPhase.run();
        WorkerStep nextState = waitPhase.nextStep();

        // Check the results
        Mockito.verify(snapshotCreator, times(2)).isSnapshotFinished();
        assertEquals(SnapshotStep.ExitPhaseSnapshotFailed.class, nextState.getClass());
    }

    @Test
    void ExitPhaseSuccess_AsExpected() {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotStep.ExitPhaseSuccess exitPhase = new ExitPhaseSuccess(globalState, cmsClient, snapshotCreator);
        exitPhase.run();
        WorkerStep nextState = exitPhase.nextStep();

        // Check the results
        Mockito.verify(cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.COMPLETED);
        Mockito.verify(globalState, times(1)).updatePhase(GlobalState.Phase.SNAPSHOT_COMPLETED);
        assertEquals(null, nextState);
    }

    @Test
    void ExitPhaseSnapshotFailed_AsExpected() {
        // Set up the test
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        when(snapshotCreator.getSnapshotName()).thenReturn("test");
        SnapshotCreationFailed e = new SnapshotCreationFailed("test");

        // Run the test
        SnapshotStep.ExitPhaseSnapshotFailed exitPhase = new ExitPhaseSnapshotFailed(globalState, cmsClient, snapshotCreator, e);
        assertThrows(SnapshotCreationFailed.class, () -> {
            exitPhase.run();
        });

        // Check the results
        Mockito.verify(cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.FAILED);
        Mockito.verify(globalState, times(1)).updatePhase(GlobalState.Phase.SNAPSHOT_FAILED);
    }
    
}
