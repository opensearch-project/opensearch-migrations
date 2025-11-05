package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

class PostgresWorkCoordinatorMultiWorkerTest extends PostgresWorkCoordinatorTestBase {

    @Test
    void testMultipleWorkersAcquireDifferentWorkItems() throws Exception {
        var coordinator1 = createCoordinator("worker-1");
        var coordinator2 = createCoordinator("worker-2");
        
        assertThat(coordinator1.createUnassignedWorkItem(new WorkItem("item", 1, 0), () -> null), is(true));
        assertThat(coordinator1.createUnassignedWorkItem(new WorkItem("item", 2, 0), () -> null), is(true));
        assertThat(coordinator1.numWorkItemsNotYetComplete(() -> null), is(2));
        assertThat(coordinator2.numWorkItemsNotYetComplete(() -> null), is(2));
        
        var visitor1 = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        var outcome1 = coordinator1.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        outcome1.visit(visitor1);
        
        var remainingAfterFirst = coordinator1.numWorkItemsNotYetComplete(() -> null);
        assertThat("Should still have 2 incomplete items after acquisition", remainingAfterFirst, is(2));
        
        var visitor2 = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        var outcome2 = coordinator2.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        outcome2.visit(visitor2);
        
        verify(visitor1).onAcquiredWork(any());
        verify(visitor2).onAcquiredWork(any());
        verify(visitor1, never()).onNoAvailableWorkToBeDone();
        verify(visitor2, never()).onNoAvailableWorkToBeDone();
    }

    @Test
    void testConcurrentWorkAcquisitionNoDuplicates() throws Exception {
        var coordinator = createCoordinator("setup");
        for (int i = 0; i < 10; i++) {
            coordinator.createUnassignedWorkItem(new WorkItem("item", i, 0), () -> null);
        }
        
        var workerCount = 5;
        var executor = Executors.newFixedThreadPool(workerCount);
        var latch = new CountDownLatch(workerCount);
        var sharedVisitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        
        for (int i = 0; i < workerCount; i++) {
            final String workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    var workerCoordinator = createCoordinator(workerId);
                    for (int j = 0; j < 3; j++) {
                        var outcome = workerCoordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
                        outcome.visit(sharedVisitor);
                    }
                } catch (Exception e) {
                    fail("Worker failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertThat(latch.await(30, TimeUnit.SECONDS), is(true));
        executor.shutdown();
        
        verify(sharedVisitor, times(10)).onAcquiredWork(any());
    }

    @Test
    void testAllWorkItemsProcessedExactlyOnce() throws Exception {
        var coordinator = createCoordinator("setup");
        var itemCount = 20;
        for (int i = 0; i < itemCount; i++) {
            coordinator.createUnassignedWorkItem(new WorkItem("item", i, 0), () -> null);
        }
        
        var workerCount = 3;
        var executor = Executors.newFixedThreadPool(workerCount);
        var completedCount = new AtomicInteger(0);
        
        for (int i = 0; i < workerCount; i++) {
            final var workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    var workerCoordinator = createCoordinator(workerId);
                    var noWorkCount = 0;
                    while (noWorkCount < 5) {
                        var outcome = workerCoordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
                        var visitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
                        outcome.visit(visitor);
                        
                        var acquiredWork = mockingDetails(visitor).getInvocations().stream()
                                .filter(inv -> inv.getMethod().getName().equals("onAcquiredWork"))
                                .findFirst();
                        
                        if (acquiredWork.isPresent()) {
                            var workItem = (IWorkCoordinator.WorkItemAndDuration) acquiredWork.get().getArgument(0);
                            workerCoordinator.completeWorkItem(workItem.getWorkItem(), () -> null);
                            completedCount.incrementAndGet();
                            noWorkCount = 0;
                        } else {
                            noWorkCount++;
                            Thread.sleep(50);
                        }
                    }
                } catch (Exception e) {
                    fail("Worker failed: " + e.getMessage());
                }
            });
        }
        
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS), is(true));
        
        assertThat("All items completed", completedCount.get(), is(itemCount));
        assertThat("No incomplete items", coordinator.numWorkItemsNotYetComplete(() -> null), is(0));
    }

    @Test
    void testSuccessorWorkItemsProcessedByMultipleWorkers() throws Exception {
        var coordinator1 = createCoordinator("worker-1");
        var coordinator2 = createCoordinator("worker-2");
        
        coordinator1.createUnassignedWorkItem(new WorkItem("parent-item", 0, 0), () -> null);
        
        var parentVisitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        doAnswer(invocation -> {
            try {
                coordinator1.createSuccessorWorkItemsAndMarkComplete(
                    new WorkItem("parent-item", 0, 0),
                    List.of(new WorkItem("child", 1, 0), new WorkItem("child", 2, 0)),
                    0,
                    () -> null
                );
            } catch (Exception e) {
                fail("Failed to create successors: " + e.getMessage());
            }
            return null;
        }).when(parentVisitor).onAcquiredWork(any());
        
        assertThat("parent-item should be queued", coordinator1.numWorkItemsNotYetComplete(() -> null), is(1));
        var outcome = coordinator1.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        outcome.visit(parentVisitor);
        
        assertThat("multiple successor items are created after its processed", coordinator1.numWorkItemsNotYetComplete(() -> null), is(2));
        var sharedVisitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        var outcome1 = coordinator1.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        outcome1.visit(sharedVisitor);
        
        var outcome2 = coordinator2.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
        outcome2.visit(sharedVisitor);
        
        verify(sharedVisitor, times(2)).onAcquiredWork(any());
    }
}
