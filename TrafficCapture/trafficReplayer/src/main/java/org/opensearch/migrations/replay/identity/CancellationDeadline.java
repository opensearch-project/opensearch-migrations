/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.identity;

/**
 * Represents the monotonic-clock instant at which graceful cancellation must become forced cancellation.
 *
 * <p>Cancellation limits elapsed work, so wall-clock changes must not move the deadline. Keeping the value
 * in a dedicated type also prevents code from confusing an absolute monotonic instant with a duration or
 * an epoch timestamp.</p>
 *
 * <p>{@link System#nanoTime()} has an arbitrary origin and may be negative, so the value deliberately has
 * no range validation. Comparisons subtract the current reading from the deadline; this preserves the
 * monotonic-clock wraparound semantics and lets remaining time clamp cleanly to zero.</p>
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
