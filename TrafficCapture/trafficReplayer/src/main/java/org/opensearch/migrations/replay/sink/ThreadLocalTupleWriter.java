package org.opensearch.migrations.replay.sink;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.opensearch.migrations.replay.ParsedHttpMessagesAsDicts;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-thread sink registry that assigns each calling thread its own {@link TupleSink}.
 *
 * <p>Since each Netty event loop is single-threaded, the per-thread sink requires
 * no synchronization.</p>
 */
@Slf4j
public class ThreadLocalTupleWriter implements AutoCloseable {
    private final List<TupleSink> sinks = new CopyOnWriteArrayList<>();
    private final AtomicInteger sinkIndexCounter = new AtomicInteger();
    private final IntFunction<TupleSink> sinkFactory;

    private final FastThreadLocal<TupleSink> threadLocalSink = new FastThreadLocal<>() {
        @Override
        protected TupleSink initialValue() {
            TupleSink sink = sinkFactory.apply(sinkIndexCounter.getAndIncrement());
            sinks.add(sink);
            return sink;
        }
    };

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
        var future = new CompletableFuture<Void>();
        threadLocalSink.get().accept(parsed.toTupleMap(tuple), future);
        return future;
    }

    @Override
    public void close() {
        sinks.forEach(sink -> {
            try {
                sink.close();
            } catch (Exception e) {
                log.atError().setCause(e).setMessage("Error closing TupleSink").log();
            }
        });
        sinks.clear();
        threadLocalSink.remove();
    }
}
