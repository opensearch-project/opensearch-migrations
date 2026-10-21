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
            Assertions.assertEquals(0, limiter.reserveConnect().delayNanos());
            now.addAndGet(20 * MILLI);
        }
        Assertions.assertEquals(0, limiter.totalDelayedReservations());
    }

    @Test
    void aBurstBeyondTheAllowanceIsSpacedAtTheCurrentRate() {
        var limiter = new TargetConnectionLimiter(100, now::get);

        var delays = new long[15];
        for (var i = 0; i < delays.length; i++) {
            delays[i] = limiter.reserveConnect().delayNanos() / MILLI;
        }
        // A tenth of a second of allowance at 100 per second, plus the current slot, starts immediately.
        for (var i = 0; i <= 10; i++) {
            Assertions.assertEquals(0, delays[i], "connect " + i);
        }
        Assertions.assertArrayEquals(new long[] { 10, 20, 30, 40 }, java.util.Arrays.copyOfRange(delays, 11, 15));
    }

    @Test
    void twoConsecutiveFailingWindowsHalveTheRate() {
        var limiter = new TargetConnectionLimiter(400, now::get);
        failingWindow(limiter, 30, 70);
        Assertions.assertEquals(400, limiter.ratePerSecond(), 0.001, "one failing window is not enough");
        failingWindow(limiter, 30, 70);

        Assertions.assertEquals(200, limiter.ratePerSecond(), 0.001);
        Assertions.assertEquals(1, limiter.totalRateDecreases());
    }

    @Test
    void aSingleFailingWindowBetweenHealthyOnesDoesNotReduceTheRate() {
        var limiter = new TargetConnectionLimiter(400, now::get);
        failingWindow(limiter, 30, 70);
        failingWindow(limiter, 0, 100);
        failingWindow(limiter, 30, 70);

        Assertions.assertEquals(0, limiter.totalRateDecreases());
    }

    @Test
    void theBackgroundHandshakeFailuresOfAHealthyTargetDoNotReduceTheRate() {
        var limiter = new TargetConnectionLimiter(400, now::get);
        for (var window = 0; window < 5; window++) {
            failingWindow(limiter, 8, 192);
        }

        Assertions.assertEquals(0, limiter.totalRateDecreases());
    }

    @Test
    void oneIncidentCannotTakeTheRateBelowAQuarterOfWhereItStarted() {
        var limiter = new TargetConnectionLimiter(400, now::get);
        for (var window = 0; window < 12; window++) {
            failingWindow(limiter, 50, 50);
        }
        Assertions.assertEquals(100, limiter.ratePerSecond(), 0.001);

        // A healthy window ends the incident, and its saturated demand grows the rate to 125.
        failingWindow(limiter, 0, 100);
        Assertions.assertEquals(125, limiter.ratePerSecond(), 0.001);
        for (var window = 0; window < 6; window++) {
            failingWindow(limiter, 50, 50);
        }
        Assertions.assertEquals(31.25, limiter.ratePerSecond(), 0.001, "a new incident's floor is a quarter of 125");
    }

    @Test
    void windowsTooSmallToJudgeNeitherEndAnIncidentNorGrowWhileFailing() {
        var limiter = new TargetConnectionLimiter(400, now::get);
        failingWindow(limiter, 50, 50);
        failingWindow(limiter, 5, 5);
        failingWindow(limiter, 50, 50);

        Assertions.assertEquals(200, limiter.ratePerSecond(), 0.001);
    }

    @Test
    void failuresOfConnectsStartedBeforeADecreaseDoNotCountAgainstTheNewRate() {
        var limiter = new TargetConnectionLimiter(400, now::get);
        failingWindow(limiter, 30, 70);
        var startedAtTheOldRate = new java.util.ArrayList<TargetConnectionLimiter.Reservation>();
        for (var i = 0; i < 100; i++) {
            startedAtTheOldRate.add(limiter.reserveConnect());
        }
        startedAtTheOldRate.subList(0, 30).forEach(limiter::connectFailed);
        startedAtTheOldRate.subList(30, 60).forEach(limiter::connectSucceeded);
        now.addAndGet(SECOND);
        limiter.reserveConnect();
        Assertions.assertEquals(200, limiter.ratePerSecond(), 0.001);

        startedAtTheOldRate.subList(60, 100).forEach(limiter::connectFailed);
        failingWindow(limiter, 0, 40);
        failingWindow(limiter, 0, 40);
        Assertions.assertEquals(1, limiter.totalRateDecreases());
        Assertions.assertEquals(100, limiter.totalFailures());
    }

    @Test
    void theRateGrowsQuicklyUntilTheFirstDecreaseAndModeratelyAfter() {
        var limiter = new TargetConnectionLimiter(100, now::get);
        saturateOneWindow(limiter, 0);
        Assertions.assertEquals(150, limiter.ratePerSecond(), 0.001);

        saturateOneWindow(limiter, 100);
        Assertions.assertEquals(150, limiter.ratePerSecond(), 0.001, "no growth while failing");
        saturateOneWindow(limiter, 150);
        Assertions.assertEquals(75, limiter.ratePerSecond(), 0.001);
        saturateOneWindow(limiter, 0);
        Assertions.assertEquals(93.75, limiter.ratePerSecond(), 0.001);
    }

    @Test
    void anIdleProcessDoesNotGrowItsAllowance() {
        var limiter = new TargetConnectionLimiter(100, now::get);
        limiter.connectSucceeded(limiter.reserveConnect());
        now.addAndGet(SECOND);
        limiter.reserveConnect();

        Assertions.assertEquals(100, limiter.ratePerSecond(), 0.001);
    }

    @Test
    void theUnlimitedLimiterNeverDelays() {
        for (var i = 0; i < 10_000; i++) {
            Assertions.assertEquals(0, TargetConnectionLimiter.UNLIMITED.reserveConnect().delayNanos());
        }
        TargetConnectionLimiter.UNLIMITED.connectFailed(TargetConnectionLimiter.UNLIMITED.reserveConnect());
        Assertions.assertEquals(Double.POSITIVE_INFINITY, TargetConnectionLimiter.UNLIMITED.ratePerSecond());
    }

    private void failingWindow(TargetConnectionLimiter limiter, int failures, int successes) {
        completeConnects(limiter, failures, successes);
        now.addAndGet(SECOND);
        limiter.reserveConnect();
    }

    private void completeConnects(TargetConnectionLimiter limiter, int failures, int successes) {
        for (var i = 0; i < failures; i++) {
            limiter.connectFailed(limiter.reserveConnect());
        }
        for (var i = 0; i < successes; i++) {
            limiter.connectSucceeded(limiter.reserveConnect());
        }
    }

    private void saturateOneWindow(TargetConnectionLimiter limiter, int failures) {
        var rate = limiter.ratePerSecond();
        var reservations = new java.util.ArrayList<TargetConnectionLimiter.Reservation>();
        for (var i = 0; i < rate * 2; i++) {
            reservations.add(limiter.reserveConnect());
        }
        reservations.subList(0, failures).forEach(limiter::connectFailed);
        reservations.subList(failures, reservations.size()).forEach(limiter::connectSucceeded);
        now.addAndGet(SECOND);
        limiter.reserveConnect();
    }
}
