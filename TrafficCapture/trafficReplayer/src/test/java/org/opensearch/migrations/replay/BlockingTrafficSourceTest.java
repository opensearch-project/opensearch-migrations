package org.opensearch.migrations.replay;

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
import java.util.function.Supplier;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@WrapWithNettyLeakDetection(disableLeakChecks = true)
class BlockingTrafficSourceTest extends InstrumentationTest {
    private static final Instant sourceStartTime = Instant.EPOCH;
    public static final int SHIFT = 1;

    @Test
    void readNextChunkTest() throws Exception {
        var nStreamsToCreate = 210;
        var BUFFER_MILLIS = 10;
        var testSource = new TestTrafficCaptureSource(rootContext, nStreamsToCreate);

        var blockingSource = new BlockingTrafficSource(testSource, Duration.ofMillis(BUFFER_MILLIS));
        blockingSource.stopReadsPast(sourceStartTime.plus(Duration.ofMillis(0)));
        var firstChunk = new ArrayList<ITrafficStreamWithKey>();
        for (int i = 0; i <= BUFFER_MILLIS + SHIFT; ++i) {
            var nextPieceFuture = blockingSource.readNextTrafficStreamChunk(rootContext::createReadChunkContext);
            nextPieceFuture.get(500000, TimeUnit.MILLISECONDS).forEach(ts -> firstChunk.add(ts));
        }
        log.info("blockingSource=" + blockingSource);
        Assertions.assertTrue(BUFFER_MILLIS + SHIFT <= firstChunk.size());
        Instant lastTime = null;
        for (int i = SHIFT; i < nStreamsToCreate - BUFFER_MILLIS - SHIFT; ++i) {
            var blockedFuture = blockingSource.readNextTrafficStreamChunk(rootContext::createReadChunkContext);
            Thread.sleep(5);
            Assertions.assertFalse(blockedFuture.isDone(), "for i=" + i + " and coounter=" + testSource.counter.get());
            Assertions.assertEquals(i + BUFFER_MILLIS + SHIFT, testSource.counter.get());
            blockingSource.stopReadsPast(sourceStartTime.plus(Duration.ofMillis(i)));
            log.info("after stopReadsPast blockingSource=" + blockingSource);
            var completedFutureValue = blockedFuture.get(10000, TimeUnit.MILLISECONDS);
            lastTime = TrafficStreamUtils.getFirstTimestamp(completedFutureValue.get(0).getStream()).get();
        }
        Assertions.assertEquals(sourceStartTime.plus(Duration.ofMillis(nStreamsToCreate - SHIFT)), lastTime);
        blockingSource.stopReadsPast(sourceStartTime.plus(Duration.ofMillis(nStreamsToCreate)));
        var exception = Assertions.assertThrows(
            ExecutionException.class,
            () -> blockingSource.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                .get(10000, TimeUnit.MILLISECONDS)
        );
        Assertions.assertInstanceOf(EOFException.class, exception.getCause());
    }

    private static class TestTrafficCaptureSource implements ISimpleTrafficCaptureSource {
        int nStreamsToCreate;
        AtomicInteger counter = new AtomicInteger();
        final TestContext rootContext;

        TestTrafficCaptureSource(TestContext rootContext, int nStreamsToCreate) {
            this.rootContext = rootContext;
            this.nStreamsToCreate = nStreamsToCreate;
        }

        @Override
        public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
            Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
        ) {
            log.atTrace().setMessage(() -> "Test.readNextTrafficStreamChunk.counter=" + counter).log();
            var i = counter.getAndIncrement();
            if (i >= nStreamsToCreate) {
                return CompletableFuture.failedFuture(new EOFException());
            }

            var t = sourceStartTime.plus(Duration.ofMillis(i));
            log.debug("Built timestamp for " + i);
            var ts = TrafficStream.newBuilder()
                .setNumberOfThisLastChunk(0)
                .setConnectionId("conn_" + i)
                .addSubStream(
                    TrafficObservation.newBuilder()
                        .setTs(Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build())
                        .setClose(CloseObservation.getDefaultInstance())
                        .build()
                )
                .build();
            var key = PojoTrafficStreamKeyAndContext.build(ts, rootContext::createTrafficStreamContextForTest);
            return CompletableFuture.completedFuture(List.of(new PojoTrafficStreamAndKey(ts, key)));
        }

        @Override
        public void close() throws IOException {}

        @Override
        public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
            // do nothing
            return CommitResult.IMMEDIATE;
        }
    }
}
