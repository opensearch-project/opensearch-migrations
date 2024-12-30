package org.opensearch.migrations.replay.traffic.source;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.Utils;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import com.google.protobuf.Timestamp;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;

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
    @Getter
    private final Duration bufferTimeWindow;
    private final ExecutorService executorForBlockingActivity;

    public BlockingTrafficSource(ISimpleTrafficCaptureSource underlying, Duration bufferTimeWindow) {
        this.underlyingSource = underlying;
        this.stopReadingAtRef = new AtomicReference<>(Instant.EPOCH);
        this.lastTimestampSecondsRef = new AtomicReference<>(Instant.EPOCH);
        this.bufferTimeWindow = bufferTimeWindow;
        this.readGate = new Semaphore(0);
        this.executorForBlockingActivity = Executors.newSingleThreadExecutor(
            new DefaultThreadFactory(
                "BlockingTrafficSource-executorForBlockingActivity-" + System.identityHashCode(this)
            )
        );
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
            log.atLevel(Level.TRACE)
                .setMessage("Releasing the block on readNextTrafficStreamChunk and set the new stopReadingAtRef={}")
                .addArgument(newValue)
                .log();
            // No reason to signal more than one reader. We don't support concurrent reads with the current contract
            readGate.drainPermits();
            readGate.release();
        } else {
            log.atTrace()
                .setMessage("stopReadsPast: {} [buffer={}] didn't move the cursor because the value was already at {}")
                .addArgument(pointInTime)
                .addArgument(prospectiveBarrier)
                .addArgument(newValue)
                .log();
        }
    }

    /**
     * Reads the next chunk that is available before the current stopReading barrier.  However,
     * that barrier isn't meant to be a tight barrier with immediate effect.
     */
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> readChunkContextSupplier
    ) {
        var readContext = readChunkContextSupplier.get();
        log.debug("BlockingTrafficSource::readNext");
        var trafficStreamListFuture = CompletableFuture.supplyAsync(
            () -> blockIfNeeded(readContext),
            executorForBlockingActivity
        ).thenCompose(v -> {
            log.trace("BlockingTrafficSource::composing");
            return underlyingSource.readNextTrafficStreamChunk(() -> readContext);
        }).whenComplete((v, t) -> readContext.close());
        return trafficStreamListFuture.whenComplete((v, t) -> {
            if (t != null) {
                return;
            }
            var maxLocallyObservedTimestamp = v.stream()
                .flatMap(tswk -> tswk.getStream().getSubStreamList().stream())
                .map(TrafficObservation::getTs)
                .max(Comparator.comparingLong(Timestamp::getSeconds).thenComparingInt(Timestamp::getNanos))
                .map(TrafficStreamUtils::instantFromProtoTimestamp)
                .orElse(Instant.EPOCH);
            Utils.setIfLater(lastTimestampSecondsRef, maxLocallyObservedTimestamp);
            log.atTrace().setMessage("end of readNextTrafficStreamChunk trigger...lastTimestampSecondsRef={}")
                .addArgument(lastTimestampSecondsRef::get)
                .log();
        });
    }

    /**
     * This could be rewritten as a fully asynchronous function that uses times, but for a single
     * thread in the application, it isn't worth it.  It's also easier to debug the state machine
     * from a blocking function.
     * @param readContext
     * @return
     */
    private Void blockIfNeeded(ITrafficSourceContexts.IReadChunkContext readContext) {
        if (stopReadingAtRef.get().equals(Instant.EPOCH)) {
            return null;
        }
        log.atTrace().setMessage("stopReadingAtRef={} lastTimestampSecondsRef={}")
            .addArgument(stopReadingAtRef)
            .addArgument(lastTimestampSecondsRef)
            .log();
        ITrafficSourceContexts.IBackPressureBlockContext blockContext = null;
        while (stopReadingAtRef.get().isBefore(lastTimestampSecondsRef.get())) {
            if (blockContext == null) {
                blockContext = readContext.createBackPressureContext();
            }
            try {
                log.atTrace().setMessage("blocking until signaled to read the next chunk last={} stop={}")
                    .addArgument(lastTimestampSecondsRef::get)
                    .addArgument(stopReadingAtRef::get)
                    .log();
                var nextTouchOp = underlyingSource.getNextRequiredTouch();
                if (nextTouchOp.isEmpty()) {
                    log.trace("acquiring readGate semaphore (w/out timeout)");
                    try (var waitContext = blockContext.createWaitForSignalContext()) {
                        readGate.acquire();
                    }
                } else {
                    var nextInstant = nextTouchOp.get();
                    final var nowTime = Instant.now();
                    var waitIntervalMs = Duration.between(nowTime, nextInstant).toMillis();
                    log.atDebug().setMessage("Next touch at {} ... in {}ms (now={})")
                        .addArgument(nextInstant)
                        .addArgument(waitIntervalMs)
                        .addArgument(nowTime)
                        .log();
                    if (waitIntervalMs <= 0) {
                        underlyingSource.touch(blockContext);
                    } else {
                        // if this doesn't succeed, we'll loop around & likely do a touch, then loop around again.
                        // if it DOES succeed, we'll loop around and make sure that there's not another reason to stop
                        log.atTrace()
                            .setMessage("acquiring readGate semaphore with timeout={}")
                            .addArgument(waitIntervalMs).log();
                        try (var waitContext = blockContext.createWaitForSignalContext()) {
                            var didAcquire = readGate.tryAcquire(waitIntervalMs, TimeUnit.MILLISECONDS);
                            log.atTrace().setMessage("semaphore {}")
                                .addArgument(() -> (didAcquire ? "" : "not ") + "acquired").log();
                        }
                    }
                }
            } catch (InterruptedException e) {
                log.atWarn().setCause(e).setMessage("Interrupted while waiting to read more data").log();
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (blockContext != null) {
            blockContext.close();
        }
        return null;
    }

    @Override
    public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException {
        var commitResult = underlyingSource.commitTrafficStream(trafficStreamKey);
        if (commitResult == CommitResult.AFTER_NEXT_READ) {
            readGate.drainPermits();
            readGate.release();
        }
        return commitResult;
    }

    @Override
    public void close() throws Exception {
        underlyingSource.close();
        executorForBlockingActivity.shutdown();

    }

    @Override
    public String toString() {
        return new StringJoiner(", ", BlockingTrafficSource.class.getSimpleName() + "[", "]").add(
            "bufferTimeWindow=" + bufferTimeWindow
        )
            .add("lastTimestampSecondsRef=" + lastTimestampSecondsRef)
            .add("stopReadingAtRef=" + stopReadingAtRef)
            .add("readGate=" + readGate)
            .toString();
    }
}
