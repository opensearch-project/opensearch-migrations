package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Isolated("Isolation based on temporal checks")
class ExpiringSubstitutableItemPoolTest {

    public static final int NUM_POOLED_ITEMS = 5;
    private static final Duration SUPPLY_WORK_TIME = Duration.ofMillis(100);
    private static final Duration TIME_BETWEEN_INITIAL_ITEMS = Duration.ofMillis(10);
    public static final Duration INACTIVITY_TIMEOUT = Duration.ofMillis(1000);
    public static final int NUM_ITEMS_TO_PULL = 1;

    /**
     * This test uses concurrency primitives to govern when fully-built items can be returned.
     * That removes timing inconsistencies for the first half or so of this test.  However,
     * expirations aren't as easy to control.  Built items aren't added until AFTER the callback,
     * giving the callback an opportunity to drive sequencing.  However, expirations are driven
     * by netty's clock and internal values of the ExpiringSubstitutablePool, so it's much harder
     * to mitigate temporal inconsistencies for the expiration-related checks.
     *
     * Still, there's been enormous value in finding issues with the assertions in the latter
     * part of this test.  If there are future issues, putting more conservative duration values
     * in place may further mitigate inconsistencies, though I haven't had any tests fail yet
     * unless I've stopped threads within the debugger.
     */
    @Test
    void get() throws Exception {
        var firstWaveBuildCountdownLatch = new CountDownLatch(NUM_POOLED_ITEMS);
        var expireCountdownLatch = new CountDownLatch(NUM_POOLED_ITEMS - NUM_ITEMS_TO_PULL);
        var secondWaveBuildCountdownLatch = new CountDownLatch(NUM_POOLED_ITEMS);
        var expirationsAreDoneFuture = new CompletableFuture<Boolean>();
        var builtItemCursor = new AtomicInteger();
        var expiredItems = new ArrayList<Integer>();
        var eventLoop = new NioEventLoopGroup(1, new DefaultThreadFactory("testPool"));
        var lastCreation = new AtomicReference<Instant>();
        var pool = new ExpiringSubstitutableItemPool<Future<Integer>, Integer>(
            INACTIVITY_TIMEOUT,
            eventLoop.next(),
            () -> {
                var rval = new DefaultPromise<Integer>(eventLoop.next());
                eventLoop.schedule(() -> {
                    if (firstWaveBuildCountdownLatch.getCount() <= 0) {
                        expirationsAreDoneFuture.whenComplete(
                            (v, t) -> rval.setSuccess(
                                getIntegerItem(builtItemCursor, lastCreation, secondWaveBuildCountdownLatch)
                            )
                        );
                    } else {
                        rval.setSuccess(getIntegerItem(builtItemCursor, lastCreation, firstWaveBuildCountdownLatch));
                    }
                }, SUPPLY_WORK_TIME.toMillis(), TimeUnit.MILLISECONDS);
                return rval;
            },
            item -> {
                log.info("Expiring item: " + item);
                try {
                    expiredItems.add(item.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw Lombok.sneakyThrow(e);
                } catch (ExecutionException e) {
                    throw Lombok.sneakyThrow(e);
                }
                expireCountdownLatch.countDown();
            }
        );
        for (int i = 0; i < NUM_POOLED_ITEMS; ++i) {
            Thread.sleep(TIME_BETWEEN_INITIAL_ITEMS.toMillis());
            log.info("instructing builder to add item now");
            pool.increaseCapacity(1);
        }

        log.debug("waiting for firstWaveBuildCountdownLatch");
        log.info("Pool=" + pool);
        firstWaveBuildCountdownLatch.await();
        log.info("Done waiting for firstWaveBuildCountdownLatch");
        log.info("Pool=" + pool);
        for (int i = 1; i <= NUM_ITEMS_TO_PULL; ++i) {
            Assertions.assertEquals(i, getNextItem(pool));
        }
        log.trace(
            "Awaiting the last items to expire (lastCreationTime="
                + lastCreation.get()
                + " timeout="
                + INACTIVITY_TIMEOUT
        );
        expireCountdownLatch.await(); // wait for the 4 other original items to expire
        log.trace("Done waiting");
        {
            var nowInstant = Instant.now();
            var limitInstant = lastCreation.get().plus(INACTIVITY_TIMEOUT);
            log.debug(
                "nowInstant="
                    + nowInstant
                    + " limitInstant="
                    + limitInstant
                    + " gap = "
                    + Duration.between(limitInstant, nowInstant)
                    + " limit is before now = "
                    + limitInstant.isBefore(nowInstant)
            );
            Assertions.assertTrue(limitInstant.isBefore(nowInstant));
        }
        var initialExpiredItemsStr = IntStream.range(NUM_ITEMS_TO_PULL + 1, NUM_POOLED_ITEMS + 1)
            .mapToObj(i -> Integer.toString(i))
            .collect(Collectors.joining(","));
        Assertions.assertEquals(
            initialExpiredItemsStr,
            expiredItems.stream()
                .limit(NUM_POOLED_ITEMS - NUM_ITEMS_TO_PULL)
                .map(i -> i.toString())
                .collect(Collectors.joining(","))
        );

        expirationsAreDoneFuture.complete(true);
        Assertions.assertTrue(pool.getStats().getNItemsCreated() >= 5);
        Assertions.assertEquals(0, pool.getStats().getNColdGets());
        Assertions.assertEquals(1, pool.getStats().getNHotGets());
        Assertions.assertTrue(pool.getStats().getNItemsExpired() >= 4);

        for (int i = 1; i <= NUM_POOLED_ITEMS * 2; ++i) {
            var nextItemGrabbed = getNextItem(pool);
            log.debug("Pool=" + pool + " nextItem=" + nextItemGrabbed);
            Assertions.assertEquals(NUM_POOLED_ITEMS + i, nextItemGrabbed);
        }

        var numItemsCreated = pool.getStats().getNItemsCreated();
        log.debug("numItemsCreated=" + numItemsCreated);
        Assertions.assertTrue(numItemsCreated >= 15);
        Assertions.assertEquals(11, pool.getStats().getNHotGets() + pool.getStats().getNColdGets());
        Assertions.assertTrue(pool.getStats().getNItemsExpired() >= 4);

        Assertions.assertTrue(pool.getStats().averageBuildTime().toMillis() > 0);
        Assertions.assertTrue(
            pool.getStats().averageWaitTime().toMillis() < pool.getStats().averageBuildTime().toMillis()
        );
    }

    private static Integer getNextItem(ExpiringSubstitutableItemPool<Future<Integer>, Integer> pool)
        throws InterruptedException, ExecutionException {
        return pool.getEventLoop()
            .next()
            .schedule(() -> pool.getAvailableOrNewItem(), 0, TimeUnit.MILLISECONDS)
            .get()
            .get();
    }

    private static Integer getIntegerItem(
        AtomicInteger builtItemCursor,
        AtomicReference<Instant> lastCreation,
        CountDownLatch countdownLatchToUse
    ) {
        log.debug("Building item (" + builtItemCursor.hashCode() + ") " + (builtItemCursor.get() + 1));
        countdownLatchToUse.countDown();
        lastCreation.set(Instant.now());
        return Integer.valueOf(builtItemCursor.incrementAndGet());
    }
}
