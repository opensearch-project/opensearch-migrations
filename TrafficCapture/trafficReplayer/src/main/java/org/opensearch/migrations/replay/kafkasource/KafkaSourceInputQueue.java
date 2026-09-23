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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Carries {@link KafkaSourceInput} from other owners to the Kafka thread, which is the only thread that
 * removes and applies them. Defined by {@code kafkaLLD §4.2}.
 *
 * <p>The order of the two steps in {@link #submit} is the contract, not an implementation detail: the value
 * is queued <strong>before</strong> any wakeup is considered, so a wakeup can never arrive pointing at work
 * that is not yet visible. {@code kafkaLLD §5.4} states it as "submitting a source input always places the
 * immutable value in the queue first".
 *
 * <p>Submission reports acceptance and never silently discards. Once closed, submission is refused rather
 * than dropped, because an input that vanishes is a record that never completes
 * ({@code kafkaLLD §4.2}).
 *
 * <p>Submission also signals {@link #awaitInput(long)} directly. That is how a revocation callback waiting
 * for its grace deadline learns about commit and lifecycle inputs: {@code kafkaLLD §15.1} requires that
 * "queue submission signals the callback's wait directly; it does not call {@code KafkaConsumer.wakeup()}
 * while callback handling is protected from wakeup". The signal and the wakeup are therefore two separate
 * mechanisms, and only the signal reaches a callback.
 */
public final class KafkaSourceInputQueue {

    private final ConcurrentLinkedQueue<KafkaSourceInput> inputs = new ConcurrentLinkedQueue<>();
    /** Separate from the Kafka wakeup: this is the only notification a protected callback may receive. */
    private final Object signal = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final WakeupController wakeupController;

    public KafkaSourceInputQueue(WakeupController wakeupController) {
        this.wakeupController = Objects.requireNonNull(wakeupController, "wakeupController");
    }

    /**
     * Queues an input and prompts the Kafka thread if it may be waiting in {@code poll()}.
     *
     * @throws IllegalStateException if the queue is closed, so a caller learns its input was not accepted
     *                               instead of losing it
     */
    public void submit(KafkaSourceInput input) {
        Objects.requireNonNull(input, "input");
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
     * Waits until an input is queued or the monotonic deadline passes, whichever happens first. Used by
     * {@code onPartitionsRevoked} to process commit and lifecycle inputs while it waits out the grace
     * interval, without polling and without a Kafka wakeup it is not allowed to receive.
     *
     * <p>The deadline is {@code System.nanoTime()}-based to match {@code CancellationDeadline}: a wall-clock
     * change must not be able to shorten or extend the configured grace interval
     * ({@code kafkaLLD §15.1}).
     *
     * @return true if an input is available, false if the deadline passed first
     */
    public boolean awaitInput(long monotonicDeadlineNanos) throws InterruptedException {
        synchronized (signal) {
            while (inputs.isEmpty()) {
                var remainingNanos = monotonicDeadlineNanos - System.nanoTime();
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
