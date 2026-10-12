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
 * front end is failing handshakes is not helped by more handshakes. The limiter adjusts over one-second
 * windows: after {@link #FAILING_WINDOWS_TO_DECREASE} consecutive windows in which more than
 * {@link #FAILURE_FRACTION} of completed connects and TLS handshakes failed, it halves the allowed
 * connection rate, but one incident never takes the rate below {@link #INCIDENT_FLOOR_FRACTION} of where
 * it started. While connects are succeeding and demand fills the allowance it grows the rate, quickly
 * until the first decrease and more gently after. A healthy target sheds a few percent of handshakes
 * all the time, so only sustained, substantial failure counts. When the allowance exceeds demand,
 * connects are not delayed.
 *
 * <p>Each decrease starts a new epoch, and only connects reserved in the current epoch are judged. A
 * connect that was started at the old rate and fails afterwards is evidence about the old rate, so it
 * cannot halve the new one.
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
    static final double FAILURE_FRACTION = 0.15;
    static final int FAILING_WINDOWS_TO_DECREASE = 2;
    static final double INCIDENT_FLOOR_FRACTION = 0.25;
    static final long MINIMUM_FAILURES_TO_DECREASE = 5;
    static final long MINIMUM_COMPLETIONS_TO_DECREASE = 20;
    static final double SLOW_START_GROWTH = 1.5;
    static final double STEADY_GROWTH = 1.25;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long WINDOW_NANOS = NANOS_PER_SECOND;
    private static final double BURST_SECONDS = 0.1;

    /** Identifies one reserved connect so its outcome is credited to the epoch it was started in. */
    public record Reservation(long delayNanos, long epoch) {}

    /** Admits every connect immediately and records nothing. */
    public static final TargetConnectionLimiter UNLIMITED = new TargetConnectionLimiter();

    private final boolean limiting;
    private final LongSupplier nanoTime;
    private double ratePerSecond;
    private boolean decreasedOnce;
    private int consecutiveFailingWindows;
    private double incidentStartRate;
    private long epoch;
    private long windowStartNanos;
    private long windowReservations;
    private long windowDelayedReservations;
    private long windowSuccesses;
    private long windowFailures;
    private long nextSlotNanos;
    private long totalDelayedReservations;
    private long totalDelayNanos;
    private long totalRateDecreases;
    private long totalFailures;

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

    /** Reserves the next connect; the caller waits {@link Reservation#delayNanos()} before starting it. */
    public Reservation reserveConnect() {
        if (!limiting) {
            return new Reservation(0, 0);
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
                totalDelayNanos += slot - now;
                return new Reservation(slot - now, epoch);
            }
            return new Reservation(0, epoch);
        }
    }

    /** Records a connect that produced a usable channel, including any TLS handshake. */
    public void connectSucceeded(@NonNull Reservation reservation) {
        if (!limiting) {
            return;
        }
        synchronized (this) {
            rollWindows(nanoTime.getAsLong());
            if (reservation.epoch() == epoch) {
                windowSuccesses++;
            }
        }
    }

    /** Records a TCP connect or TLS handshake that failed. */
    public void connectFailed(@NonNull Reservation reservation) {
        if (!limiting) {
            return;
        }
        synchronized (this) {
            rollWindows(nanoTime.getAsLong());
            totalFailures++;
            if (reservation.epoch() == epoch) {
                windowFailures++;
            }
        }
    }

    public synchronized double ratePerSecond() {
        return limiting ? ratePerSecond : Double.POSITIVE_INFINITY;
    }

    public synchronized long totalDelayedReservations() {
        return totalDelayedReservations;
    }

    public synchronized long totalDelayMillis() {
        return totalDelayNanos / 1_000_000;
    }

    public synchronized long totalRateDecreases() {
        return totalRateDecreases;
    }

    public synchronized long totalFailures() {
        return totalFailures;
    }

    public boolean isLimiting() {
        return limiting;
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
        var conclusive = completed >= MINIMUM_COMPLETIONS_TO_DECREASE;
        var failing = conclusive
            && windowFailures >= MINIMUM_FAILURES_TO_DECREASE
            && windowFailures > FAILURE_FRACTION * completed;
        if (failing) {
            if (++consecutiveFailingWindows >= FAILING_WINDOWS_TO_DECREASE) {
                decrease();
            }
            return;
        }
        if (conclusive) {
            consecutiveFailingWindows = 0;
            incidentStartRate = 0;
        } else if (windowFailures > 0) {
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

    private void decrease() {
        if (incidentStartRate == 0) {
            incidentStartRate = ratePerSecond;
        }
        var previous = ratePerSecond;
        ratePerSecond = Math.max(
            MINIMUM_RATE,
            Math.max(incidentStartRate * INCIDENT_FLOOR_FRACTION, ratePerSecond / 2)
        );
        consecutiveFailingWindows = 0;
        decreasedOnce = true;
        epoch++;
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
    }
}
