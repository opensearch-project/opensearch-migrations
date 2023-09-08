package org.opensearch.migrations.replay;

import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.slf4j.event.Level;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The BlockingTrafficSource class implements ITrafficCaptureSource and wraps another instance.
 * It keeps track of a couple Instants for the last timestamp from a TrafficStreamObservation
 * and for a high-watermark (stopReadingAt) that has been supplied externally.  If the last
 * timestamp was PAST the high-watermark, calls to read the next chunk (readNextTrafficStreamChunk)
 * will return a CompletableFuture that is blocking and won't be released until
 * somebody advances the high-watermark by calling stopReadsPast, which takes in a
 * point-in-time (in System time) and adds some buffer to it.
 *
 * This class is designed to only be threadsafe for any number of callers to call stopReadsPast
 * and independently for one caller to call readNextTrafficStreamChunk() and to wait for the result
 * to complete before another caller calls it again.
 */
@Slf4j
public class BlockingTrafficSource implements ITrafficCaptureSource, BufferedTimeController {
    private final ITrafficCaptureSource underlyingSource;
    private final AtomicReference<Instant> lastTimestampSecondsRef;
    private final AtomicReference<Instant> stopReadingAtRef;
    /**
     * Limit the number of readers to one at a time and only if we haven't yet maxed out our time buffer
     */
    private final Semaphore readGate;

    private final Duration bufferTimeWindow;

    public BlockingTrafficSource(ITrafficCaptureSource underlying, Duration bufferTimeWindow) {
        this.underlyingSource = underlying;
        this.stopReadingAtRef = new AtomicReference<>(Instant.EPOCH);
        this.lastTimestampSecondsRef = new AtomicReference<>(Instant.EPOCH);
        this.bufferTimeWindow = bufferTimeWindow;
        this.readGate = new Semaphore(0);
    }

    /**
     * This will move the current high-watermark on reads that we can do to the specified time PLUS the
     * bufferTimeWindow (which was set in the c'tor)
     * @param pointInTime
     */
    @Override
    public void stopReadsPast(Instant pointInTime) {
        var prospectiveBarrier = pointInTime.plus(bufferTimeWindow);
        var newValue = Utils.setIfLater(stopReadingAtRef, prospectiveBarrier);
        if (newValue.equals(prospectiveBarrier)) {
            log.atLevel(readGate.hasQueuedThreads() ? Level.INFO: Level.TRACE)
                    .setMessage(() -> "Releasing the block on readNextTrafficStreamChunk and set" +
                            " the new stopReadingAtRef=" + newValue).log();
            // No reason to signal more than one reader.  We don't support concurrent reads with the current contract
            readGate.drainPermits();
            readGate.release();
        } else {
            log.atTrace()
                    .setMessage(() -> "stopReadsPast: " + pointInTime + " -> (" + prospectiveBarrier +
                            ") didn't move the cursor because the value was " +
                            "already at " + newValue
                    ).log();
        }
    }

    public Instant lastReadTimestamp() {
        return lastTimestampSecondsRef.get();
    }

    public Duration getBufferTimeWindow() {
        return bufferTimeWindow;
    }

    public Instant unblockedReadBoundary() {
        return lastReadTimestamp().equals(Instant.EPOCH) ? null : stopReadingAtRef.get();
    }

    /**
     * Reads the next chunk that is available before the current stopReading barrier.  However,
     * that barrier isn't meant to be a tight barrier with immediate effect.
     *
     * @return
     */
    @Override
    public CompletableFuture<List<TrafficStream>>
    readNextTrafficStreamChunk() {
        var trafficStreamListFuture =
                CompletableFuture.supplyAsync(() -> {
                                    while (stopReadingAtRef.get().isBefore(lastTimestampSecondsRef.get())) {
                                        try {
                                            log.info("blocking until signaled to read the next chunk");
                                            readGate.acquire();
                                        } catch (InterruptedException e) {
                                            log.atWarn().setCause(e)
                                                    .log("Interrupted while waiting to read more data");
                                        }
                                    }
                                    return null;
                                },
                                task -> new Thread(task).start())
                        .thenCompose(v->underlyingSource.readNextTrafficStreamChunk());
        return trafficStreamListFuture.whenComplete((v,t)->{
            if (t != null) {
                return;
            }
            var maxLocallyObserved = v.stream().flatMap(ts->ts.getSubStreamList().stream())
                    .map(tso->tso.getTs())
                    .max(Comparator.comparingLong(Timestamp::getSeconds)
                            .thenComparingInt(Timestamp::getNanos))
                    .map(protobufTs->Instant.ofEpochSecond(protobufTs.getSeconds(), protobufTs.getNanos()))
                    .orElse(Instant.EPOCH);
            // base case - if this is the first time through, set the time expiration boundary relative to
            // the value that just came in (+ the bufferedWindow)
            if (lastTimestampSecondsRef.get().equals(Instant.EPOCH)) {
                Utils.setIfLater(stopReadingAtRef, maxLocallyObserved.plus(bufferTimeWindow));
            }
            Utils.setIfLater(lastTimestampSecondsRef, maxLocallyObserved);
            log.atTrace().setMessage(()->"end of readNextTrafficStreamChunk trigger...lastTimestampSecondsRef="
                    +lastTimestampSecondsRef.get()).log();
        });
    }

    @Override
    public void close() throws IOException {
        underlyingSource.close();
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", BlockingTrafficSource.class.getSimpleName() + "[", "]")
                .add("bufferTimeWindow=" + bufferTimeWindow)
                .add("lastTimestampSecondsRef=" + lastTimestampSecondsRef)
                .add("stopReadingAtRef=" + stopReadingAtRef)
                .add("readGate=" + readGate)
                .toString();
    }
}
