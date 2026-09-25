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
 * Thread-safe submission boundary for immutable replay-intake inputs.
 *
 * <p>The queue owns no replay state. Only the replay-intake owner removes inputs.
 *
 * <p>{@link #submit} reports whether the input was accepted, and that return value is load-bearing rather
 * than advisory: {@code procCommit §9.2} has {@code onPartitionsRevoked} wait "only until replay intake
 * accepts that force-cancellation input and then return". Acceptance proves intake will observe the
 * notification unless the process fails; it does not mean intake has acted on it.
 */
public final class ReplayIntakeInputQueue {

    sealed interface Entry permits SubmittedInput, StopAfterDraining {}

    record SubmittedInput(
        @NonNull ReplayIntakeInput input,
        CompletableFuture<Void> handled
    ) implements Entry {}

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
            }
        }
        entries.clear();
    }
}
