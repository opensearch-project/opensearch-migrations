package org.opensearch.migrations.bulkload.workcoordination;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomizes the waits between work-coordination retries so that workers that failed together (a coordinator
 * restart, a throttling burst, every worker contending for the same lease) don't retry together.
 *
 * <p>Most coordination retries use "equal jitter": a wait somewhere between half the computed backoff and all of
 * it. Completion retries use upward jitter so their configured retry window is not shortened: a wait between the
 * computed backoff and the next doubling step, without exceeding the configured maximum delay.
 */
public final class RetryJitter {
    private RetryJitter() {}

    /** A wait in {@code [delayMillis / 2, delayMillis)}; a delay below 2 ms is returned as is. */
    public static long equalJitterMillis(long delayMillis) {
        return equalJitterMillis(delayMillis, ThreadLocalRandom.current().nextDouble());
    }

    /** @param unitRandom a number in {@code [0, 1)} picking where in the range the wait falls */
    static long equalJitterMillis(long delayMillis, double unitRandom) {
        if (delayMillis < 2) {
            return delayMillis;
        }
        long half = delayMillis / 2;
        return half + (long) ((delayMillis - half) * unitRandom);
    }

    /**
     * A wait in {@code [min(delayMillis, maxDelayMillis), maxDelayMillis]}, with an exclusive upper bound when
     * there is room below the cap. Before the cap, the random range extends at most one doubling step upward.
     */
    public static long upwardJitterMillis(long delayMillis, long maxDelayMillis) {
        return upwardJitterMillis(delayMillis, maxDelayMillis, ThreadLocalRandom.current().nextDouble());
    }

    /** @param unitRandom a number in {@code [0, 1)} picking where in the range the wait falls */
    static long upwardJitterMillis(long delayMillis, long maxDelayMillis, double unitRandom) {
        long floor = Math.min(delayMillis, maxDelayMillis);
        long width = Math.min(floor, maxDelayMillis - floor);
        return floor + (long) (width * unitRandom);
    }
}
