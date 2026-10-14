/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;

import lombok.NonNull;

/**
 * Provides the thread-safe FIFO boundary around the single-threaded replay-intake state machine.
 *
 * <p>Producers submit immutable events without gaining access to intake state. Acceptance means the event
 * is durably ordered for observation by the owner, while the optional handled future distinguishes that
 * guarantee from the later point at which the transition has actually been applied.</p>
 *
 * <p>Control entries use the same FIFO: a processing fence completes after all earlier events, and the
 * stop marker atomically rejects later submissions while allowing accepted work to drain. Abrupt closure
 * instead fails pending acknowledgements and discards queued work, making graceful and failure shutdown
 * semantics explicit.</p>
 */
public final class ReplayIntakeInputQueue {

    sealed interface Entry permits SubmittedInput, ProcessingFence, StopAfterDraining {}

    record SubmittedInput(
        @NonNull ReplayIntakeInput input,
        CompletableFuture<Void> handled
    ) implements Entry {}

    record ProcessingFence(@NonNull CompletableFuture<Void> handled) implements Entry {}

    enum StopAfterDraining implements Entry {
        INSTANCE
    }

    private final LinkedBlockingQueue<Entry> entries = new LinkedBlockingQueue<>();
    private boolean accepting = true;

    public synchronized boolean submit(@NonNull ReplayIntakeInput input) {
        if (!accepting) {
            return false;
        }
        return entries.offer(new SubmittedInput(input, null));
    }

    /**
     * Submits a correctness-required lifecycle input and completes only after replay intake applies it.
     */
    public synchronized CompletionStage<Void> submitAndAwaitHandling(
        @NonNull ReplayIntakeInput input
    ) {
        var handled = new CompletableFuture<Void>();
        if (!accepting) {
            handled.completeExceptionally(
                new IllegalStateException("Replay-intake input queue is closed; refusing " + input)
            );
            return handled.minimalCompletionStage();
        }
        if (!entries.offer(new SubmittedInput(input, handled))) {
            handled.completeExceptionally(
                new IllegalStateException("Replay-intake input queue refused " + input)
            );
        }
        return handled.minimalCompletionStage();
    }

    /** Completes after the owner has applied every input accepted before this FIFO fence. */
    public synchronized CompletionStage<Void> awaitPriorInputsHandled() {
        var handled = new CompletableFuture<Void>();
        if (!accepting) {
            handled.completeExceptionally(
                new IllegalStateException("Replay-intake input queue is closed; refusing processing fence")
            );
        } else if (!entries.offer(new ProcessingFence(handled))) {
            handled.completeExceptionally(
                new IllegalStateException("Replay-intake input queue refused processing fence")
            );
        }
        return handled.minimalCompletionStage();
    }

    /**
     * Atomically stops accepting inputs and appends the FIFO marker that terminates the owner after all
     * previously accepted inputs have been applied.
     */
    public synchronized boolean requestStopAfterDraining() {
        if (!accepting) {
            return false;
        }
        accepting = false;
        return entries.offer(StopAfterDraining.INSTANCE);
    }

    /**
     * Removes one business input for diagnostic fixtures that inspect producer output directly.
     *
     * <p>The running owner uses {@link #takeEntry()} so it can also observe queue-control markers.
     */
    public ReplayIntakeInput take() throws InterruptedException {
        var entry = entries.take();
        if (entry instanceof SubmittedInput submitted) {
            return submitted.input();
        }
        // requestStopAfterDraining atomically rejects later submissions, so no entry can exist behind this
        // marker. Re-offering therefore preserves its position for the owner rather than moving it past work.
        entries.offer(entry);
        throw new IllegalStateException("Only ReplayIntakeOwner may remove the stop-after-draining marker");
    }

    Entry takeEntry() throws InterruptedException {
        return entries.take();
    }

    public int size() {
        return entries.size();
    }

    /** Abrupt failure-path closure: reject new work and discard anything not yet removed by the owner. */
    public synchronized void closeNow() {
        accepting = false;
        for (var entry : entries) {
            if (entry instanceof SubmittedInput submitted && submitted.handled() != null) {
                submitted.handled().completeExceptionally(
                    new IllegalStateException("Replay-intake owner closed before applying input")
                );
            } else if (entry instanceof ProcessingFence fence) {
                fence.handled().completeExceptionally(
                    new IllegalStateException("Replay-intake owner closed before reaching processing fence")
                );
            }
        }
        entries.clear();
    }
}
