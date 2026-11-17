/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.NonNull;

/**
 * Carries source-state events safely from concurrent producers to the Kafka consumer thread.
 *
 * <p>Submission first makes the immutable value visible, then signals both coordination mechanisms. A
 * direct condition signal wakes a revocation callback that is waiting through its grace interval; a
 * controlled Kafka wakeup may interrupt a long poll when the consumer is in a phase where interruption is
 * safe. Keeping those mechanisms separate prevents a wakeup from disrupting rebalance or commit work.</p>
 *
 * <p>The queue rejects submissions after closure rather than silently losing correctness-critical events.
 * Draining is bounded to the entries present at the start of the call so a continuous stream of control
 * messages cannot starve Kafka polling, and timed waiting accepts a duration so the owner retains authority
 * over the monotonic deadline.</p>
 */
public final class KafkaSourceInputQueue {

    private final ConcurrentLinkedQueue<KafkaSourceInput> inputs = new ConcurrentLinkedQueue<>();
    /** Separate from the Kafka wakeup: this is the only notification a protected callback may receive. */
    private final Object signal = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final WakeupController wakeupController;

    public KafkaSourceInputQueue(@NonNull WakeupController wakeupController) {
        this.wakeupController = wakeupController;
    }

    /**
     * Queues an input and prompts the Kafka thread if it may be waiting in {@code poll()}.
     *
     * @throws IllegalStateException if the queue is closed, so a caller learns its input was not accepted
     *                               instead of losing it
     */
    public void submit(@NonNull KafkaSourceInput input) {
        if (closed.get()) {
            throw new IllegalStateException("Kafka source input queue is closed; refusing " + input);
        }
        inputs.add(input);
        synchronized (signal) {
            signal.notifyAll();
        }
        wakeupController.onInputSubmitted();
    }

    /**
     * Waits up to {@code maxWaitNanos} for an input to be queued. Used by {@code onPartitionsRevoked} to
     * process commit and lifecycle inputs while it waits out the grace interval, without polling and without
     * a Kafka wakeup it is not allowed to receive.
     *
     * @param maxWaitNanos how long to wait at most, derived by the caller from its own monotonic clock. Zero
     *                     or negative means do not wait, which is how a caller whose deadline has already
     *                     passed still gets an honest answer about what is queued
     * @return true if an input is available, false if the wait elapsed first
     */
    public boolean awaitInput(long maxWaitNanos) throws InterruptedException {
        if (maxWaitNanos <= 0) {
            return !inputs.isEmpty();
        }
        // Elapsed real time, measured locally. Object.wait cannot be driven by an injected clock, so the
        // caller's clock decides how long to wait and this decides only when that much has passed.
        var waitUntilNanos = System.nanoTime() + maxWaitNanos;
        synchronized (signal) {
            while (inputs.isEmpty()) {
                var remainingNanos = waitUntilNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                // Millisecond granularity is what Object.wait offers; the nanos argument only refines the
                // final millisecond, and rounding up by one avoids a zero timeout meaning "wait forever".
                signal.wait(remainingNanos / 1_000_000L + 1);
            }
            return true;
        }
    }

    /** Removes the next input, or empty if none is queued. Kafka thread only. */
    public Optional<KafkaSourceInput> poll() {
        return Optional.ofNullable(inputs.poll());
    }

    /**
     * Drains everything queued at the moment of the call.
     *
     * <p>Bounded to what is already present rather than looping until empty, so a steady stream of
     * submissions cannot starve the poll that the drained inputs are waiting on.
     */
    public List<KafkaSourceInput> drain() {
        var drained = new ArrayList<KafkaSourceInput>();
        for (var queued = inputs.size(); queued > 0; queued--) {
            var input = inputs.poll();
            if (input == null) {
                break;
            }
            drained.add(input);
        }
        return drained;
    }

    public boolean isEmpty() {
        return inputs.isEmpty();
    }

    public int size() {
        return inputs.size();
    }

    /** Refuses further submissions. Already-queued inputs remain drainable so shutdown can finish them. */
    public void close() {
        closed.set(true);
        synchronized (signal) {
            // Release any waiter so a closing source does not sit out a grace interval it can no longer use.
            signal.notifyAll();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
