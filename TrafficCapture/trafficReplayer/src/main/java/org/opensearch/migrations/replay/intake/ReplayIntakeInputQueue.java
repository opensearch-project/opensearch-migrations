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
