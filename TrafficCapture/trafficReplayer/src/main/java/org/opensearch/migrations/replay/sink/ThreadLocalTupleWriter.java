package org.opensearch.migrations.replay.sink;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.opensearch.migrations.replay.ParsedHttpMessagesAsDicts;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-thread tuple writer that eliminates the need for a shared Disruptor.
 *
 * <p>Each calling thread gets its own {@link TupleSink} instance (typically a
 * {@link GzipJsonLinesSink}). The gzip write (JSON serialization + compression)
 * happens on the calling thread (fast, in-memory). The expensive {@code fsync}
 * is submitted to a shared IO executor so it doesn't block the Netty event loop.</p>
 *
 * <p>Since each Netty event loop is single-threaded, the per-thread sink requires
 * no synchronization.</p>
 */
@Slf4j
public class ThreadLocalTupleWriter implements AutoCloseable {
    private final ConcurrentHashMap<Long, TupleSink> sinks = new ConcurrentHashMap<>();
    private final AtomicInteger threadIndexCounter = new AtomicInteger();
    private final IntFunction<TupleSink> sinkFactory;
    private final ExecutorService ioExecutor;

    /**
     * @param sinkFactory creates a new sink for each thread, given a thread index
     */
    public ThreadLocalTupleWriter(IntFunction<TupleSink> sinkFactory) {
        this.sinkFactory = sinkFactory;
        this.ioExecutor = Executors.newCachedThreadPool(r -> {
            var t = new Thread(r, "tuple-io-flush");
            t.setDaemon(true);
            return t;
        });
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
     * <p>The gzip write happens synchronously (fast, in-memory buffer).
     * The flush+fsync is submitted to the IO executor so the event loop is not blocked.</p>
     */
    public CompletableFuture<Void> writeTuple(SourceTargetCaptureTuple tuple, ParsedHttpMessagesAsDicts parsed) {
        var sink = sinks.computeIfAbsent(
            Thread.currentThread().getId(),
            id -> sinkFactory.apply(threadIndexCounter.getAndIncrement())
        );
        var future = new CompletableFuture<Void>();
        var map = parsed.toTupleMap(tuple);
        sink.accept(map, future);
        // Submit flush+fsync to IO executor so we don't block the event loop
        ioExecutor.execute(sink::onEndOfBatch);
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
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
