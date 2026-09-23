package org.opensearch.migrations.replay.traffic.source;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: ISimpleTrafficCaptureSource ITrafficSourceContexts ITrafficStreamKey PojoTrafficStreamAndKey SourceInput . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.io.EOFException;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordId;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.tracing.TestContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArrayCursorTrafficCaptureSource implements ISimpleTrafficCaptureSource {
    // This deterministic fixture deliberately opts into record-work tracking and commit callbacks.
    private static final String SYNTHETIC_TOPIC = "array-cursor-traffic";
    private static final int SYNTHETIC_PARTITION = 0;

    final AtomicInteger readCursor;
    final PriorityQueue<TrafficStreamCursorKey> pQueue = new PriorityQueue<>();
    Integer cursorHighWatermark;
    ArrayCursorTrafficSourceContext arrayCursorTrafficSourceContext;
    TestContext rootContext;
    private final int sourceGeneration;
    private boolean retired;
    private boolean closed;

    public ArrayCursorTrafficCaptureSource(
        TestContext rootContext,
        ArrayCursorTrafficSourceContext arrayCursorTrafficSourceContext
    ) {
        this.arrayCursorTrafficSourceContext = arrayCursorTrafficSourceContext;
        this.rootContext = rootContext;
        synchronized (arrayCursorTrafficSourceContext) {
            var activation = arrayCursorTrafficSourceContext.prepareActivation();
            log.info("startingCursor = " + activation.startingCursor());
            this.readCursor = new AtomicInteger(activation.startingCursor());
            cursorHighWatermark = activation.startingCursor();
            this.sourceGeneration = activation.sourceGeneration();
            arrayCursorTrafficSourceContext.publishActivation(this);
        }
    }

    @Override
    public CompletableFuture<List<SourceInput>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    ) {
        synchronized (pQueue) {
            if (retired || closed) {
                return CompletableFuture.failedFuture(
                    new CancellationException(
                        "Array-cursor source generation is no longer active: " + sourceGeneration
                    )
                );
            }
            var idx = readCursor.getAndIncrement();
            log.info("reading chunk from index=" + idx);
            if (arrayCursorTrafficSourceContext.trafficStreamsList.size() <= idx) {
                return CompletableFuture.failedFuture(new EOFException());
            }
            var stream = arrayCursorTrafficSourceContext.trafficStreamsList.get(idx);
            var key = new TrafficStreamCursorKey(
                rootContext,
                stream,
                idx,
                sourceGeneration
            );
            pQueue.add(key);
            cursorHighWatermark = idx;
            return CompletableFuture.completedFuture(List.of(
                (SourceInput) new PojoTrafficStreamAndKey(stream, key)
            ));
        }
    }

    @Override
    public RecordId recordIdFor(ITrafficStreamKey trafficStreamKey) {
        var cursorKey = (TrafficStreamCursorKey) trafficStreamKey;
        return new KafkaRecordId(
            SYNTHETIC_TOPIC,
            SYNTHETIC_PARTITION,
            cursorKey.arrayIndex,
            cursorKey.getSourceGeneration()
        );
    }

    @Override
    public CompletionStage<Void> recordProcessingFinished(KafkaRecordId recordId) {
        var acknowledgement = new CompletableFuture<Void>();
        try {
            if (!SYNTHETIC_TOPIC.equals(recordId.topic())
                || recordId.partition() != SYNTHETIC_PARTITION) {
                throw new IllegalArgumentException(
                    "Unexpected array-cursor record identity: " + recordId
                );
            }
            synchronized (pQueue) {
                if (closed) {
                    throw new CancellationException(
                        "Array-cursor source closed before record completion acceptance"
                    );
                }
                if (retired || recordId.sourceGeneration() != sourceGeneration) {
                    log.atDebug()
                        .setMessage(
                            "Ignoring completion from record generation {} for source generation {}; "
                                + "retired={}"
                        )
                        .addArgument(recordId.sourceGeneration())
                        .addArgument(sourceGeneration)
                        .addArgument(retired)
                        .log();
                    acknowledgement.complete(null);
                    return acknowledgement.minimalCompletionStage();
                }
                var incomingCursor = Math.toIntExact(recordId.offset());
                var head = pQueue.peek();
                if (head == null) {
                    throw new IllegalStateException(
                        "Array-cursor record completed without an observed record: " + recordId
                    );
                }
                var completedKey = pQueue.stream()
                    .filter(key ->
                        key.arrayIndex == incomingCursor
                            && key.getSourceGeneration() == recordId.sourceGeneration()
                    )
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                        "Array-cursor record was not pending completion: " + recordId
                    ));
                rootContext.releaseChannelContextForTest(
                    completedKey.trafficStreamsContext.getChannelKeyContext()
                );
                pQueue.remove(completedKey);
                if (head == completedKey) {
                    var nextCursor = pQueue.isEmpty()
                        ? cursorHighWatermark + 1
                        : pQueue.peek().arrayIndex;
                    arrayCursorTrafficSourceContext.nextReadCursor.set(nextCursor);
                }
            }
            acknowledgement.complete(null);
        } catch (Throwable failure) {
            acknowledgement.completeExceptionally(failure);
        }
        return acknowledgement.minimalCompletionStage();
    }

    void retireForSupersession() {
        synchronized (pQueue) {
            if (retired || closed) {
                return;
            }
            retired = true;
            releasePendingContexts();
        }
    }

    @Override
    public CompletionStage<Void> acknowledgeSessionTermination(ConnectionSessionKey sessionKey) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey) {
        // This deterministic source has no separate connection registry.
    }

    @Override
    public void close() {
        synchronized (pQueue) {
            if (closed) {
                return;
            }
            closed = true;
            releasePendingContexts();
        }
    }

    private void releasePendingContexts() {
        while (!pQueue.isEmpty()) {
            var key = pQueue.peek();
            rootContext.releaseChannelContextForTest(
                key.trafficStreamsContext.getChannelKeyContext()
            );
            pQueue.remove(key);
        }
    }
}

*/
// REBUILD-LIMBO-END(G10)