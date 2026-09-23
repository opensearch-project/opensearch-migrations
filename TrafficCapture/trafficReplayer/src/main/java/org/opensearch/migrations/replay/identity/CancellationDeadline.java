/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.identity;

/**
 * The instant by which graceful cancellation must have finished, expressed on the monotonic clock.
 *
 * <p>Monotonic rather than wall-clock, and a distinct type rather than a bare {@code long}, because a
 * cancellation deadline is compared against elapsed time and must not be affected by clock adjustment. The
 * type also keeps the deadline visible in signatures: the prior implementation threaded raw
 * {@code CancellationException}s through cancellation paths and had nowhere to put a deadline, which is part
 * of why graceful and forced cancellation were not distinguishable as values.</p>
 *
 * <p><strong>No range validation.</strong> {@link System#nanoTime()} has an arbitrary origin and is
 * explicitly permitted to be negative, so rejecting negative values here would reject legitimate deadlines
 * on some JVMs. Comparisons must therefore be written as {@code (deadline - now) <= 0} rather than
 * {@code now >= deadline}, so that they remain correct across nanosecond overflow.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 2, and
 * used by the cancellation model in {@code docs/captureAndReplay/replayerLowLevelDesign.md} section 6.</p>
 */
public record CancellationDeadline(long monotonicDeadlineNanos) {
    /**
     * @param nowNanos a reading from the same monotonic source this deadline was created from
     * @return whether the deadline has been reached, overflow-safe
     */
    public boolean hasPassed(long nowNanos) {
        return (monotonicDeadlineNanos - nowNanos) <= 0;
    }

    /**
     * @param nowNanos a reading from the same monotonic source this deadline was created from
     * @return nanoseconds remaining, zero once the deadline has passed
     */
    public long remainingNanos(long nowNanos) {
        return Math.max(0, monotonicDeadlineNanos - nowNanos);
    }
}
