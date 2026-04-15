package org.opensearch.migrations.replay.sink;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A TupleSink that immediately completes futures and forwards the tuple map
 * to a callback. Useful for tests that need to inspect tuples synchronously.
 */
public class CallbackTupleSink implements TupleSink {
    private final Consumer<Map<String, Object>> callback;

    public CallbackTupleSink(Consumer<Map<String, Object>> callback) {
        this.callback = callback;
    }

    @Override
    public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
        try {
            callback.accept(tupleMap);
            future.complete(null);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    @Override
    public void flush() {
        // No batching needed — futures are completed immediately in accept()
    }

    @Override
    public void periodicFlush() {
        // No time-based thresholds — futures are completed immediately in accept()
    }

    @Override
    public void close() {
        // No resources to release
    }
}
