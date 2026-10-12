/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource.assignor;

import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * One-shot deadline bridge between {@link QuietWindowAssignor} and the Kafka owner loop.
 *
 * <p>The assignor cannot call the consumer; only the Kafka owner thread may. When the assignment
 * leader wants a rebalance round after the quiet window elapses, it arms a deadline here. The owner
 * loop consumes it ({@link #takeDueDeadline}) and calls {@code consumer.enforceRebalance()}, and
 * bounds its poll timeout with {@link #millisUntilDeadline} so the deadline is observed promptly
 * without a busy loop.</p>
 *
 * <p>A deadline is delivered at most once, and every {@code assign()} round cancels and possibly
 * re-arms it, so a stale deadline can cause at most one extra assignment round and can never
 * authorize a stale decision — the decision is always remade inside {@code assign()}.</p>
 *
 * <p>The control owns the monotonic clock, so the assignor that arms deadlines and the owner loop that
 * consumes them agree on what "now" is. A control nothing arms is inert.</p>
 */
public final class AssignorControl {

    public static final long NO_DEADLINE = Long.MIN_VALUE;

    private final LongSupplier monotonicMillis;
    private long deadlineMillis = NO_DEADLINE;

    public AssignorControl() {
        this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public AssignorControl(LongSupplier monotonicMillis) {
        this.monotonicMillis = monotonicMillis;
    }

    public long nowMillis() {
        return monotonicMillis.getAsLong();
    }

    /** Arms the deadline, replacing any previously armed one. Called from the assignor callbacks. */
    public synchronized void arm(long absoluteDeadlineMillis) {
        deadlineMillis = absoluteDeadlineMillis;
    }

    /** Clears any armed deadline. Called at the start of every assignment round and on non-leaders. */
    public synchronized void cancel() {
        deadlineMillis = NO_DEADLINE;
    }

    /**
     * Returns true at most once per armed deadline, when {@code nowMillis} has reached it. The caller
     * reacts by requesting a rebalance round; consuming the deadline here prevents repeated
     * {@code enforceRebalance()} calls while Kafka is already starting the requested round.
     */
    public synchronized boolean takeDueDeadline(long nowMillis) {
        if (deadlineMillis != NO_DEADLINE && nowMillis >= deadlineMillis) {
            deadlineMillis = NO_DEADLINE;
            return true;
        }
        return false;
    }

    /** Time until the armed deadline, for bounding a poll timeout. {@code Long.MAX_VALUE} when unarmed. */
    public synchronized long millisUntilDeadline(long nowMillis) {
        return deadlineMillis == NO_DEADLINE ? Long.MAX_VALUE : Math.max(0, deadlineMillis - nowMillis);
    }

    /** The armed deadline if any, for observability and tests. Does not consume it. */
    public synchronized OptionalLong peekDeadline() {
        return deadlineMillis == NO_DEADLINE ? OptionalLong.empty() : OptionalLong.of(deadlineMillis);
    }
}
