package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * This class takes an expiration time and guarantees that the process will NOT run past that expiration
 * time unless the work is marked as complete before that expiration.  This class may, but does not need to,
 * synchronize its clock with an external source of truth for better accuracy.
 */
public class LeaseExpireTrigger implements AutoCloseable {
    private final ScheduledExecutorService scheduledExecutorService;
    final ConcurrentHashMap<String, Instant> workItemToLeaseMap;
    final Consumer<String> onLeaseExpired;
    final Clock currentTimeSupplier;
    /**
     * Constructs a LeaseExpireTrigger with the default system UTC clock.
     *
     * @param onLeaseExpired A consumer that will be called when a lease expires.
     */
    public LeaseExpireTrigger(Consumer<String> onLeaseExpired) {
        this(onLeaseExpired, Clock.systemUTC());
    }

    /**
     * Constructs a LeaseExpireTrigger with a specified clock.
     *
     * @param onLeaseExpired A consumer that will be called when a lease expires.
     *                       This consumer is expected to be run 0 or 1 times on any thread.
     * @param currentTimeSupplier The clock to use for time calculations.
     */
    public LeaseExpireTrigger(Consumer<String> onLeaseExpired, Clock currentTimeSupplier) {
        scheduledExecutorService = Executors.newScheduledThreadPool(
            1,
            new DefaultThreadFactory("leaseWatchingProcessKillerThread")
        );
        this.workItemToLeaseMap = new ConcurrentHashMap<>();
        this.onLeaseExpired = onLeaseExpired;
        this.currentTimeSupplier = currentTimeSupplier;
    }

    public void registerExpiration(String workItemId, Instant killTime) {
        workItemToLeaseMap.put(workItemId, killTime);
        final Runnable expirationRunnable = () -> {
            if (workItemToLeaseMap.containsKey(workItemId)) {
                onLeaseExpired.accept(workItemId);
            }
        };
        final var killDuration = Duration.between(currentTimeSupplier.instant(), killTime);
        if (killDuration.isNegative()) {
            // If lease is already expired, kill the process synchronously so that no work gets completed
            expirationRunnable.run();
        } else {
            scheduledExecutorService.schedule(expirationRunnable,
                killDuration.toMillis(),
                TimeUnit.MILLISECONDS
            );

        }
    }

    /**
     * This will remove the item from the map, meaning that even after the event fires, it will be ignored/swallowed.
     * @param workItemId
     */
    public void markWorkAsCompleted(String workItemId) {
        workItemToLeaseMap.remove(workItemId);
    }

    @Override
    public void close() throws Exception {
        scheduledExecutorService.shutdownNow();
    }
}
