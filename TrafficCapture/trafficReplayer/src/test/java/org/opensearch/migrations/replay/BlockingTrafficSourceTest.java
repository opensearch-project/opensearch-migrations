package org.opensearch.migrations.replay;

import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

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
class BlockingTrafficSourceTest {
    private static final Instant sourceStartTime = Instant.EPOCH;

    @Test
    void readNextChunkTest() throws Exception {
        var nStreamsToCreate = 210;
        var BUFFER_MILLIS = 10;
        var testSource = new TestTrafficCaptureSource(nStreamsToCreate);

        var blockingSource = new BlockingTrafficSource(testSource, Duration.ofMillis(BUFFER_MILLIS));
        var firstChunk = new ArrayList<TrafficStream>();
        for (int i=0; i<=BUFFER_MILLIS+2; ++i) {
            var nextPieceFuture = blockingSource.readNextTrafficStreamChunk();
            nextPieceFuture.get(500000, TimeUnit.MILLISECONDS)
                .forEach(ts->firstChunk.add(ts));
        }
        log.info("blockingSource=" + blockingSource);
        Assertions.assertTrue(BUFFER_MILLIS+2 <= firstChunk.size());
        Instant lastTime = null;
        for (int i=1; i<nStreamsToCreate-BUFFER_MILLIS-2; ++i) {
            var blockedFuture = blockingSource.readNextTrafficStreamChunk();
            Thread.sleep(5);
            Assertions.assertFalse(blockedFuture.isDone());
            Assertions.assertEquals(i+BUFFER_MILLIS+2, testSource.counter.get());
            blockingSource.stopReadsPast(sourceStartTime.plus(Duration.ofMillis(i+1)));
            log.info("after stopReadsPast blockingSource=" + blockingSource);
            var protoBufTime =
                    blockedFuture.get(100, TimeUnit.MILLISECONDS).get(0).getSubStreamList().get(0).getTs();
            lastTime = Instant.ofEpochSecond(protoBufTime.getSeconds(), protoBufTime.getNanos());
        }
        Assertions.assertEquals(sourceStartTime.plus(Duration.ofMillis(nStreamsToCreate-1)), lastTime);
        blockingSource.stopReadsPast(sourceStartTime.plus(Duration.ofMillis(nStreamsToCreate)));
        var exception = Assertions.assertThrows(ExecutionException.class,
                ()->blockingSource.readNextTrafficStreamChunk().get(10, TimeUnit.MILLISECONDS));
        Assertions.assertInstanceOf(EOFException.class, exception.getCause());
    }

    private static class TestTrafficCaptureSource implements ITrafficCaptureSource {
        int nStreamsToCreate;
        AtomicInteger counter = new AtomicInteger();
        Instant replayStartTime = Instant.EPOCH.plus(Duration.ofSeconds(1));

        TestTrafficCaptureSource(int nStreamsToCreate) {
            this.nStreamsToCreate = nStreamsToCreate;
        }

        @Override
        public CompletableFuture<List<TrafficStream>> readNextTrafficStreamChunk() {
            log.atTrace().setMessage(()->"Test.readNextTrafficStreamChunk.counter="+counter).log();
            var i = counter.getAndIncrement();
            if (i >= nStreamsToCreate) {
                return CompletableFuture.failedFuture(new EOFException());
            }

            var t = sourceStartTime.plus(Duration.ofMillis(i));
            log.debug("Built timestamp for " + i);
            return CompletableFuture.completedFuture(List.of(
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
                            .build()));
        }

        @Override
        public void close() throws IOException {}
    }
}