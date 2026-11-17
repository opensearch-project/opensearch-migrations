/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.function.LongSupplier;

import org.opensearch.migrations.replay.identity.CancellationDeadline;

import lombok.NonNull;

/**
 * Waits for source-control input during a deadline-bounded Kafka revocation grace period.
 *
 * <p>The grace deadline belongs to one monotonic clock, while ordinary blocking primitives measure elapsed
 * real time internally. This abstraction keeps the decision on the deadline's clock: the production
 * implementation waits in bounded slices, wakes immediately when the source queue is signalled, and
 * rechecks the monotonic deadline after every slice.</p>
 *
 * <p>As a result, queued completion or cleanup events can still be applied while the rebalance callback is
 * protected from Kafka wakeups, and clock control remains deterministic in tests. The wait returns only
 * because input is available or the supplied deadline has actually passed.</p>
 */
public interface GraceIntervalWait {

    /**
     * Waits until an input is queued or {@code deadline} has passed on its own clock.
     *
     * @return true if an input is available, false if the deadline passed first
     */
    boolean awaitInputUntil(CancellationDeadline deadline) throws InterruptedException;

    /**
     * The production wait: blocks on the queue's signal, in slices, re-reading the deadline's own clock each
     * time it wakes.
     *
     * <p>Slicing matters. A single wait sized from the deadline would return on elapsed real time and could not
     * then say whether the deadline had actually passed; re-checking the clock after each slice means the clock
     * that created the deadline is the only thing that ends the interval. The slice is a ceiling on how long a
     * clock adjustment can go unnoticed, not a polling interval — a submission still wakes the wait
     * immediately.
     */
    static GraceIntervalWait blockingOn(
        @NonNull KafkaSourceInputQueue queue,
        @NonNull LongSupplier monotonicNanos
    ) {
        var sliceNanos = java.time.Duration.ofMillis(25).toNanos();
        return deadline -> {
            while (!deadline.hasPassed(monotonicNanos.getAsLong())) {
                var remainingNanos = deadline.remainingNanos(monotonicNanos.getAsLong());
                if (queue.awaitInput(Math.min(remainingNanos, sliceNanos))) {
                    return true;
                }
            }
            return false;
        };
    }
}
