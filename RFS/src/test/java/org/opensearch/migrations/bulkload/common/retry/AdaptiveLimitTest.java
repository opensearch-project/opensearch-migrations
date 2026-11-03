package org.opensearch.migrations.bulkload.common.retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

class AdaptiveLimitTest {
    /** Fill every slot, then release them all with {@code signal}: one round of requests. */
    private static void round(AdaptiveLimit limit, AdaptiveLimit.Signal signal) {
        var slots = new ArrayList<AdaptiveLimit.Slot>();
        int permits = Math.max(1, (int) limit.limit());
        for (int i = 0; i < permits; i++) {
            slots.add(limit.acquire().block(Duration.ofSeconds(1)));
        }
        slots.forEach(slot -> slot.release(signal));
    }

    @Test
    void slowStartDoublesEachRoundUntilTheMax() {
        var limit = new AdaptiveLimit(10, () -> 0.5);
        var seen = new ArrayList<Integer>();
        for (int i = 0; i < 5; i++) {
            seen.add((int) limit.limit());
            round(limit, AdaptiveLimit.Signal.SUCCESS);
        }
        Assertions.assertEquals(List.of(1, 2, 4, 8, 10), seen);
    }

    @Test
    void oneOverloadEventCutsOnceByARandomHalfToSevenTenths() {
        var limit = new AdaptiveLimit(20, () -> 0.5);
        for (int i = 0; i < 4; i++) {
            round(limit, AdaptiveLimit.Signal.SUCCESS);
        }
        Assertions.assertEquals(16, limit.limit());
        // All 16 requests sent before the cut come back throttled; only the first one counts.
        round(limit, AdaptiveLimit.Signal.THROTTLED_429);
        Assertions.assertEquals(16 * 0.6, limit.limit(), 1e-9);
        Assertions.assertEquals(8, limit.threshold(), 1e-9);
        // A request sent after the cut can cut again.
        round(limit, AdaptiveLimit.Signal.THROTTLED_429);
        Assertions.assertEquals(16 * 0.6 * 0.6, limit.limit(), 1e-9);
        Assertions.assertEquals(1, new AdaptiveLimit(5, () -> 0.0).limit());
    }

    @Test
    void aboveTheThresholdGrowthIsAboutOnePerRound() {
        var limit = new AdaptiveLimit(100, () -> 0.0);
        for (int i = 0; i < 4; i++) {
            round(limit, AdaptiveLimit.Signal.SUCCESS);
        }
        round(limit, AdaptiveLimit.Signal.THROTTLED_429);   // 16 -> 8, threshold 8
        Assertions.assertEquals(8, limit.limit(), 1e-9);
        round(limit, AdaptiveLimit.Signal.SUCCESS);
        Assertions.assertEquals(9, limit.limit(), 0.1);
    }

    @Test
    void theLimitDoesNotGrowWhileItIsNotTheBottleneck() {
        var limit = new AdaptiveLimit(10, () -> 0.5);
        round(limit, AdaptiveLimit.Signal.SUCCESS);
        round(limit, AdaptiveLimit.Signal.SUCCESS);
        Assertions.assertEquals(4, limit.limit());
        // The source is slow: one request at a time, with three slots unused.
        for (int i = 0; i < 20; i++) {
            limit.acquire().block(Duration.ofSeconds(1)).release(AdaptiveLimit.Signal.SUCCESS);
        }
        Assertions.assertEquals(4, limit.limit());
    }

    @Test
    void otherFailuresLeaveTheLimitAlone() {
        var limit = new AdaptiveLimit(10, () -> 0.5);
        round(limit, AdaptiveLimit.Signal.SUCCESS);
        round(limit, AdaptiveLimit.Signal.OTHER_FAILURE);
        round(limit, AdaptiveLimit.Signal.CANCELLED);
        Assertions.assertEquals(2, limit.limit());
    }

    @Test
    void aQuietPeriodResetsTheThresholdToTheMax() {
        var holder = new AdaptiveLimit[1];
        StepVerifier.withVirtualTime(() -> Mono.fromCallable(() -> {
                holder[0] = new AdaptiveLimit(64, () -> 0.0);
                for (int i = 0; i < 4; i++) {
                    round(holder[0], AdaptiveLimit.Signal.SUCCESS);
                }
                round(holder[0], AdaptiveLimit.Signal.THROTTLED_429);   // 16 -> 8, threshold 8
                return holder[0];
            }))
            .expectNextCount(1)
            .thenAwait(AdaptiveLimit.QUIET_PERIOD.minusSeconds(1))
            .then(() -> {
                round(holder[0], AdaptiveLimit.Signal.SUCCESS);
                Assertions.assertEquals(8, holder[0].threshold(), 1e-9);
            })
            .thenAwait(Duration.ofSeconds(1))
            .then(() -> {
                round(holder[0], AdaptiveLimit.Signal.SUCCESS);
                Assertions.assertEquals(64, holder[0].threshold(), 1e-9);
                double before = holder[0].limit();
                round(holder[0], AdaptiveLimit.Signal.SUCCESS);
                // Below the threshold again, so each success adds a whole slot.
                Assertions.assertEquals(before + Math.floor(before), holder[0].limit(), 1e-9);
            })
            .verifyComplete();
    }

