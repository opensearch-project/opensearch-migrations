package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * This class maintains N items for S seconds.  After S seconds, items are expired as per the
 * specified expiration callback.  Callers can retrieve items from the cache or built on-demand
 * if no items are available within the cache that are ready to go.
 *
 * This class does not use locking.  Instead, it is assumed that one of these will be created for
 * each netty event loop.
 */
@Slf4j
public class ExpiringSubstitutableItemPool<F extends Future<U>, U> {

    private static class Entry<F> {
        Instant timestamp;
        F future;

        public Entry(F future) {
            timestamp = Instant.now();
            this.future = future;
        }

        @Override
        public String toString() {
            return "Entry{" + "timestamp=" + timestamp + ", value=" + future + '}';
        }
    }

    public static class PoolClosedException extends RuntimeException {}

    public static class Stats {
        @Getter
        private long nItemsCreated;
        @Getter
        private long nItemsExpired;
        @Getter
        private long nHotGets; // cache hits
        @Getter
        private long nColdGets; // cache misses
        @Getter
        Duration totalDurationBuildingItems = Duration.ZERO;
        @Getter
        Duration totalWaitTimeForCallers = Duration.ZERO;

        public Stats() {}

        public Stats(
            long nItemsCreated,
            long nItemsExpired,
            long nHotGets,
            long nColdGets,
            Duration totalDurationBuildingItems,
            Duration totalWaitTimeForCallers
        ) {
            this.nItemsCreated = nItemsCreated;
            this.nItemsExpired = nItemsExpired;
            this.nHotGets = nHotGets;
            this.nColdGets = nColdGets;
            this.totalDurationBuildingItems = totalDurationBuildingItems;
            this.totalWaitTimeForCallers = totalWaitTimeForCallers;
        }

        public Stats(Stats o) {
            this(
                o.nItemsCreated,
                o.nItemsExpired,
                o.nHotGets,
                o.nColdGets,
                o.totalDurationBuildingItems,
                o.totalWaitTimeForCallers
            );
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", Stats.class.getSimpleName() + "[", "]").add("nItemsCreated=" + nItemsCreated)
                .add("nHotGets=" + nHotGets)
                .add("nColdGets=" + nColdGets)
                .add("nExpiredItems=" + nItemsExpired)
                .add("avgDurationBuildingItems=" + averageBuildTime())
                .add("avgWaitTimeForCallers=" + averageWaitTime())
                .toString();
        }

        public long getTotalGets() {
            return nHotGets + nColdGets;
        }

        public Duration averageWaitTime() {
            if (getTotalGets() == 0) {
                return Duration.ZERO;
            }
            return totalWaitTimeForCallers.dividedBy(getTotalGets());
        }

        public Duration averageBuildTime() {
            if (totalItemsCreated() == 0) {
                return Duration.ZERO;
            }
            return totalDurationBuildingItems.dividedBy(totalItemsCreated());
        }

        private void itemBuilt(Duration delta) {
            totalDurationBuildingItems = totalDurationBuildingItems.plus(delta);
            nItemsCreated++;
        }

        private void addWaitTime(Duration delta) {
            totalWaitTimeForCallers = totalWaitTimeForCallers.plus(delta);
        }

        private void addHotGet() {
            nHotGets++;
        }

        private void addColdGet() {
            nColdGets++;
        }

        private long totalItemsCreated() {
            return nItemsCreated;
        }

        private void addExpiredItem() {
            nItemsExpired++;
        }
    }

    // Store in-progress futures that were the result of item builds in their "in-order"
    // creation time so that if the readyItems is empty, we can return a future that is
    // more likely to complete.
    private final LinkedHashSet<F> inProgressItems;
    private final Queue<Entry<F>> readyItems;
    private final Supplier<F> itemSupplier;
    private final Consumer<F> onExpirationConsumer;
    @Getter
    private final EventLoop eventLoop;
    private final Duration inactivityTimeout;
    private final GenericFutureListener<F> shuffleInProgressToReady;
    private final Stats stats;
    private int poolSize;

    public ExpiringSubstitutableItemPool(
        @NonNull Duration inactivityTimeout,
        @NonNull EventLoop eventLoop,
        @NonNull Supplier<F> itemSupplier,
        @NonNull Consumer<F> onExpirationConsumer,
        int numItemsToLoad,
        @NonNull Duration initialItemLoadInterval
    ) {
        this(inactivityTimeout, eventLoop, itemSupplier, onExpirationConsumer);
        increaseCapacityWithSchedule(numItemsToLoad, initialItemLoadInterval);
    }

    public ExpiringSubstitutableItemPool(
        @NonNull Duration inactivityTimeout,
        @NonNull EventLoop eventLoop,
        @NonNull Supplier<F> itemSupplier,
        @NonNull Consumer<F> onExpirationConsumer
    ) {
        assert inactivityTimeout.multipliedBy(-1).isNegative() : "inactivityTimeout must be > 0";
        this.inProgressItems = new LinkedHashSet<>();
        this.readyItems = new ArrayDeque<>();
        this.eventLoop = eventLoop;
        this.inactivityTimeout = inactivityTimeout;
        this.onExpirationConsumer = onExpirationConsumer;
        this.stats = new Stats();
        this.itemSupplier = () -> {
            var startTime = Instant.now();
            var rval = itemSupplier.get();
            rval.addListener(v -> stats.itemBuilt(Duration.between(startTime, Instant.now())));
            return rval;
        };
        // store this as a field so that we can remove the listener once the inProgress item has been
        // shifted to the readyItems
        this.shuffleInProgressToReady = f -> {
            inProgressItems.remove(f);
            if (f.isSuccess()) {
                readyItems.add(new Entry<>(f));
                scheduleNextExpirationSweep(inactivityTimeout);
            } else {
                // the calling context should track failures too - no reason to log
                // TODO - add some backoff here
                beginLoadingNewItemIfNecessary();
            }
        };
    }

