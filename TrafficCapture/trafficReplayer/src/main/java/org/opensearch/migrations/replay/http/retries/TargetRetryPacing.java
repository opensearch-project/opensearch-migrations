/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.http.retries;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

import lombok.NonNull;

/**
 * Decides how long a failed target attempt waits before it is tried again, for every request in one
 * replayer process.
 *
 * <p>Two limits apply. Each request backs off exponentially with equal jitter, so requests that failed
 * together do not return together. Across the process, retries are a budget earned by first attempts:
 * the sustained retry rate is at most {@code budgetRatio} times the recent first-attempt rate, with a
 * floor so that a process whose requests are all retrying still makes progress. Retries are traffic the
 * source never sent, so the budget keeps the extra load on the target proportional to the replayed load.
 *
 * <p>Thread-safe; target event loops share one instance.
 */
public final class TargetRetryPacing {
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(100);
    public static final Duration DEFAULT_MAXIMUM_DELAY = Duration.ofSeconds(30);
    public static final double DEFAULT_BUDGET_RATIO = 0.2;
    public static final double DEFAULT_BUDGET_FLOOR_PER_SECOND = 50;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long RATE_WINDOW_NANOS = NANOS_PER_SECOND;
    private static final double BURST_SECONDS = 1.0;

    private final long initialDelayNanos;
    private final long maximumDelayNanos;
    private final boolean jittered;
    private final double budgetRatio;
    private final double budgetFloorPerSecond;
    private final DoubleSupplier randomFraction;
    private final LongSupplier nanoTime;

    private final LongAdder firstAttempts = new LongAdder();
    private long rateWindowStartNanos;
    private double firstAttemptsPerSecond;
    private boolean rateObserved;
    private long nextRetrySlotNanos;
    private final LongAdder budgetDeferredRetries = new LongAdder();
    private final LongAdder throttledResponses = new LongAdder();

    private TargetRetryPacing(
        @NonNull Duration initialDelay,
        @NonNull Duration maximumDelay,
        boolean jittered,
        double budgetRatio,
        double budgetFloorPerSecond,
        @NonNull DoubleSupplier randomFraction,
        @NonNull LongSupplier nanoTime
    ) {
        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay must be at least initialDelay");
        }
        if (budgetRatio < 0 || !Double.isFinite(budgetRatio)) {
            throw new IllegalArgumentException("budgetRatio must be finite and non-negative");
        }
        if (budgetFloorPerSecond <= 0 || Double.isNaN(budgetFloorPerSecond)) {
            throw new IllegalArgumentException("budgetFloorPerSecond must be positive");
        }
        this.initialDelayNanos = initialDelay.toNanos();
        this.maximumDelayNanos = maximumDelay.toNanos();
        this.jittered = jittered;
        this.budgetRatio = budgetRatio;
        this.budgetFloorPerSecond = budgetFloorPerSecond;
        this.randomFraction = randomFraction;
        this.nanoTime = nanoTime;
        this.rateWindowStartNanos = nanoTime.getAsLong();
        this.nextRetrySlotNanos = Long.MIN_VALUE;
    }

    /** Exponential backoff without jitter or a process budget, for tests that assert exact delays. */
    public static TargetRetryPacing deterministic(@NonNull Duration initialDelay, @NonNull Duration maximumDelay) {
        return new TargetRetryPacing(
            initialDelay,
            maximumDelay,
            false,
            0,
            Double.POSITIVE_INFINITY,
            () -> 0.0,
            System::nanoTime
        );
    }

    public static TargetRetryPacing deployed(
        @NonNull Duration maximumDelay,
        double budgetRatio,
        double budgetFloorPerSecond
    ) {
        return paced(
            DEFAULT_INITIAL_DELAY,
            maximumDelay,
            budgetRatio,
            budgetFloorPerSecond,
            () -> ThreadLocalRandom.current().nextDouble(),
            System::nanoTime
        );
    }

    public static TargetRetryPacing paced(
        @NonNull Duration initialDelay,
        @NonNull Duration maximumDelay,
        double budgetRatio,
        double budgetFloorPerSecond,
        @NonNull DoubleSupplier randomFraction,
        @NonNull LongSupplier nanoTime
    ) {
        return new TargetRetryPacing(
            initialDelay,
            maximumDelay,
            true,
            budgetRatio,
            budgetFloorPerSecond,
            randomFraction,
            nanoTime
        );
    }

    public void firstAttemptStarted() {
        firstAttempts.increment();
    }

    /** Retries whose start was pushed past their backoff because the process budget was spent. */
    public long budgetDeferredRetries() {
        return budgetDeferredRetries.sum();
    }

    public void throttledResponseObserved() {
        throttledResponses.increment();
    }

    /** Target responses classified as load shedding, which retry without spending the response-retry limit. */
    public long throttledResponses() {
        return throttledResponses.sum();
    }

    public Duration delayBeforeRetry(int completedAttemptCount) {
        if (completedAttemptCount <= 0) {
            throw new IllegalArgumentException("completedAttemptCount must be positive");
        }
        var backoffNanos = backoffNanos(completedAttemptCount);
        if (Double.isInfinite(budgetFloorPerSecond)) {
            return Duration.ofNanos(backoffNanos);
        }
        var now = nanoTime.getAsLong();
        var slot = reserveRetrySlot(now);
        if (slot - now > backoffNanos) {
            budgetDeferredRetries.increment();
            return Duration.ofNanos(slot - now);
        }
        return Duration.ofNanos(backoffNanos);
    }

    private long backoffNanos(int completedAttemptCount) {
        var ceiling = initialDelayNanos;
        for (var attempt = 1; attempt < completedAttemptCount && ceiling < maximumDelayNanos; attempt++) {
            ceiling = ceiling > maximumDelayNanos / 2 ? maximumDelayNanos : ceiling * 2;
        }
        ceiling = Math.min(ceiling, maximumDelayNanos);
        if (!jittered) {
            return ceiling;
        }
        var half = ceiling / 2;
        return half + (long) (randomFraction.getAsDouble() * (ceiling - half));
    }

    private synchronized long reserveRetrySlot(long now) {
        var elapsed = now - rateWindowStartNanos;
        if (elapsed >= RATE_WINDOW_NANOS) {
            var observed = firstAttempts.sumThenReset() * (double) NANOS_PER_SECOND / elapsed;
            firstAttemptsPerSecond = rateObserved ? (firstAttemptsPerSecond + observed) / 2 : observed;
            rateObserved = true;
            rateWindowStartNanos = now;
        }
        var retriesPerSecond = Math.max(budgetFloorPerSecond, budgetRatio * firstAttemptsPerSecond);
        var intervalNanos = Math.max(1L, (long) (NANOS_PER_SECOND / retriesPerSecond));
        var earliestUnspent = now - (long) (BURST_SECONDS * NANOS_PER_SECOND);
        var slot = Math.max(nextRetrySlotNanos, earliestUnspent);
        nextRetrySlotNanos = slot + intervalNanos;
        return Math.max(slot, now);
    }
}
