package org.opensearch.migrations.replay;

import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@WrapWithNettyLeakDetection(disableLeakChecks = true)
class BlockingTrafficSourceTest {
    private static final Instant sourceStartTime = Instant.EPOCH;
    public static final int SHIFT = 1;

    @Test
    void readNextChunkTest() throws Exception {
        var nStreamsToCreate = 210;
        var BUFFER_MILLIS = 10;
        var testSource = new TestTrafficCaptureSource(nStreamsToCreate);

        var blockingSource = new BlockingTrafficSource(testSource, Duration.ofMillis(BUFFER_MILLIS), Integer.MAX_VALUE,
                CapturedTrafficToHttpTransactionAccumulator::countRequestsInTrafficStream, x->1);
        blockingSource.stopReadsPast(sourceStartTime.plus(Duration.ofMillis(0)));
        var firstChunk = new ArrayList<ITrafficStreamWithKey>();
        for (int i = 0; i<=BUFFER_MILLIS+SHIFT; ++i) {
            var nextPieceFuture = blockingSource.readNextTrafficStreamChunk();
            nextPieceFuture.get(500000, TimeUnit.MILLISECONDS)
                .forEach(ts->firstChunk.add(ts));
        }
        log.info("blockingSource=" + blockingSource);
        Assertions.assertTrue(BUFFER_MILLIS+SHIFT <= firstChunk.size());
        Instant lastTime = null;
        for (int i =SHIFT; i<nStreamsToCreate-BUFFER_MILLIS-SHIFT; ++i) {
            var blockedFuture = blockingSource.readNextTrafficStreamChunk();
            Thread.sleep(5);
            Assertions.assertFalse(blockedFuture.isDone(), "for i="+i+" and coounter="+testSource.counter.get());
            Assertions.assertEquals(i+BUFFER_MILLIS+SHIFT, testSource.counter.get());
            blockingSource.stopReadsPast(sourceStartTime.plus(Duration.ofMillis(i)));
            log.info("after stopReadsPast blockingSource=" + blockingSource);
            var completedFutureValue = blockedFuture.get(10000, TimeUnit.MILLISECONDS);
            lastTime = TrafficStreamUtils.getFirstTimestamp(completedFutureValue.get(0).getStream()).get();
        }
        Assertions.assertEquals(sourceStartTime.plus(Duration.ofMillis(nStreamsToCreate-SHIFT)), lastTime);
        blockingSource.stopReadsPast(sourceStartTime.plus(Duration.ofMillis(nStreamsToCreate)));
        var exception = Assertions.assertThrows(ExecutionException.class,
                ()->blockingSource.readNextTrafficStreamChunk().get(10, TimeUnit.MILLISECONDS));
        Assertions.assertInstanceOf(EOFException.class, exception.getCause());
    }

    private static class TestTrafficCaptureSource implements ISimpleTrafficCaptureSource {
        int nStreamsToCreate;
        AtomicInteger counter = new AtomicInteger();
        Instant replayStartTime = Instant.EPOCH.plus(Duration.ofSeconds(SHIFT));

        TestTrafficCaptureSource(int nStreamsToCreate) {
            this.nStreamsToCreate = nStreamsToCreate;
        }

        @Override
        public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
            log.atTrace().setMessage(()->"Test.readNextTrafficStreamChunk.counter="+counter).log();
            var i = counter.getAndIncrement();
            if (i >= nStreamsToCreate) {
                return CompletableFuture.failedFuture(new EOFException());
            }

            var t = sourceStartTime.plus(Duration.ofMillis(i));
            log.debug("Built timestamp for " + i);
            return CompletableFuture.completedFuture(List.of(new TrafficStreamWithEmbeddedKey(
                    TrafficStream.newBuilder()
                            .setNumberOfThisLastChunk(0)
                            .setConnectionId("conn_" + i)
                            .addSubStream(TrafficObservation.newBuilder()
                                    .setTs(Timestamp.newBuilder()
                                            .setSeconds(t.getEpochSecond())
                                            .setNanos(t.getNano())
                                            .build())
                                    .setClose(CloseObservation.getDefaultInstance())
                                    .build())
                            .build())));
        }

        @Override
        public void close() throws IOException {}

        @Override
        public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
            // do nothing
        }
    }
}