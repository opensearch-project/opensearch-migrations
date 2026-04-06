package org.opensearch.migrations.replay.sink;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable destination for replay tuples. Receives structured tuple data and owns
 * both serialization and future lifecycle. Batch sinks complete all futures at once
 * on commit; streaming sinks complete per-item as acks arrive.
 *
 * <p>Each instance is single-threaded — one per Netty event loop thread.</p>
 */
public interface TupleSink extends AutoCloseable {
    /** Accept one tuple. The sink owns the future and must complete it when durability is confirmed. */
    void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future);

    /** Hint that no more items are immediately available. Batch sinks should flush buffers. */
    void onEndOfBatch();

    /** Periodic callback even when no events arrive. Batch sinks should check time-based thresholds. */
    void onIdle();

    /** Finalize: flush, commit, complete all outstanding futures, release resources. */
    @Override
    void close();
}
