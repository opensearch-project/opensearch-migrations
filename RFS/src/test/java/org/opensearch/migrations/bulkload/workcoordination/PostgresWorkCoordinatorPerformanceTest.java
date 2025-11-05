package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

@Disabled("Performance tests disabled")
class PostgresWorkCoordinatorPerformanceTest extends PostgresWorkCoordinatorTestBase {

    @Test
    void testPerformanceWith100Workers() throws Exception {
        var workerCount = 100;
        var itemsPerWorker = 10;
        var totalItems = workerCount * itemsPerWorker;
        
        var setupCoordinator = createCoordinator("setup");
        var startSetup = System.currentTimeMillis();
        for (int i = 0; i < totalItems; i++) {
            setupCoordinator.createUnassignedWorkItem(new WorkItem("item", i, 0), () -> null);
        }
        long setupTime = System.currentTimeMillis() - startSetup;
        System.err.println("Setup time for " + totalItems + " items: " + setupTime + "ms");
        
        var executor = Executors.newFixedThreadPool(workerCount);
        var completedCount = new AtomicInteger(0);
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(workerCount);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < workerCount; i++) {
            final var workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    var workerCoordinator = createCoordinator(workerId);
                    
                    while (true) {
                        var outcome = workerCoordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
                        var acquired = new boolean[]{false};
                        var itemId = new String[1];
                        
                        var visitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
                        doAnswer(invocation -> {
                            IWorkCoordinator.WorkItemAndDuration workItem = invocation.getArgument(0);
                            acquired[0] = true;
                            itemId[0] = workItem.getWorkItem().toString();
                            return null;
                        }).when(visitor).onAcquiredWork(any());
                        
                        outcome.visit(visitor);
                        
                        if (!acquired[0]) {
                            break;
                        }
                        
                        workerCoordinator.completeWorkItem(WorkItem.fromString(itemId[0]), () -> null);
                        completedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Worker failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertThat(doneLatch.await(120, TimeUnit.SECONDS), is(true));
        
        var totalTime = System.currentTimeMillis() - startTime;
        executor.shutdown();
        
        assertThat(completedCount.get(), is(totalItems));
        assertThat(setupCoordinator.numWorkItemsNotYetComplete(() -> null), is(0));
        
        System.err.println("Performance Results:");
        System.err.println("  Workers: " + workerCount);
        System.err.println("  Total Items: " + totalItems);
        System.err.println("  Total Time: " + totalTime + "ms");
        System.err.println("  Throughput: " + (totalItems * 1000.0 / totalTime) + " items/sec");
        System.err.println("  Avg Time per Item: " + (totalTime / (double)totalItems) + "ms");
    }

    @Test
    void testHighContentionScenario() throws Exception {
        var workerCount = 50;
        var itemCount = 10;
        
        var setupCoordinator = createCoordinator("setup");
        for (int i = 0; i < itemCount; i++) {
            setupCoordinator.createUnassignedWorkItem(new WorkItem("item", i, 0), () -> null);
        }
        
        var executor = Executors.newFixedThreadPool(workerCount);
        var acquiredItems = Collections.synchronizedList(new ArrayList<>());
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(workerCount);
        
        for (int i = 0; i < workerCount; i++) {
            final String workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    var workerCoordinator = createCoordinator(workerId);
                    
                    var outcome = workerCoordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
                    var visitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
                    doAnswer(invocation -> {
                        IWorkCoordinator.WorkItemAndDuration workItem = invocation.getArgument(0);
                        acquiredItems.add(workItem.getWorkItem().toString());
                        return null;
                    }).when(visitor).onAcquiredWork(any());
                    
                    outcome.visit(visitor);
                } catch (Exception e) {
                    fail("Worker failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS), is(true));
        executor.shutdown();
        
        assertThat(acquiredItems.size(), is(itemCount));
        assertThat(acquiredItems.stream().distinct().count(), is((long)itemCount));
    }

    @Test
    void testSuccessorWorkItemPerformance() throws Exception {
        var parentCount = 100;
        var successorsPerParent = 5;
        
        var coordinator = createCoordinator("worker");
        
        for (int i = 0; i < parentCount; i++) {
            coordinator.createUnassignedWorkItem(new WorkItem("parent", i, 0), () -> null);
        }
        
        var startTime = System.currentTimeMillis();
        
        for (int i = 0; i < parentCount; i++) {
            var outcome = coordinator.acquireNextWorkItem(Duration.ofMinutes(5), () -> null);
            final int parentIndex = i;
            
            var visitor = mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
            doAnswer(invocation -> {
                try {
                    IWorkCoordinator.WorkItemAndDuration workItem = invocation.getArgument(0);
                    var successors = new ArrayList<WorkItem>();
                    for (int j = 0; j < successorsPerParent; j++) {
                        successors.add(new WorkItem("child-" + parentIndex, 0, j));
                    }
                    coordinator.createSuccessorWorkItemsAndMarkComplete(
                        workItem.getWorkItem(),
                        successors,
                        0,
                        () -> null
                    );
                } catch (Exception e) {
                    fail("Failed to create successors: " + e.getMessage());
                }
                return null;
            }).when(visitor).onAcquiredWork(any());
            
            outcome.visit(visitor);
        }
        
        var creationTime = System.currentTimeMillis() - startTime;
        
        var expectedSuccessors = parentCount * successorsPerParent;
        assertThat(coordinator.numWorkItemsNotYetComplete(() -> null), is(expectedSuccessors));
        
        System.err.println("Successor Creation Performance:");
        System.err.println("  Parents: " + parentCount);
        System.err.println("  Successors per Parent: " + successorsPerParent);
        System.err.println("  Total Successors: " + expectedSuccessors);
        System.err.println("  Creation Time: " + creationTime + "ms");
        System.err.println("  Avg Time per Parent: " + (creationTime / (double)parentCount) + "ms");
    }
}
