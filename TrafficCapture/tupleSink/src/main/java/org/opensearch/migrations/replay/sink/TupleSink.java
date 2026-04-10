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

    /** Flush any buffered data. Called when no more items are immediately available. */
    void flush();

    /** Periodic callback even when no events arrive. Implementations should check time-based thresholds. */
    void periodicFlush();

    /** Finalize: flush, commit, complete all outstanding futures, release resources. */
    @Override
    void close();
}
