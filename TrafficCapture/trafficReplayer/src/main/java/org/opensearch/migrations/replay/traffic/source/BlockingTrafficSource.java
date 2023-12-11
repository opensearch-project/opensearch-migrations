package org.opensearch.migrations.replay.traffic.source;

import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.Utils;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.slf4j.event.Level;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
public class BlockingTrafficSource implements ITrafficCaptureSource, BufferedFlowController {

    private final ISimpleTrafficCaptureSource underlyingSource;
    private final AtomicReference<Instant> lastTimestampSecondsRef;
    private final AtomicReference<Instant> stopReadingAtRef;
    /**
     * Limit the number of readers to one at a time and only if we haven't yet maxed out our time buffer
     */
    private final Semaphore readGate;
    private final Duration bufferTimeWindow;


    public BlockingTrafficSource(ISimpleTrafficCaptureSource underlying,
                                 Duration bufferTimeWindow) {
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
                    .setMessage(() -> "stopReadsPast: " + pointInTime + " [buffer=" + prospectiveBarrier +
                            "] didn't move the cursor because the value was already at " + newValue
                    ).log();
        }
    }

    public Duration getBufferTimeWindow() {
        return bufferTimeWindow;
    }

    /**
     * Reads the next chunk that is available before the current stopReading barrier.  However,
     * that barrier isn't meant to be a tight barrier with immediate effect.
     *
     * @return
     */
    @Override
    public CompletableFuture<List<ITrafficStreamWithKey>>
    readNextTrafficStreamChunk() {
        log.info("BlockingTrafficSource::readNext");
        var trafficStreamListFuture = CompletableFuture
                .supplyAsync(this::blockIfNeeded, task -> new Thread(task).start())
                .thenCompose(v->{
                    log.info("BlockingTrafficSource::composing");
                    return underlyingSource.readNextTrafficStreamChunk();
                });
        return trafficStreamListFuture.whenComplete((v,t)->{
            if (t != null) {
                return;
            }
            var maxLocallyObservedTimestamp = v.stream().flatMap(tswk->tswk.getStream().getSubStreamList().stream())
                    .map(tso->tso.getTs())
                    .max(Comparator.comparingLong(Timestamp::getSeconds)
                            .thenComparingInt(Timestamp::getNanos))
                    .map(TrafficStreamUtils::instantFromProtoTimestamp)
                    .orElse(Instant.EPOCH);
            Utils.setIfLater(lastTimestampSecondsRef, maxLocallyObservedTimestamp);
            log.atTrace().setMessage(()->"end of readNextTrafficStreamChunk trigger...lastTimestampSecondsRef="
                    +lastTimestampSecondsRef.get()).log();
        });
    }

    private Void blockIfNeeded() {
        if (stopReadingAtRef.get().equals(Instant.EPOCH)) { return null; }
        log.atInfo().setMessage(()->"stopReadingAtRef="+stopReadingAtRef+
                " lastTimestampSecondsRef="+lastTimestampSecondsRef).log();
        while (stopReadingAtRef.get().isBefore(lastTimestampSecondsRef.get())) {
            try {
                log.atInfo().setMessage("blocking until signaled to read the next chunk last={} stop={}")
                        .addArgument(lastTimestampSecondsRef.get())
                        .addArgument(stopReadingAtRef.get())
                        .log();
                var nextTouchOp = underlyingSource.getNextRequiredTouch();
                if (nextTouchOp.isEmpty()) {
                    log.trace("acquring readGate semaphore (w/out timeout)");
                    readGate.acquire();
                } else {
                    var nextInstant = nextTouchOp.get();
                    final var nowTime = Instant.now();
                    var waitIntervalMs = Duration.between(nowTime, nextInstant).toMillis();
                    log.atDebug().setMessage(()->"Next touch at " + nextInstant +
                            " ... in " + waitIntervalMs + "ms (now="+nowTime+")").log();
                    if (waitIntervalMs <= 0) {
                        underlyingSource.touch();
                    } else {
                        // if this doesn't succeed, we'll loop around & likely do a touch, then loop around again.
                        // if it DOES succeed, we'll loop around and make sure that there's not another reason to stop
                        log.atTrace().setMessage(()->"acquring readGate semaphore with timeout="+waitIntervalMs).log();
                        readGate.tryAcquire(waitIntervalMs, TimeUnit.MILLISECONDS);
                    }
                }
            } catch (InterruptedException e) {
                log.atWarn().setCause(e).log("Interrupted while waiting to read more data");
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    @Override
    public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException {
        underlyingSource.commitTrafficStream(trafficStreamKey);
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
