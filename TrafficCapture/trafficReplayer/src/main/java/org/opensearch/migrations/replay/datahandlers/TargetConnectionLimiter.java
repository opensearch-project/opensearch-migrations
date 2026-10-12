/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.datahandlers;

import java.util.function.LongSupplier;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Paces new target connections for one replayer process.
 *
 * <p>Replayed traffic opens roughly one target connection per source connection, and a target whose
 * front end is failing handshakes is not helped by more handshakes. The limiter is additive-increase,
 * multiplicative-decrease over one-second windows: it halves the allowed connection rate when a
 * window's connect and handshake failures exceed {@link #FAILURE_FRACTION}, and while connects are
 * succeeding and demand fills the allowance it grows the rate, quickly until the first decrease and
 * gently after. When the target is healthy and the allowance exceeds demand, connects are not delayed.
 *
 * <p>Thread-safe; target event loops share one instance.
 */
@Slf4j
public final class TargetConnectionLimiter {
    public static final double DEFAULT_INITIAL_RATE = 200;
    public static final double MINIMUM_INITIAL_RATE = 10;
    public static final double MAXIMUM_INITIAL_RATE = 1_000_000;
    static final double MINIMUM_RATE = MINIMUM_INITIAL_RATE;
    static final double MAXIMUM_RATE = MAXIMUM_INITIAL_RATE;
    static final double FAILURE_FRACTION = 0.05;
    static final long MINIMUM_FAILURES_TO_DECREASE = 5;
    static final double SLOW_START_GROWTH = 1.5;
    static final double STEADY_GROWTH = 1.1;
    static final int DECREASE_COOLDOWN_WINDOWS = 2;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long WINDOW_NANOS = NANOS_PER_SECOND;
    private static final double BURST_SECONDS = 0.1;

    /** Admits every connect immediately and records nothing. */
    public static final TargetConnectionLimiter UNLIMITED = new TargetConnectionLimiter();

    private final boolean limiting;
    private final LongSupplier nanoTime;
    private double ratePerSecond;
    private boolean decreasedOnce;
    private int cooldownWindows;
    private long windowStartNanos;
    private long windowReservations;
    private long windowDelayedReservations;
    private long windowSuccesses;
    private long windowFailures;
    private long nextSlotNanos;
    private long totalDelayedReservations;
    private long totalRateDecreases;

    private TargetConnectionLimiter() {
        this.limiting = false;
        this.nanoTime = System::nanoTime;
    }

    public TargetConnectionLimiter(double initialRatePerSecond, @NonNull LongSupplier nanoTime) {
        if (!(initialRatePerSecond >= MINIMUM_RATE) || initialRatePerSecond > MAXIMUM_RATE) {
            throw new IllegalArgumentException(
                "initial connection rate must be between " + MINIMUM_RATE + " and " + MAXIMUM_RATE
            );
        }
        this.limiting = true;
        this.nanoTime = nanoTime;
        this.ratePerSecond = initialRatePerSecond;
        this.windowStartNanos = nanoTime.getAsLong();
        this.nextSlotNanos = Long.MIN_VALUE;
    }

    /** Reserves the next connect and returns how long the caller must wait before starting it. */
    public long reserveConnectDelayNanos() {
        if (!limiting) {
            return 0;
        }
        synchronized (this) {
            var now = nanoTime.getAsLong();
            rollWindows(now);
            var interval = (long) (NANOS_PER_SECOND / ratePerSecond);
            var slot = Math.max(nextSlotNanos, now - (long) (BURST_SECONDS * NANOS_PER_SECOND));
            nextSlotNanos = slot + interval;
            windowReservations++;
            if (slot > now) {
                windowDelayedReservations++;
                totalDelayedReservations++;
                return slot - now;
            }
            return 0;
        }
    }

    /** Records a connect that produced a usable channel, including any TLS handshake. */
    public void connectSucceeded() {
        if (!limiting) {
            return;
        }
        synchronized (this) {
            rollWindows(nanoTime.getAsLong());
            windowSuccesses++;
        }
    }

    /** Records a TCP connect or TLS handshake that failed. */
    public void connectFailed() {
        if (!limiting) {
            return;
        }
        synchronized (this) {
            rollWindows(nanoTime.getAsLong());
            windowFailures++;
        }
    }

    public synchronized double ratePerSecond() {
        return limiting ? ratePerSecond : Double.POSITIVE_INFINITY;
    }

    public synchronized long totalDelayedReservations() {
        return totalDelayedReservations;
    }

    public synchronized long totalRateDecreases() {
        return totalRateDecreases;
    }

    private void rollWindows(long now) {
        if (now - windowStartNanos < WINDOW_NANOS) {
            return;
        }
        adjustRate();
        windowStartNanos = now;
        windowReservations = 0;
        windowDelayedReservations = 0;
        windowSuccesses = 0;
        windowFailures = 0;
    }

    private void adjustRate() {
        var completed = windowSuccesses + windowFailures;
        var failing = windowFailures >= MINIMUM_FAILURES_TO_DECREASE
            && windowFailures > FAILURE_FRACTION * completed;
        if (cooldownWindows > 0) {
            cooldownWindows--;
            return;
        }
        if (failing) {
            var previous = ratePerSecond;
            ratePerSecond = Math.max(MINIMUM_RATE, ratePerSecond / 2);
            decreasedOnce = true;
            cooldownWindows = DECREASE_COOLDOWN_WINDOWS;
            totalRateDecreases++;
            log.atInfo()
                .setMessage(
                    "event=target_connection_rate_decreased previous_per_s={} current_per_s={} "
                        + "window_failures={} window_successes={}"
                )
                .addArgument(() -> Math.round(previous))
                .addArgument(() -> Math.round(ratePerSecond))
                .addArgument(windowFailures)
                .addArgument(windowSuccesses)
                .log();
            return;
        }
        var demandFilledAllowance = windowDelayedReservations > 0
            || windowReservations >= ratePerSecond * (WINDOW_NANOS / (double) NANOS_PER_SECOND);
        if (demandFilledAllowance) {
            ratePerSecond = Math.min(
                MAXIMUM_RATE,
                ratePerSecond * (decreasedOnce ? STEADY_GROWTH : SLOW_START_GROWTH)
            );
        }
    }
}
