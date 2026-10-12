/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.datahandlers;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TargetConnectionLimiterTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;

    private final AtomicLong now = new AtomicLong(0);

    @Test
    void connectsWithinTheAllowanceAreNotDelayed() {
        var limiter = new TargetConnectionLimiter(100, now::get);

        for (var i = 0; i < 50; i++) {
            Assertions.assertEquals(0, limiter.reserveConnectDelayNanos());
            now.addAndGet(20 * MILLI);
        }
        Assertions.assertEquals(0, limiter.totalDelayedReservations());
    }

    @Test
    void aBurstBeyondTheAllowanceIsSpacedAtTheCurrentRate() {
        var limiter = new TargetConnectionLimiter(100, now::get);

        var delays = new long[15];
        for (var i = 0; i < delays.length; i++) {
            delays[i] = limiter.reserveConnectDelayNanos() / MILLI;
        }
        // A tenth of a second of allowance at 100 per second, plus the current slot, starts immediately.
        for (var i = 0; i <= 10; i++) {
            Assertions.assertEquals(0, delays[i], "connect " + i);
        }
        Assertions.assertArrayEquals(new long[] { 10, 20, 30, 40 }, java.util.Arrays.copyOfRange(delays, 11, 15));
    }

    @Test
    void failuresHalveTheRateAndACooldownPreventsRepeatedHalving() {
        var limiter = new TargetConnectionLimiter(400, now::get);
        for (var i = 0; i < 20; i++) {
            limiter.connectFailed();
        }
        for (var i = 0; i < 80; i++) {
            limiter.connectSucceeded();
        }
        now.addAndGet(SECOND);
        limiter.reserveConnectDelayNanos();
        Assertions.assertEquals(200, limiter.ratePerSecond(), 0.001);

        for (var window = 0; window < 2; window++) {
            for (var i = 0; i < 20; i++) {
                limiter.connectFailed();
            }
            now.addAndGet(SECOND);
            limiter.reserveConnectDelayNanos();
            Assertions.assertEquals(200, limiter.ratePerSecond(), 0.001, "cooldown window " + window);
        }
        for (var i = 0; i < 20; i++) {
            limiter.connectFailed();
        }
        now.addAndGet(SECOND);
        limiter.reserveConnectDelayNanos();
        Assertions.assertEquals(100, limiter.ratePerSecond(), 0.001);
        Assertions.assertEquals(2, limiter.totalRateDecreases());
    }

    @Test
    void aFewStrayFailuresDoNotReduceTheRate() {
        var limiter = new TargetConnectionLimiter(400, now::get);
        for (var i = 0; i < 4; i++) {
            limiter.connectFailed();
        }
        now.addAndGet(SECOND);
        limiter.reserveConnectDelayNanos();

        Assertions.assertEquals(400, limiter.ratePerSecond(), 0.001);
    }

    @Test
    void theRateGrowsQuicklyUntilTheFirstDecreaseAndGentlyAfter() {
        var limiter = new TargetConnectionLimiter(100, now::get);
        saturateOneWindow(limiter);
        Assertions.assertEquals(150, limiter.ratePerSecond(), 0.001);

        for (var i = 0; i < 10; i++) {
            limiter.connectFailed();
        }
        saturateOneWindow(limiter);
        Assertions.assertEquals(75, limiter.ratePerSecond(), 0.001);
        saturateOneWindow(limiter);
        saturateOneWindow(limiter);
        Assertions.assertEquals(75, limiter.ratePerSecond(), 0.001, "cooldown");
        saturateOneWindow(limiter);
        Assertions.assertEquals(82.5, limiter.ratePerSecond(), 0.001);
    }

    @Test
    void anIdleProcessDoesNotGrowItsAllowance() {
        var limiter = new TargetConnectionLimiter(100, now::get);
        limiter.reserveConnectDelayNanos();
        limiter.connectSucceeded();
        now.addAndGet(SECOND);
        limiter.reserveConnectDelayNanos();

        Assertions.assertEquals(100, limiter.ratePerSecond(), 0.001);
    }

    @Test
    void theUnlimitedLimiterNeverDelays() {
        for (var i = 0; i < 10_000; i++) {
            Assertions.assertEquals(0, TargetConnectionLimiter.UNLIMITED.reserveConnectDelayNanos());
        }
        TargetConnectionLimiter.UNLIMITED.connectFailed();
        Assertions.assertEquals(Double.POSITIVE_INFINITY, TargetConnectionLimiter.UNLIMITED.ratePerSecond());
    }

    private void saturateOneWindow(TargetConnectionLimiter limiter) {
        var rate = limiter.ratePerSecond();
        for (var i = 0; i < rate * 2; i++) {
            limiter.reserveConnectDelayNanos();
        }
        now.addAndGet(SECOND);
        limiter.reserveConnectDelayNanos();
    }
}
