package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@SuppressWarnings("java:S1854")
@Slf4j
public class ScopedWorkCoordinator {

    public final IWorkCoordinator workCoordinator;
    final LeaseExpireTrigger leaseExpireTrigger;

    public ScopedWorkCoordinator(IWorkCoordinator workCoordinator, LeaseExpireTrigger leaseExpireTrigger) {
        this.workCoordinator = workCoordinator;
        this.leaseExpireTrigger = leaseExpireTrigger;
    }

    public interface WorkItemGetter {
        @NonNull
        IWorkCoordinator.WorkAcquisitionOutcome tryAcquire(IWorkCoordinator wc);
    }

    public <T> T ensurePhaseCompletion(
        WorkItemGetter workItemIdSupplier,
        IWorkCoordinator.WorkAcquisitionOutcomeVisitor<T> visitor,
        Supplier<IWorkCoordinationContexts.ICompleteWorkItemContext> contextSupplier
    ) throws IOException, InterruptedException {
        var acquisitionResult = workItemIdSupplier.tryAcquire(workCoordinator);
        return acquisitionResult.visit(new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<T>() {
            @Override
            public T onAlreadyCompleted() throws IOException {
                log.info("Work item already marked as completed. Skipping.");
                return visitor.onAlreadyCompleted();
            }

            @Override
            public T onNoAvailableWorkToBeDone() throws IOException {
                log.info("No available work to be done at this time.");
                return visitor.onNoAvailableWorkToBeDone();
            }

            @Override
            public T onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) throws IOException,
                InterruptedException {
                var workItemId = workItem.getWorkItem().toString();
                log.info("Acquired work item: {} with lease expiration at {}", workItemId, workItem.leaseExpirationTime);
                leaseExpireTrigger.registerExpiration(workItemId, workItem.leaseExpirationTime);
                long startTime = System.currentTimeMillis();
                try {
                    T result = visitor.onAcquiredWork(workItem);
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("Finished onAcquiredWork for work item: {} in {} ms", workItemId, duration);
                    workCoordinator.completeWorkItem(workItemId, contextSupplier);
                    log.info("Marked work item {} as completed and released lease", workItemId);
                    leaseExpireTrigger.markWorkAsCompleted(workItemId);
                    return result;
                } catch (Exception e) {
                    log.error("Exception while processing work item {}: {}", workItemId, e.toString(), e);
                    throw e;
                }
            }
        });
    }
}
