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

import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
            nextPieceFuture.get(10, TimeUnit.SECONDS).forEach(ts -> firstChunk.add(ts));
        }
        log.atInfo().setMessage("blockingSource={}").addArgument(blockingSource).log();
        Assertions.assertTrue(BUFFER_MILLIS + SHIFT <= firstChunk.size());
        Instant lastTime = null;
        for (int i = SHIFT; i < nStreamsToCreate - BUFFER_MILLIS - SHIFT; ++i) {
            var blockedFuture = blockingSource.readNextTrafficStreamChunk(rootContext::createReadChunkContext);
            Assertions.assertFalse(blockedFuture.isDone(), "for i=" + i + " and coounter=" + testSource.counter.get());
            Assertions.assertEquals(i + BUFFER_MILLIS + SHIFT, testSource.counter.get());
            blockingSource.stopReadsPast(sourceStartTime.plus(Duration.ofMillis(i)));
            log.atInfo().setMessage("after stopReadsPast blockingSource={}").addArgument(blockingSource).log();
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

    /**
     * Verify that BlockingTrafficSource delegates onNetworkConnectionClosed and
     * onConnectionAccumulationComplete to the underlying source.
     */
    @Test
    void blockingTrafficSource_delegatesLifecycleCallbacks() throws Exception {
        var delegatingSource = new DelegationTrackingSource(rootContext, 10);
        var blockingSource = new BlockingTrafficSource(delegatingSource, Duration.ofMillis(10));

        blockingSource.onNetworkConnectionClosed("test-conn", 5, 3);
        Assertions.assertEquals(1, delegatingSource.networkClosedCalls.get(),
            "onNetworkConnectionClosed must be delegated to underlying source");
        Assertions.assertEquals("test-conn:5:3", delegatingSource.lastNetworkClosedArgs,
            "Delegation must pass exact arguments");

        var mockKey = PojoTrafficStreamKeyAndContext.build(
            TrafficStream.newBuilder().setConnectionId("c").setNumberOfThisLastChunk(0).build(),
            rootContext::createTrafficStreamContextForTest);
        blockingSource.onConnectionAccumulationComplete(mockKey);
        Assertions.assertEquals(1, delegatingSource.accumulationCompleteCalls.get(),
            "onConnectionAccumulationComplete must be delegated to underlying source");

        blockingSource.close();
    }

    private static class DelegationTrackingSource extends TestTrafficCaptureSource {
        final AtomicInteger networkClosedCalls = new AtomicInteger();
        final AtomicInteger accumulationCompleteCalls = new AtomicInteger();
        volatile String lastNetworkClosedArgs;

        DelegationTrackingSource(TestContext rootContext, int nStreams) {
            super(rootContext, nStreams);
        }

        @Override
        public void onNetworkConnectionClosed(String connectionId, int sessionNumber, int generation) {
            networkClosedCalls.incrementAndGet();
            lastNetworkClosedArgs = connectionId + ":" + sessionNumber + ":" + generation;
        }

        @Override
        public void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey) {
            accumulationCompleteCalls.incrementAndGet();
        }
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
            log.atTrace().setMessage("Test.readNextTrafficStreamChunk.counter={}").addArgument(counter).log();
            var i = counter.getAndIncrement();
            if (i >= nStreamsToCreate) {
                return CompletableFuture.failedFuture(new EOFException());
            }

            var t = sourceStartTime.plus(Duration.ofMillis(i));
            log.atDebug().setMessage("Built timestamp for {}").addArgument(i).log();
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
