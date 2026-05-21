package org.opensearch.migrations.replay.sink;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.ParsedHttpMessagesAsDicts;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.ThreadSafeTransformerWrapper;

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
    private static final Supplier<IJsonTransformer> NOOP_TRANSFORMER_SUPPLIER = () -> input -> input;

    private final List<TupleSink> sinks = new CopyOnWriteArrayList<>();
    private final AtomicInteger sinkIndexCounter = new AtomicInteger();
    private final IntFunction<TupleSink> sinkFactory;
    private final ThreadSafeTransformerWrapper tupleTransformer;

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
        this(sinkFactory, null);
    }

    /**
     * @param sinkFactory creates a new sink for each thread, given a sink index
     * @param tupleTransformerSupplier creates a tuple transformer per thread
     */
    public ThreadLocalTupleWriter(
        IntFunction<TupleSink> sinkFactory,
        Supplier<IJsonTransformer> tupleTransformerSupplier
    ) {
        this.sinkFactory = sinkFactory;
        var transformerSupplier = tupleTransformerSupplier != null
            ? tupleTransformerSupplier
            : NOOP_TRANSFORMER_SUPPLIER;
        this.tupleTransformer = new ThreadSafeTransformerWrapper(transformerSupplier);
    }

    /**
     * Write a tuple. Called on a Netty event loop thread.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> writeTuple(SourceTargetCaptureTuple tuple, ParsedHttpMessagesAsDicts parsed) {
        var future = new CompletableFuture<Void>();
        try {
            var transformedTuple = tupleTransformer.transformJson(parsed.toTupleMap(tuple));
            if (!(transformedTuple instanceof Map)) {
                throw new IllegalArgumentException("Tuple transformer must return a JSON object");
            }
            threadLocalSink.get().accept((Map<String, Object>) transformedTuple, future);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            throw e;
        }
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
        tupleTransformer.close();
    }
}
