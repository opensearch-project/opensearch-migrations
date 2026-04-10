package org.opensearch.migrations.replay.sink;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.opensearch.migrations.replay.ParsedHttpMessagesAsDicts;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-thread sink registry that assigns each calling thread its own {@link TupleSink}.
 *
 * <p>Since each Netty event loop is single-threaded, the per-thread sink requires
 * no synchronization.</p>
 */
@Slf4j
public class ThreadLocalTupleWriter implements AutoCloseable {
    private final ConcurrentHashMap<Long, TupleSink> sinks = new ConcurrentHashMap<>();
    private final AtomicInteger sinkIndexCounter = new AtomicInteger();
    private final IntFunction<TupleSink> sinkFactory;

    /**
     * @param sinkFactory creates a new sink for each thread, given a sink index
     */
    public ThreadLocalTupleWriter(IntFunction<TupleSink> sinkFactory) {
        this.sinkFactory = sinkFactory;
    }

    /**
     * Write a tuple. Called on a Netty event loop thread.
     */
    public CompletableFuture<Void> writeTuple(SourceTargetCaptureTuple tuple, ParsedHttpMessagesAsDicts parsed) {
        var sink = sinks.computeIfAbsent(
            Thread.currentThread().getId(),
            id -> sinkFactory.apply(sinkIndexCounter.getAndIncrement())
        );
        var future = new CompletableFuture<Void>();
        var map = parsed.toTupleMap(tuple);
        sink.accept(map, future);
        return future;
    }

    @Override
    public void close() {
        sinks.values().forEach(sink -> {
            try {
                sink.close();
            } catch (Exception e) {
                log.atError().setCause(e).setMessage("Error closing TupleSink").log();
            }
        });
        sinks.clear();
    }
}
