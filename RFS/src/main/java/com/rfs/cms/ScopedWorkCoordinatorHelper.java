package com.rfs.cms;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class ScopedWorkCoordinatorHelper {

    public final IWorkCoordinator workCoordinator;
    final ProcessManager processManager;

    public ScopedWorkCoordinatorHelper(IWorkCoordinator workCoordinator, ProcessManager processManager) {
        this.workCoordinator = workCoordinator;
        this.processManager = processManager;
    }

    public interface WorkItemGetter {
        @NonNull IWorkCoordinator.WorkAcquisitionOutcome apply(IWorkCoordinator wc);
    }

    public <T> T ensurePhaseCompletion(WorkItemGetter workItemIdSupplier,
                                       IWorkCoordinator.WorkAcquisitionOutcomeVisitor<T> visitor) throws IOException {
        var acquisitionResult = workItemIdSupplier.apply(workCoordinator);
        return acquisitionResult.visit(new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<T>() {
            @Override
            public T onAlreadyCompleted() throws IOException {
                return visitor.onAlreadyCompleted();
            }

            @Override
            public T onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) throws IOException {
                var workItemId = workItem.getWorkItemId();
                processManager.registerExpiration(workItem.workItemId, workItem.leaseExpirationTime);
                var rval = visitor.onAcquiredWork(workItem);
                workCoordinator.completeWorkItem(workItemId);
                processManager.markWorkAsCompleted(workItemId);
                return rval;
            }
        });
    }
}
