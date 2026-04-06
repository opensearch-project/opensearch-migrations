package org.opensearch.migrations.replay.sink;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.opensearch.migrations.replay.ParsedHttpMessagesAsDicts;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-thread tuple writer that eliminates the need for a shared Disruptor.
 *
 * <p>Each calling thread gets its own {@link TupleSink} instance (typically a
 * {@link GzipJsonLinesSink}). Since each Netty event loop is single-threaded,
 * the per-thread sink requires no synchronization — both the gzip write and the
 * flush+fsync happen on the calling thread.</p>
 *
 * <p>The fsync cost (~1ms for small writes to Mountpoint S3) is comparable to the
 * Disruptor publish latency it replaces, and keeps the single-threaded invariant
 * that {@link GzipJsonLinesSink} depends on.</p>
 */
@Slf4j
public class ThreadLocalTupleWriter implements AutoCloseable {
    private final ConcurrentHashMap<Long, TupleSink> sinks = new ConcurrentHashMap<>();
    private final AtomicInteger threadIndexCounter = new AtomicInteger();
    private final IntFunction<TupleSink> sinkFactory;

    /**
     * @param sinkFactory creates a new sink for each thread, given a thread index
     */
    public ThreadLocalTupleWriter(IntFunction<TupleSink> sinkFactory) {
        this.sinkFactory = sinkFactory;
    }

    /**
     * Convenience constructor for the common GzipJsonLinesSink case.
     */
    public ThreadLocalTupleWriter(Path outputDir, long maxFileSizeBytes, Duration maxFileAge) {
        this(threadIndex -> new GzipJsonLinesSink(outputDir, maxFileSizeBytes, maxFileAge, threadIndex));
    }

    /**
     * Write a tuple. Called on a Netty event loop thread.
     *
     * <p>Both the gzip write and the flush+fsync happen synchronously on the calling
     * thread, preserving the single-threaded invariant that the sink depends on.</p>
     */
    public CompletableFuture<Void> writeTuple(SourceTargetCaptureTuple tuple, ParsedHttpMessagesAsDicts parsed) {
        var sink = sinks.computeIfAbsent(
            Thread.currentThread().getId(),
            id -> sinkFactory.apply(threadIndexCounter.getAndIncrement())
        );
        var future = new CompletableFuture<Void>();
        var map = parsed.toTupleMap(tuple);
        sink.accept(map, future);
        sink.onEndOfBatch();
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
