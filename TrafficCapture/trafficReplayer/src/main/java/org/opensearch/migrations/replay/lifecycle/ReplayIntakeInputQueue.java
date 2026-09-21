/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

import lombok.NonNull;

/**
 * Thread-safe submission boundary for immutable replay-intake inputs.
 *
 * <p>The queue owns no replay state. Only {@link ReplayIntakeOwner} removes inputs.
 */
public final class ReplayIntakeInputQueue {
    record QueuedInput(
        @NonNull ReplayIntakeInput input,
        CompletableFuture<Void> handled
    ) {}

    private final LinkedBlockingQueue<QueuedInput> inputs = new LinkedBlockingQueue<>();
    private boolean accepting = true;

    public synchronized boolean submit(@NonNull ReplayIntakeInput input) {
        return submit(input, null);
    }

    synchronized boolean submit(
        @NonNull ReplayIntakeInput input,
        CompletableFuture<Void> handled
    ) {
        if (!accepting) {
            return false;
        }
        return inputs.offer(new QueuedInput(input, handled));
    }

    QueuedInput take() throws InterruptedException {
        return inputs.take();
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
}
