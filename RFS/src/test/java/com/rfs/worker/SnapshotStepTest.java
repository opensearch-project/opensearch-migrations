package com.rfs.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.BeforeEach;
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
import com.rfs.worker.SnapshotStep.SharedMembers;
import com.rfs.worker.SnapshotStep.WaitForSnapshot;

import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.stream.Stream;


@ExtendWith(MockitoExtension.class)
public class SnapshotStepTest {
    private SharedMembers testMembers;

    @BeforeEach
    void setUp() {
        GlobalState globalState = Mockito.mock(GlobalState.class);
        CmsClient cmsClient = Mockito.mock(CmsClient.class);
        SnapshotCreator snapshotCreator = Mockito.mock(SnapshotCreator.class);
        testMembers = new SharedMembers(globalState, cmsClient, snapshotCreator);
    }

    static Stream<Arguments > provideEnterPhaseArgs() {
        return Stream.of(
            Arguments.of(Optional.empty(), SnapshotStep.CreateEntry.class),
            Arguments.of(Optional.of(new Snapshot("test", SnapshotStatus.NOT_STARTED)), SnapshotStep.InitiateSnapshot.class),
            Arguments.of(Optional.of(new Snapshot("test", SnapshotStatus.IN_PROGRESS)), SnapshotStep.WaitForSnapshot.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideEnterPhaseArgs")
    void EnterPhase_AsExpected(Optional<Snapshot> snapshotEntry, Class<?> expected) {
        // Run the test
        SnapshotStep.EnterPhase testStep = new SnapshotStep.EnterPhase(testMembers, snapshotEntry);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(GlobalState.Phase.SNAPSHOT_IN_PROGRESS);
        assertEquals(expected, nextStep.getClass());
    }

    @Test
    void CreateEntry_AsExpected() {
        // Set up the test
        when(testMembers.snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotStep.CreateEntry testStep = new SnapshotStep.CreateEntry(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).createSnapshotEntry("test");
        assertEquals(SnapshotStep.InitiateSnapshot.class, nextStep.getClass());
    }

    @Test
    void InitiateSnapshot_AsExpected() {
        // Set up the test
        when(testMembers.snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotStep.InitiateSnapshot testStep = new SnapshotStep.InitiateSnapshot(testMembers);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(testMembers.snapshotCreator, times(1)).registerRepo();
        Mockito.verify(testMembers.snapshotCreator, times(1)).createSnapshot();
        Mockito.verify(testMembers.cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.IN_PROGRESS);
        assertEquals(SnapshotStep.WaitForSnapshot.class, nextStep.getClass());
    }

    public static class TestableWaitForSnapshot extends WaitForSnapshot {
        public TestableWaitForSnapshot(SharedMembers testMembers) {
            super(testMembers);
        }

        protected void waitABit() throws InterruptedException {
            // Do nothing
        }
    }

    @Test
    void WaitForSnapshot_successful_AsExpected() {
        // Set up the test
        when(testMembers.snapshotCreator.isSnapshotFinished())
            .thenReturn(false)
            .thenReturn(true);

        // Run the test
        SnapshotStep.WaitForSnapshot waitPhase = new TestableWaitForSnapshot(testMembers);
        waitPhase.run();
        WorkerStep nextStep = waitPhase.nextStep();

        // Check the results
        Mockito.verify(testMembers.snapshotCreator, times(2)).isSnapshotFinished();
        assertEquals(SnapshotStep.ExitPhaseSuccess.class, nextStep.getClass());
    }

    @Test
    void WaitForSnapshot_failedSnapshot_AsExpected() {
        // Set up the test
        when(testMembers.snapshotCreator.isSnapshotFinished())
            .thenReturn(false)
            .thenThrow(new SnapshotCreationFailed("test"));

        // Run the test
        SnapshotStep.WaitForSnapshot waitPhase = new TestableWaitForSnapshot(testMembers);
        waitPhase.run();
        WorkerStep nextStep = waitPhase.nextStep();

        // Check the results
        Mockito.verify(testMembers.snapshotCreator, times(2)).isSnapshotFinished();
        assertEquals(SnapshotStep.ExitPhaseSnapshotFailed.class, nextStep.getClass());
    }

    @Test
    void ExitPhaseSuccess_AsExpected() {
        // Set up the test
        when(testMembers.snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotStep.ExitPhaseSuccess exitPhase = new ExitPhaseSuccess(testMembers);
        exitPhase.run();
        WorkerStep nextStep = exitPhase.nextStep();

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.COMPLETED);
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(GlobalState.Phase.SNAPSHOT_COMPLETED);
        assertEquals(null, nextStep);
    }

    @Test
    void ExitPhaseSnapshotFailed_AsExpected() {
        // Set up the test
        when(testMembers.snapshotCreator.getSnapshotName()).thenReturn("test");
        SnapshotCreationFailed e = new SnapshotCreationFailed("test");

        // Run the test
        SnapshotStep.ExitPhaseSnapshotFailed exitPhase = new ExitPhaseSnapshotFailed(testMembers, e);
        exitPhase.run();
        assertThrows(SnapshotCreationFailed.class, () -> {
            exitPhase.nextStep();
        });

        // Check the results
        Mockito.verify(testMembers.cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.FAILED);
        Mockito.verify(testMembers.globalState, times(1)).updatePhase(GlobalState.Phase.SNAPSHOT_FAILED);
    }    
}
