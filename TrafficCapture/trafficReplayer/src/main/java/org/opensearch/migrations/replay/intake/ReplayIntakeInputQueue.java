/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

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

    private final LinkedBlockingQueue<ReplayIntakeInput> inputs = new LinkedBlockingQueue<>();
    private boolean accepting = true;

    public synchronized boolean submit(@NonNull ReplayIntakeInput input) {
        if (!accepting) {
            return false;
        }
        return inputs.offer(input);
    }

    public ReplayIntakeInput take() throws InterruptedException {
        return inputs.take();
    }

    public int size() {
        return inputs.size();
    }

    public synchronized void close() {
        accepting = false;
        inputs.clear();
    }
}
// REBUILD-LIMBO-START(G3)
// The QueuedInput wrapper and its CompletableFuture<Void> handled, which let a submitter observe that
// intake finished applying one input, and the close() path that fails those futures with
// RejectedExecutionException. G2 needs only acceptance, so the queue carries bare inputs; G3 restores this
// when the intake owner has something to signal back.
/*
    record QueuedInput(
        @NonNull ReplayIntakeInput input,
        CompletableFuture<Void> handled
    ) {}

    synchronized boolean submit(
        @NonNull ReplayIntakeInput input,
        CompletableFuture<Void> handled
    ) {
        if (!accepting) {
            return false;
        }
        return inputs.offer(new QueuedInput(input, handled));
    }

    synchronized void close() {
        if (!accepting) {
            return;
        }
        accepting = false;
        QueuedInput queued;
        while ((queued = inputs.poll()) != null) {
            if (queued.handled() != null) {
                queued.handled().completeExceptionally(
                    new RejectedExecutionException(
                        "Replay-intake input queue closed before queued "
                            + queued.input().getClass().getSimpleName()
                            + " was handled"
                    )
                );
            }
        }
    }
*/
// REBUILD-LIMBO-END(G3)
