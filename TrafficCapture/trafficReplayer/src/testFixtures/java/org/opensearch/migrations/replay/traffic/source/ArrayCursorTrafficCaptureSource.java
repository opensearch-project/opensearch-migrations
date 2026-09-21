package org.opensearch.migrations.replay.traffic.source;

import java.io.EOFException;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.tracing.TestContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArrayCursorTrafficCaptureSource implements ISimpleTrafficCaptureSource {
    final AtomicInteger readCursor;
    final PriorityQueue<TrafficStreamCursorKey> pQueue = new PriorityQueue<>();
    Integer cursorHighWatermark;
    ArrayCursorTrafficSourceContext arrayCursorTrafficSourceContext;
    TestContext rootContext;

    public ArrayCursorTrafficCaptureSource(
        TestContext rootContext,
        ArrayCursorTrafficSourceContext arrayCursorTrafficSourceContext
    ) {
        var startingCursor = arrayCursorTrafficSourceContext.nextReadCursor.get();
        log.info("startingCursor = " + startingCursor);
        this.readCursor = new AtomicInteger(startingCursor);
        this.arrayCursorTrafficSourceContext = arrayCursorTrafficSourceContext;
        cursorHighWatermark = startingCursor;
        this.rootContext = rootContext;
    }

    @Override
    public CompletableFuture<List<SourceInput>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    ) {
        var idx = readCursor.getAndIncrement();
        log.info("reading chunk from index=" + idx);
        if (arrayCursorTrafficSourceContext.trafficStreamsList.size() <= idx) {
            return CompletableFuture.failedFuture(new EOFException());
        }
        var stream = arrayCursorTrafficSourceContext.trafficStreamsList.get(idx);
        var key = new TrafficStreamCursorKey(rootContext, stream, idx);
        synchronized (pQueue) {
            pQueue.add(key);
            cursorHighWatermark = idx;
        }
        return CompletableFuture.supplyAsync(() -> List.of(
            (SourceInput) new PojoTrafficStreamAndKey(stream, key)
        ));
    }

    @Override
    public CompletionStage<Void> acknowledgeSessionTermination(ConnectionSessionKey sessionKey) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey) {
        // This deterministic source has no separate connection registry.
    }
}
