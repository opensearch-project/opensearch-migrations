package com.rfs.cms;

import com.rfs.tracing.IWorkCoordinationContexts;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.function.Supplier;

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

    public <T> T ensurePhaseCompletion(WorkItemGetter workItemIdSupplier,
                                       IWorkCoordinator.WorkAcquisitionOutcomeVisitor<T> visitor,
                                       Supplier<IWorkCoordinationContexts.ICompleteWorkItemContext> contextSupplier)
            throws IOException, InterruptedException
    {
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
                var rval = visitor.onAcquiredWork(workItem);
                workCoordinator.completeWorkItem(workItemId, contextSupplier);
                leaseExpireTrigger.markWorkAsCompleted(workItemId);
                return rval;
            }
        });
    }
}
