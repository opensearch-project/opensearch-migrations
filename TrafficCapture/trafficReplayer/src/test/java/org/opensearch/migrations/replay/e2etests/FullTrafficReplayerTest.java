package org.opensearch.migrations.replay.e2etests;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TrafficReplayer;
import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficSourceContext;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamCursorKey;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
// It would be great to test with leak detection here, but right now this test relies upon TrafficReplayer.shutdown()
// to recycle the TrafficReplayers.  Since that shutdown process optimizes for speed of teardown, rather than tidying
// everything up as it closes the door, some leaks may be inevitable.  E.g. when work is outstanding and being sent
// to the test server, a shutdown will stop those work threads without letting them flush through all of their work
// (since that could take a very long time) and some of the work might have been followed by resource releases.
@WrapWithNettyLeakDetection(disableLeakChecks = true)
public class FullTrafficReplayerTest extends InstrumentationTest {

    public static final int INITIAL_STOP_REPLAYER_REQUEST_COUNT = 1;
    public static final String TEST_NODE_ID = "TestNodeId";
    public static final String TEST_CONNECTION_ID = "testConnectionId";

    protected static class IndexWatchingListenerFactory implements Supplier<Consumer<SourceTargetCaptureTuple>> {
        AtomicInteger nextStopPointRef = new AtomicInteger(INITIAL_STOP_REPLAYER_REQUEST_COUNT);

        @Override
        public Consumer<SourceTargetCaptureTuple> get() {
            log.info("StopAt="+nextStopPointRef.get());
            var stopPoint = nextStopPointRef.get();
            return tuple -> {
                var key = tuple.getRequestKey();
                if (((TrafficStreamCursorKey) (key.getTrafficStreamKey())).arrayIndex > stopPoint) {
                    log.error("Request received after our ingest threshold. Throwing.  Discarding " + key);
                    var nextStopPoint = stopPoint + new Random(stopPoint).nextInt(stopPoint + 1);
                    nextStopPointRef.compareAndSet(stopPoint, nextStopPoint);
                    throw new TrafficReplayerRunner.FabricatedErrorToKillTheReplayer(false);
                }
            };
        }
    }

    @Test
    @ResourceLock("TrafficReplayerRunner")
    public void fullTestWithThrottledStart() throws Throwable {
        var random = new Random(1);
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(200),
                firstLine -> TestHttpServerContext.makeResponse(random, firstLine));
        var nonTrackingContext = TestContext.noOtelTracking();
        var streamAndSizes = ExhaustiveTrafficStreamGenerator.generateStreamAndSumOfItsTransactions(nonTrackingContext,
                16, true);
        var numExpectedRequests = streamAndSizes.numHttpTransactions;
        var trafficStreams = streamAndSizes.stream.collect(Collectors.toList());
        log.atInfo().setMessage(() -> trafficStreams.stream().map(ts -> TrafficStreamUtils.summarizeTrafficStream(ts))
                .collect(Collectors.joining("\n"))).log();
        Function<TestContext, ISimpleTrafficCaptureSource> trafficSourceSupplier = rc -> new ISimpleTrafficCaptureSource() {
            boolean isDone = false;
            @Override
            public CompletableFuture<List<ITrafficStreamWithKey>>
            readNextTrafficStreamChunk(Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier) {
                if (isDone) {
                    return CompletableFuture.failedFuture(new EOFException());
                } else {
                    isDone = true;
                    return CompletableFuture.completedFuture(trafficStreams.stream()
                            .map(ts -> new PojoTrafficStreamAndKey(ts,
                                    PojoTrafficStreamKeyAndContext.build(ts, rc::createTrafficStreamContextForTest)))
                            .map(v -> (ITrafficStreamWithKey) v)
                            .collect(Collectors.toList()));
                }
            }

            @Override
            public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException {
                return null;
            }
        };

        TrafficReplayerRunner.runReplayer(
                numExpectedRequests,
                rc -> {
                    try {
                        return new TrafficReplayer(rc, httpServer.localhostEndpoint(), null,
                                new StaticAuthTransformerFactory("TEST"), null,
                                true, 1, 1);
                    } catch (SSLException e) {
                        throw new RuntimeException(e);
                    }
                },
                () -> t -> {},
                () -> nonTrackingContext,
                trafficSourceSupplier);
        log.info("done");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "3,false",
            "-1,false",
            "3,true",
            "-1,true",
    })
    @Tag("longTest")
    @ResourceLock("TrafficReplayerRunner")
    public void fullTestWithRestarts(int testSize, boolean randomize) throws Throwable {
        var random = new Random(1);
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(200),
                response -> TestHttpServerContext.makeResponse(random, response));
        var streamAndSizes = ExhaustiveTrafficStreamGenerator.generateStreamAndSumOfItsTransactions(TestContext.noOtelTracking(),
                testSize, randomize);
        var numExpectedRequests = streamAndSizes.numHttpTransactions;
        var trafficStreams = streamAndSizes.stream.collect(Collectors.toList());
        log.atInfo().setMessage(() -> trafficStreams.stream().map(ts -> TrafficStreamUtils.summarizeTrafficStream(ts))
                .collect(Collectors.joining("\n"))).log();
        var trafficSourceSupplier = new ArrayCursorTrafficSourceContext(trafficStreams);
        TrafficReplayerRunner.runReplayer(
                numExpectedRequests, httpServer.localhostEndpoint(), new IndexWatchingListenerFactory(),
                () -> TestContext.noOtelTracking(),
                trafficSourceSupplier);
        Assertions.assertEquals(trafficSourceSupplier.trafficStreamsList.size(),
                trafficSourceSupplier.nextReadCursor.get());
        log.info("done");
    }
}
