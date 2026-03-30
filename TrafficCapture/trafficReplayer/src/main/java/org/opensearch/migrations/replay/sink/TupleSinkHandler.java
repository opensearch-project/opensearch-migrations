package org.opensearch.migrations.replay.sink;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.Sequence;
import lombok.extern.slf4j.Slf4j;

/**
 * LMAX Disruptor consumer that delegates to a pluggable {@link TupleSink}.
 * Uses {@code endOfBatch} for batched flushing and sequence callback for
 * batched ring buffer slot reclamation.
 */
@Slf4j
public class TupleSinkHandler implements EventHandler<TupleEvent> {
    private final TupleSink sink;
    private Sequence sequenceCallback;

    public TupleSinkHandler(TupleSink sink) {
        this.sink = sink;
    }

    @Override
    public void setSequenceCallback(Sequence sequence) {
        this.sequenceCallback = sequence;
    }

    @Override
    public void onEvent(TupleEvent event, long sequence, boolean endOfBatch) throws Exception {
        try {
            sink.accept(event.tupleMap, event.future);
        } finally {
            event.clear();
        }
        if (endOfBatch) {
            sink.onEndOfBatch();
            if (sequenceCallback != null) {
                sequenceCallback.set(sequence);
            }
        }
    }

    @Override
    public void onTimeout(long sequence) throws Exception {
        sink.onIdle();
    }

    @Override
    public void onShutdown() {
        try {
            sink.close();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Error closing TupleSink during shutdown").log();
        }
    }
}
