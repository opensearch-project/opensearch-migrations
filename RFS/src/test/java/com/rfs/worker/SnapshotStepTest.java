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
import com.rfs.worker.SnapshotStep.WaitForSnapshot;

import static org.mockito.Mockito.*;

import java.util.stream.Stream;


@ExtendWith(MockitoExtension.class)
public class SnapshotStepTest {
    private GlobalState globalState;
    private CmsClient cmsClient;
    private SnapshotCreator snapshotCreator;

    @BeforeEach
    void setUp() {
        this.globalState = Mockito.mock(GlobalState.class);
        this.cmsClient = Mockito.mock(CmsClient.class);
        this.snapshotCreator = Mockito.mock(SnapshotCreator.class);
    }

    static Stream<Arguments > provideEnterPhaseArgs() {
        return Stream.of(
            Arguments.of(null, SnapshotStep.CreateEntry.class),
            Arguments.of(new Snapshot("test", SnapshotStatus.NOT_STARTED), SnapshotStep.InitiateSnapshot.class),
            Arguments.of(new Snapshot("test", SnapshotStatus.IN_PROGRESS), SnapshotStep.WaitForSnapshot.class)
        );
    }

    @ParameterizedTest
    @MethodSource("provideEnterPhaseArgs")
    void EnterPhase_AsExpected(Snapshot snapshotEntry, Class<?> expected) {
        // Run the test
        SnapshotStep.EnterPhase testStep = new SnapshotStep.EnterPhase(globalState, cmsClient, snapshotCreator, snapshotEntry);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(globalState, times(1)).updatePhase(GlobalState.Phase.SNAPSHOT_IN_PROGRESS);
        assertEquals(expected, nextStep.getClass());
    }

    @Test
    void CreateEntry_AsExpected() {
        // Set up the test
        when(snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotStep.CreateEntry testStep = new SnapshotStep.CreateEntry(globalState, cmsClient, snapshotCreator);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(cmsClient, times(1)).createSnapshotEntry("test");
        assertEquals(SnapshotStep.InitiateSnapshot.class, nextStep.getClass());
    }

    @Test
    void InitiateSnapshot_AsExpected() {
        // Set up the test
        when(snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotStep.InitiateSnapshot testStep = new SnapshotStep.InitiateSnapshot(globalState, cmsClient, snapshotCreator);
        testStep.run();
        WorkerStep nextStep = testStep.nextStep();

        // Check the results
        Mockito.verify(snapshotCreator, times(1)).registerRepo();
        Mockito.verify(snapshotCreator, times(1)).createSnapshot();
        Mockito.verify(cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.IN_PROGRESS);
        assertEquals(SnapshotStep.WaitForSnapshot.class, nextStep.getClass());
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
        when(snapshotCreator.isSnapshotFinished())
            .thenReturn(false)
            .thenReturn(true);

        // Run the test
        SnapshotStep.WaitForSnapshot waitPhase = new TestableWaitForSnapshot(globalState, cmsClient, snapshotCreator);
        waitPhase.run();
        WorkerStep nextStep = waitPhase.nextStep();

        // Check the results
        Mockito.verify(snapshotCreator, times(2)).isSnapshotFinished();
        assertEquals(SnapshotStep.ExitPhaseSuccess.class, nextStep.getClass());
    }

    @Test
    void WaitForSnapshot_failedSnapshot_AsExpected() {
        // Set up the test
        when(snapshotCreator.isSnapshotFinished())
            .thenReturn(false)
            .thenThrow(new SnapshotCreationFailed("test"));

        // Run the test
        SnapshotStep.WaitForSnapshot waitPhase = new TestableWaitForSnapshot(globalState, cmsClient, snapshotCreator);
        waitPhase.run();
        WorkerStep nextStep = waitPhase.nextStep();

        // Check the results
        Mockito.verify(snapshotCreator, times(2)).isSnapshotFinished();
        assertEquals(SnapshotStep.ExitPhaseSnapshotFailed.class, nextStep.getClass());
    }

    @Test
    void ExitPhaseSuccess_AsExpected() {
        // Set up the test
        when(snapshotCreator.getSnapshotName()).thenReturn("test");

        // Run the test
        SnapshotStep.ExitPhaseSuccess exitPhase = new ExitPhaseSuccess(globalState, cmsClient, snapshotCreator);
        exitPhase.run();
        WorkerStep nextStep = exitPhase.nextStep();

        // Check the results
        Mockito.verify(cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.COMPLETED);
        Mockito.verify(globalState, times(1)).updatePhase(GlobalState.Phase.SNAPSHOT_COMPLETED);
        assertEquals(null, nextStep);
    }

    @Test
    void ExitPhaseSnapshotFailed_AsExpected() {
        // Set up the test
        when(snapshotCreator.getSnapshotName()).thenReturn("test");
        SnapshotCreationFailed e = new SnapshotCreationFailed("test");

        // Run the test
        SnapshotStep.ExitPhaseSnapshotFailed exitPhase = new ExitPhaseSnapshotFailed(globalState, cmsClient, snapshotCreator, e);
        exitPhase.run();
        assertThrows(SnapshotCreationFailed.class, () -> {
            exitPhase.nextStep();
        });

        // Check the results
        Mockito.verify(cmsClient, times(1)).updateSnapshotEntry("test", SnapshotStatus.FAILED);
        Mockito.verify(globalState, times(1)).updatePhase(GlobalState.Phase.SNAPSHOT_FAILED);
    }    
}
