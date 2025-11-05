package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Duration;

import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PostgresWorkCoordinatorTest extends PostgresWorkCoordinatorTestBase {

    private PostgresWorkCoordinator coordinator;

    @BeforeEach
    @Override
    void setUp() throws Exception {
        super.setUp();
        coordinator = createCoordinator("test-worker-1");
    }

    @Test
    void testCreateUnassignedWorkItem() throws Exception {
        boolean created = coordinator.createUnassignedWorkItem(new WorkItem("index", 0, 0), () -> null);
        assertThat(created, is(true));
        
        boolean createdAgain = coordinator.createUnassignedWorkItem(new WorkItem("index", 0, 0), () -> null);
        assertThat(createdAgain, is(false));
    }

    @Test
    void testAcquireNextWorkItem() throws Exception {
        coordinator.createUnassignedWorkItem(new WorkItem("index", 0, 0), () -> null);
        
        var outcome = coordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        
        assertThat(outcome, is(notNullValue()));
        var visitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        outcome.visit(visitor);
        
        verify(visitor).onAcquiredWork(any());
        verify(visitor, never()).onAlreadyCompleted();
        verify(visitor, never()).onNoAvailableWorkToBeDone();
    }

    @Test
    void testCompleteWorkItem() throws Exception {
        coordinator.createUnassignedWorkItem(new WorkItem("index", 0, 0), () -> null);
        coordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        
        coordinator.completeWorkItem(new WorkItem("index", 0, 0), () -> null);
        
        int remaining = coordinator.numWorkItemsNotYetComplete(() -> null);
        assertThat(remaining, is(0));
    }

    @Test
    void testNoAvailableWork() throws Exception {
        var outcome = coordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        
        var visitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        outcome.visit(visitor);
        
        verify(visitor).onNoAvailableWorkToBeDone();
        verify(visitor, never()).onAlreadyCompleted();
        verify(visitor, never()).onAcquiredWork(any());
    }

    @Test
    void testLeaseExpirationAndRecovery() throws Exception {
        coordinator.createUnassignedWorkItem(new WorkItem("index", 0, 0), () -> null);
        
        var shortLeaseCoordinator = createCoordinator("worker-with-short-lease");
        shortLeaseCoordinator.acquireNextWorkItem(Duration.ofSeconds(1), () -> null);
        
        testClock.advance(Duration.ofSeconds(2));
        
        var outcome = coordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        
        var visitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        outcome.visit(visitor);
        
        verify(visitor).onAcquiredWork(any());
        verify(visitor, never()).onAlreadyCompleted();
        verify(visitor, never()).onNoAvailableWorkToBeDone();
        
        shortLeaseCoordinator.close();
    }

    @Test
    void testCreateSuccessorWorkItems() throws Exception {
        coordinator.createUnassignedWorkItem(new WorkItem("index", 0, 0), () -> null);
        coordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        
        coordinator.createSuccessorWorkItemsAndMarkComplete(
            new WorkItem("index", 0, 0),
            java.util.List.of(new WorkItem("index", 1, 0), new WorkItem("index", 2, 0)),
            0,
            () -> null
        );
        
        int remaining = coordinator.numWorkItemsNotYetComplete(() -> null);
        assertThat(remaining, is(2));
        
        assertThat(coordinator.workItemsNotYetComplete(() -> null), is(true));
    }

    @Test
    void testCannotCompleteWorkItemWithWrongLeaseHolder() throws Exception {
        coordinator.createUnassignedWorkItem(new WorkItem("index", 0, 0), () -> null);
        coordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        
        var otherCoordinator = createCoordinator("other-worker");
        
        assertThrows(Exception.class, () -> {
            otherCoordinator.completeWorkItem(new WorkItem("index", 0, 0), () -> null);
        });
        
        otherCoordinator.close();
    }

    @Test
    void testWorkItemsNotYetComplete() throws Exception {
        coordinator.createUnassignedWorkItem(new WorkItem("index", 0, 0), () -> null);
        coordinator.createUnassignedWorkItem(new WorkItem("index", 1, 0), () -> null);
        coordinator.createUnassignedWorkItem(new WorkItem("index", 2, 0), () -> null);
        
        assertThat(coordinator.numWorkItemsNotYetComplete(() -> null), is(3));
        
        var outcome = coordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        var visitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        when(visitor.onAcquiredWork(any())).thenAnswer(inv -> {
            var acquiredItem = (WorkItemAndDuration)inv.getArgument(0);
            coordinator.completeWorkItem(acquiredItem.getWorkItem(), () -> null);
            return acquiredItem;
        });
        outcome.visit(visitor);
        
        assertThat(coordinator.numWorkItemsNotYetComplete(() -> null), is(2));
        
        assertThat(coordinator.workItemsNotYetComplete(() -> null), is(true));
    }
}
