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
 */
public final class KafkaSourceInputQueue {

    private final ConcurrentLinkedQueue<KafkaSourceInput> inputs = new ConcurrentLinkedQueue<>();
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
        wakeupController.onInputSubmitted();
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
    }

    public boolean isClosed() {
        return closed.get();
    }
}