    @Test
    void releasingHandsTheSlotToTheNextWaiterAndCancelledWaitersLoseTheirPlace() {
        var limit = new AdaptiveLimit(1, () -> 0.5);
        var held = limit.acquire().block(Duration.ofSeconds(1));
        var granted = new ArrayList<String>();
        var cancelled = limit.acquire().subscribe(slot -> granted.add("cancelled"));
        limit.acquire().subscribe(slot -> granted.add("waiting"));
        Assertions.assertEquals(2, limit.waiters());
        cancelled.dispose();
        Assertions.assertEquals(1, limit.waiters());

        held.release(AdaptiveLimit.Signal.SUCCESS);
        held.release(AdaptiveLimit.Signal.SUCCESS);   // a second release is ignored

        Assertions.assertEquals(List.of("waiting"), granted);
        Assertions.assertEquals(1, limit.inFlight());
        Assertions.assertEquals(0, limit.waiters());
    }

    /** Hold one slot for {@code holdMillis} of virtual time, then release it. */
    private static void hold(VirtualTimeScheduler time, AdaptiveLimit limit, long holdMillis,
                             AdaptiveLimit.Signal signal) {
        var slot = new AdaptiveLimit.Slot[1];
        limit.acquire().subscribe(s -> slot[0] = s);
        Assertions.assertNotNull(slot[0], "no slot granted");
        time.advanceTimeBy(Duration.ofMillis(holdMillis));
        slot[0].release(signal);
    }

    @Test
    void throttlesCutBelowOneDownToTheFloor() {
        var time = VirtualTimeScheduler.getOrSet();
        try {
            var limit = new AdaptiveLimit(10, () -> 0.0);
            for (int i = 0; i < 10; i++) {
                hold(time, limit, 100, AdaptiveLimit.Signal.THROTTLED_429);
                time.advanceTimeBy(Duration.ofMinutes(1));   // past any pacing gap
            }
            Assertions.assertEquals(AdaptiveLimit.MIN_LIMIT, limit.limit(), 1e-12);
            Assertions.assertEquals(1, limit.threshold(), 1e-12);
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void belowOneTheSlotIsIdleForTheRestOfEachPacedInterval() {
        var time = VirtualTimeScheduler.getOrSet();
        try {
            var limit = new AdaptiveLimit(10, () -> 0.0);
            hold(time, limit, 1000, AdaptiveLimit.Signal.THROTTLED_429);   // L = 0.5, hold time 1 s
            Assertions.assertEquals(0.5, limit.limit(), 1e-12);
            var granted = new ArrayList<AdaptiveLimit.Slot>();
            limit.acquire().subscribe(granted::add);
            // (1/L - 1) * 1 s = 1 s before the next request may go.
            time.advanceTimeBy(Duration.ofMillis(999));
            Assertions.assertTrue(granted.isEmpty(), "granted before the paced interval ended");
            Assertions.assertEquals(1, limit.waiters());
            time.advanceTimeBy(Duration.ofMillis(1));
            Assertions.assertEquals(1, granted.size());
            time.advanceTimeBy(Duration.ofMillis(1000));
            granted.get(0).release(AdaptiveLimit.Signal.THROTTLED_429);   // L = 0.25: 3 s idle
            limit.acquire().subscribe(granted::add);
            time.advanceTimeBy(Duration.ofMillis(2999));
            Assertions.assertEquals(1, granted.size());
            time.advanceTimeBy(Duration.ofMillis(1));
            Assertions.assertEquals(2, granted.size());
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void belowOneEachSuccessAddsTheFloorBackToOne() {
        var time = VirtualTimeScheduler.getOrSet();
        try {
            var limit = new AdaptiveLimit(10, () -> 0.0);
            hold(time, limit, 10, AdaptiveLimit.Signal.THROTTLED_429);   // 1 -> 0.5
            time.advanceTimeBy(Duration.ofSeconds(1));
            hold(time, limit, 10, AdaptiveLimit.Signal.SUCCESS);
            Assertions.assertEquals(0.5 + AdaptiveLimit.MIN_LIMIT, limit.limit(), 1e-12);
            for (int i = 0; i < 40; i++) {
                time.advanceTimeBy(Duration.ofSeconds(1));
                hold(time, limit, 10, AdaptiveLimit.Signal.SUCCESS);
            }
            // Capped at 1 on the way up; from there the usual rules apply (threshold 1: +1/L per success).
            Assertions.assertTrue(limit.limit() >= 1, "limit " + limit.limit());
        } finally {
            VirtualTimeScheduler.reset();
        }
    }
}
