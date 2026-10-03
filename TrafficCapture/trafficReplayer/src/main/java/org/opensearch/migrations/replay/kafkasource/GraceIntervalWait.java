/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.Objects;
import java.util.function.LongSupplier;

import org.opensearch.migrations.replay.identity.CancellationDeadline;

/**
 * How {@code onPartitionsRevoked} waits out its grace interval.
 *
 * <p>Injected rather than called directly on {@link KafkaSourceInputQueue}, because {@code kafkaLLD §15.1}
 * requires the deadline to be measured with <em>one</em> process-local monotonic clock and a raw
 * {@code Object.wait} cannot honour that. Waiting always elapses in real time, so a deadline expressed on an
 * injected clock and a wait measured on the system clock are two sources for one deadline no matter how the
 * arithmetic is arranged. The only way to have a single source is to make the waiting itself injectable, which
 * is the same reason clocks, event loops and coordination are injected everywhere else in this rebuild.
 *
 * <p>The implementation returns when either an input is available or the deadline has passed <em>according to
 * the clock that created it</em>. Nothing else is a correct answer.
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
    static GraceIntervalWait blockingOn(KafkaSourceInputQueue queue, LongSupplier monotonicNanos) {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(monotonicNanos, "monotonicNanos");
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
