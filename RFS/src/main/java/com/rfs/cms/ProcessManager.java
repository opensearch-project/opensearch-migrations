package com.rfs.cms;

import io.netty.util.concurrent.DefaultThreadFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * This class takes an expiration time and guarantees that the process will NOT run past that expiration
 * time unless the work is marked as complete before that expiration.  This class may, but does not need to,
 * synchronize its clock with an external source of truth for better accuracy.
 */
public class ProcessManager {
    private final ScheduledExecutorService scheduledExecutorService;
    final ConcurrentHashMap<String, Instant> workItemToLeaseMap;
    final Consumer<String> onLeaseExpired;
    final Clock currentTimeSupplier;

    public ProcessManager(Consumer<String> onLeaseExpired) {
        this(onLeaseExpired, Clock.systemUTC());
    }

    public ProcessManager(Consumer<String> onLeaseExpired, Clock currentTimeSupplier) {
        scheduledExecutorService = Executors.newScheduledThreadPool(1,
                new DefaultThreadFactory("leaseWatchingProcessKillerThread"));
        this.workItemToLeaseMap = new ConcurrentHashMap<>();
        this.onLeaseExpired = onLeaseExpired;
        this.currentTimeSupplier = currentTimeSupplier;
    }

    public void registerExpiration(String workItemId, Instant killTime) {
        workItemToLeaseMap.put(workItemId, killTime);
        final var killDuration = Duration.between(killTime, currentTimeSupplier.instant());
        scheduledExecutorService.schedule(()->{
            if (workItemToLeaseMap.containsKey(workItemId)) {
                onLeaseExpired.accept(workItemId);
            }
        }, killDuration.toSeconds(), TimeUnit.SECONDS);
    }

    public void markWorkAsCompleted(String workItemId) {
        workItemToLeaseMap.remove(workItemId);
    }
}
