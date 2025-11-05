package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Duration;

/**
 * Calculates lease durations with exponential backoff.
 * Prevents overflow by capping the maximum lease duration.
 */
class LeaseCalculator {
    private static final int MAX_EXPONENT = 10; // Caps at ~1024x base duration
    private static final long MAX_LEASE_SECONDS = Duration.ofDays(7).toSeconds();

    static long calculateLeaseDuration(Duration baseDuration, int exponent) {
        var cappedExponent = Math.min(exponent, MAX_EXPONENT);
        var multiplier = 1L << cappedExponent;
        var calculatedDuration = baseDuration.toSeconds() * multiplier;
        return Math.min(calculatedDuration, MAX_LEASE_SECONDS);
    }
}
