package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
                var acquisitionTime = Instant.now();
                var leaseDuration = Duration.between(acquisitionTime, workItem.leaseExpirationTime);
                // Trigger early checkpoint at max(lease*0.75, lease-4.5min) to allow cleanup before expiry
                var earlyTriggerOffset = leaseDuration.multipliedBy(3).dividedBy(4)
                    .compareTo(leaseDuration.minus(Duration.ofMinutes(4).plusSeconds(30))) > 0
                    ? leaseDuration.multipliedBy(3).dividedBy(4)
                    : leaseDuration.minus(Duration.ofMinutes(4).plusSeconds(30));
                var earlyTriggerTime = acquisitionTime.plus(earlyTriggerOffset.isNegative() ? Duration.ZERO : earlyTriggerOffset);
                log.info("Scheduling early checkpoint trigger at {} ({}s into lease)", earlyTriggerTime, earlyTriggerOffset.toSeconds());
                leaseExpireTrigger.registerExpiration(workItemId, earlyTriggerTime);
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
