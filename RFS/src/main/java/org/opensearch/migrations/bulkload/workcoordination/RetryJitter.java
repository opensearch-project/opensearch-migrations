package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomizes the waits between work-coordination retries so that workers that failed together (a coordinator
 * restart, a throttling burst, every worker contending for the same lease) don't retry together.
 *
 * <p>This is "equal jitter": a wait somewhere between half the computed backoff and all of it. Each wait keeps at
 * least half its length because these retries are budgeted in attempts against waits that matter on their own
 * (letting clock drift settle, staying inside a lease). Waits never get longer, so totals computed from the
 * unjittered backoff remain upper bounds.
 */
public final class RetryJitter {
    private RetryJitter() {}

    /** A wait in {@code [delayMillis / 2, delayMillis)}; a delay below 2 ms is returned as is. */
    public static long equalJitterMillis(long delayMillis) {
        return equalJitterMillis(delayMillis, ThreadLocalRandom.current().nextDouble());
    }

    public static Duration equalJitter(Duration delay) {
        return Duration.ofMillis(equalJitterMillis(delay.toMillis()));
    }

    /** @param unitRandom a number in {@code [0, 1)} picking where in the range the wait falls */
    static long equalJitterMillis(long delayMillis, double unitRandom) {
        if (delayMillis < 2) {
            return delayMillis;
        }
        long half = delayMillis / 2;
        return half + (long) ((delayMillis - half) * unitRandom);
    }
}
