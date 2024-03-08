package org.opensearch.migrations.replay.traffic.source;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.tracing.TestContext;

import java.io.EOFException;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Slf4j
public class ArrayCursorTrafficCaptureSource implements ISimpleTrafficCaptureSource {
        final AtomicInteger readCursor;
        final PriorityQueue<TrafficStreamCursorKey> pQueue = new PriorityQueue<>();
        Integer cursorHighWatermark;
        ArrayCursorTrafficSourceFactory arrayCursorTrafficSourceFactory;
        TestContext rootContext;

        public ArrayCursorTrafficCaptureSource(TestContext rootContext,
                                               ArrayCursorTrafficSourceFactory arrayCursorTrafficSourceFactory) {
            var startingCursor = arrayCursorTrafficSourceFactory.nextReadCursor.get();
            log.info("startingCursor = "  + startingCursor);
            this.readCursor = new AtomicInteger(startingCursor);
            this.arrayCursorTrafficSourceFactory = arrayCursorTrafficSourceFactory;
            cursorHighWatermark = startingCursor;
            this.rootContext = rootContext;
        }

        @Override
        public CompletableFuture<List<ITrafficStreamWithKey>>
        readNextTrafficStreamChunk(Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier) {
            var idx = readCursor.getAndIncrement();
            log.info("reading chunk from index="+idx);
            if (arrayCursorTrafficSourceFactory.trafficStreamsList.size() <= idx) {
                return CompletableFuture.failedFuture(new EOFException());
            }
            var stream = arrayCursorTrafficSourceFactory.trafficStreamsList.get(idx);
            var key = new TrafficStreamCursorKey(rootContext, stream, idx);
            synchronized (pQueue) {
                pQueue.add(key);
                cursorHighWatermark = idx;
            }
            return CompletableFuture.supplyAsync(()->List.of(new PojoTrafficStreamAndKey(stream, key)));
        }

        @Override
        public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
            synchronized (pQueue) { // figure out if I need to do something more efficient later
                log.info("Commit called for "+trafficStreamKey+" with pQueue.size="+pQueue.size());
                var incomingCursor = ((TrafficStreamCursorKey)trafficStreamKey).arrayIndex;
                int topCursor = pQueue.peek().arrayIndex;
                var didRemove = pQueue.remove(trafficStreamKey);
                if (!didRemove) {
                    log.error("no item "+incomingCursor+" to remove from "+pQueue);
                }
                assert didRemove;
                if (topCursor == incomingCursor) {
                    topCursor = Optional.ofNullable(pQueue.peek()).map(k->k.getArrayIndex())
                            .orElse(cursorHighWatermark+1); // most recent cursor was previously popped
                    log.info("Commit called for "+trafficStreamKey+", and new topCursor="+topCursor);
                    arrayCursorTrafficSourceFactory.nextReadCursor.set(topCursor);
                } else {
                    log.info("Commit called for "+trafficStreamKey+", but topCursor="+topCursor);
                }
            }
            rootContext.channelContextManager.releaseContextFor(
                    ((TrafficStreamCursorKey) trafficStreamKey).trafficStreamsContext.getChannelKeyContext());
            return CommitResult.Immediate;
        }
    }