    @SneakyThrows
    public Stats getStats() {
        // make a copy on the original thread making changes, which will be up to date at the time of capture and
        // immutable for future accessors, making it thread-safe
        var copiedStats = eventLoop.submit(() -> {
            log.atTrace().setMessage("copying stats ({})={}")
                .addArgument(() -> System.identityHashCode(stats))
                .addArgument(stats)
                .log();
            return new Stats(stats);
        }).get();
        log.atTrace()
            .setMessage("Got copied value of ({})={}")
            .addArgument(() -> System.identityHashCode(copiedStats))
            .addArgument(copiedStats)
            .log();
        return copiedStats;
    }

    public int increaseCapacity(int itemsToLoad) {
        return increaseCapacityWithSchedule(itemsToLoad, Duration.ZERO);
    }

    public int increaseCapacityWithSchedule(int itemsToLoad, Duration gapBetweenLoads) {
        poolSize += itemsToLoad;
        scheduleItemLoadsRecurse(itemsToLoad, gapBetweenLoads);
        return poolSize;
    }

    public F getAvailableOrNewItem() {
        if (inactivityTimeout.isZero()) {
            throw new PoolClosedException();
        }
        var startTime = Instant.now();
        log.atTrace().setMessage("getAvailableOrNewItem: readyItems.size()={}").addArgument(readyItems::size).log();
        var item = readyItems.poll();
        log.atTrace().setMessage("getAvailableOrNewItem: item={} remaining readyItems.size()={}")
            .addArgument(item).addArgument(readyItems::size).log();
        if (item != null) {
            stats.addHotGet();
            beginLoadingNewItemIfNecessary();
            stats.addWaitTime(Duration.between(startTime, Instant.now()));
            return item.future;
        }

        BiFunction<F, String, F> durationTrackingDecoratedItem = (itemsFuture, label) -> (F) itemsFuture.addListener(
            f -> {
                stats.addWaitTime(Duration.between(startTime, Instant.now()));
                log.trace(label + "returning value=" + f.get() + " from future " + itemsFuture);
            }
        );
        stats.addColdGet();
        var inProgressIt = inProgressItems.iterator();

        if (inProgressIt.hasNext()) {
            var firstItem = inProgressIt.next();
            inProgressIt.remove();
            firstItem.removeListeners(shuffleInProgressToReady);
            beginLoadingNewItemIfNecessary();
            return durationTrackingDecoratedItem.apply(firstItem, "IN_PROGRESS: ");
        }
        return durationTrackingDecoratedItem.apply(itemSupplier.get(), "FRESH: ");
    }

    private void scheduleItemLoadsRecurse(int itemsToLoad, Duration gapBetweenLoads) {
        eventLoop.schedule(() -> {
            beginLoadingNewItemIfNecessary();
            if (itemsToLoad >= 0) {
                scheduleItemLoadsRecurse(itemsToLoad - 1, gapBetweenLoads);
            }
        }, gapBetweenLoads.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void scheduleNextExpirationSweep(Duration d) {
        eventLoop.schedule(this::expireItems, d.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void expireItems() {
        var thresholdTimestamp = Instant.now().minus(this.inactivityTimeout);
        log.debug("expiration threshold = " + thresholdTimestamp);
        while (!readyItems.isEmpty()) {
            var oldestItem = readyItems.peek();
            var gap = Duration.between(thresholdTimestamp, oldestItem.timestamp);
            if (!gap.isNegative()) {
                log.debug("scheduling next sweep for " + gap);
                scheduleNextExpirationSweep(gap);
                return;
            } else {
                stats.addExpiredItem();
                var removedItem = readyItems.poll();
                assert removedItem == oldestItem : "expected the set of readyItems to be ordered chronologically, "
                    + "so with a fixed item timeout, nothing should ever be able to cut back in time.  "
                    + "Secondly, a concurrent mutation of any sort while in this function "
                    + "should have been impossible since we're only modifying this object through a shared eventloop";
                log.debug("Removing " + removedItem);
                onExpirationConsumer.accept(removedItem.future);
                beginLoadingNewItemIfNecessary();
            }
        }
    }

    private void beginLoadingNewItemIfNecessary() {
        if (inactivityTimeout.isZero()) {
            throw new PoolClosedException();
        } else if (poolSize > (inProgressItems.size() + readyItems.size())) {
            var futureItem = itemSupplier.get();
            inProgressItems.add(futureItem);
            futureItem.addListener(shuffleInProgressToReady);
        }
    }

    @Override
    @SneakyThrows
    public String toString() {
        return eventLoop.submit(this::toStringOnThread).get();
    }

    private String toStringOnThread() {
        final StringBuilder sb = new StringBuilder("ExpiringSubstitutableItemPool{");
        sb.append("poolSize=").append(poolSize);
        if (eventLoop.inEventLoop()) {
            // these two lines are dangerous if toString() is run from a concurrent environment
            sb.append(", inProgressItems=").append(inProgressItems);
            sb.append(", readyItems=").append(readyItems);
        } else {
            sb.append(", numInProgressItems=").append(inProgressItems.size());
            sb.append(", numReadyItems=").append(readyItems.size());
        }
        sb.append(", itemSupplier=").append(itemSupplier);
        sb.append(", onExpirationConsumer=").append(onExpirationConsumer);
        sb.append(", eventLoop=").append(eventLoop);
        sb.append(", inactivityTimeout=").append(inactivityTimeout);
        sb.append(", stats=").append(stats);
        sb.append('}');
        return sb.toString();
    }
}
