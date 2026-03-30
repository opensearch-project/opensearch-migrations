package org.opensearch.migrations.replay.sink;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.lmax.disruptor.EventFactory;

/**
 * Preallocated mutable event in the LMAX Disruptor ring buffer.
 * Fields are set by the translator on the publishing thread and cleared after consumption.
 */
public class TupleEvent {
    public static final EventFactory<TupleEvent> FACTORY = TupleEvent::new;

    Map<String, Object> tupleMap;
    CompletableFuture<Void> future;

    void clear() {
        tupleMap = null;
        future = null;
    }
}
