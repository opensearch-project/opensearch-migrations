package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

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
        Supplier<IWorkCoordinationContexts.ICompleteWorkItemContext> contextSupplier,
        Supplier<IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext> successorContextSupplier
    ) throws IOException, InterruptedException {
        var acquisitionResult = workItemIdSupplier.tryAcquire(workCoordinator);
        return acquisitionResult.visit(new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<T>() {
            @Override
            public T onAlreadyCompleted() throws IOException {
                return visitor.onAlreadyCompleted();
            }

            @Override
            public T onNoAvailableWorkToBeDone() throws IOException {
                return visitor.onNoAvailableWorkToBeDone();
            }

            @Override
            public T onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) throws IOException,
                InterruptedException {
                var workItemId = workItem.getWorkItemId();
                leaseExpireTrigger.registerExpiration(workItem.workItemId, workItem.leaseExpirationTime);
                if (!workItem.successorWorkItems.isEmpty()) {
                    workCoordinator.createSuccessorWorkItemsAndMarkComplete(workItemId, workItem.successorWorkItems, successorContextSupplier);
                    leaseExpireTrigger.markWorkAsCompleted(workItemId);
                    return visitor.onAlreadyCompleted();
                }
                var rval = visitor.onAcquiredWork(workItem);
                workCoordinator.completeWorkItem(workItemId, contextSupplier);
                leaseExpireTrigger.markWorkAsCompleted(workItemId);
                return rval;
            }
        });
    }
}
