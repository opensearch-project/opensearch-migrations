/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.

// REBUILD-LIMBO-START(G11)
/*

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

import lombok.NonNull;

*/
// REBUILD-LIMBO-END(G11)
/**
 * Thread-safe submission boundary for immutable replay-intake inputs.
 *
 * <p>The queue owns no replay state. Only {@link ReplayIntakeOwner} removes inputs.
 */
// REBUILD-LIMBO-START(G11)
/*
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

*/
// REBUILD-LIMBO-END(G11)