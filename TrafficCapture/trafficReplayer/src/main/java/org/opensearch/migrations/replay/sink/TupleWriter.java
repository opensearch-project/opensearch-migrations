package org.opensearch.migrations.replay.sink;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.ParsedHttpMessagesAsDicts;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;

import com.lmax.disruptor.TimeoutBlockingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TupleWriter implements AutoCloseable {
    public static final int DEFAULT_RING_BUFFER_SIZE = 8192;

    private final Disruptor<TupleEvent> disruptor;

    public TupleWriter(TupleSink sink, int ringBufferSize) {
        this.disruptor = new Disruptor<>(
            TupleEvent.FACTORY,
            ringBufferSize,
            Thread::new,
            ProducerType.MULTI,
            // Wakes the consumer thread every 1s during idle periods to trigger onTimeout(),
            // which flushes any buffered tuples so Kafka commits aren't delayed indefinitely.
            new TimeoutBlockingWaitStrategy(1, TimeUnit.SECONDS)
        );
        disruptor.handleEventsWith(new TupleSinkHandler(sink));
        disruptor.start();
    }

    public TupleWriter(TupleSink sink) {
        this(sink, DEFAULT_RING_BUFFER_SIZE);
    }

    public CompletableFuture<Void> writeTuple(SourceTargetCaptureTuple tuple, ParsedHttpMessagesAsDicts parsed) {
        var future = new CompletableFuture<Void>();
        var map = parsed.toTupleMap(tuple);
        disruptor.publishEvent((event, seq) -> {
            event.tupleMap = map;
            event.future = future;
        });
        return future;
    }

    @Override
    public void close() {
        disruptor.shutdown();
    }
}
