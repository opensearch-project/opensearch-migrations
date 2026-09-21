/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.LinkedBlockingQueue;

import lombok.NonNull;

/**
 * Thread-safe submission boundary for immutable replay-intake inputs.
 *
 * <p>The queue owns no replay state. Only {@link ReplayIntakeOwner} removes inputs.
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

    ReplayIntakeInput take() throws InterruptedException {
        return inputs.take();
    }

    synchronized void close() {
        accepting = false;
    }
}
