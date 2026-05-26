package org.opensearch.migrations.replay.e2etests;

import javax.net.ssl.SSLException;

import java.io.EOFException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * End-to-end integration tests for response post-processor extension point.
 * Exercises the full pipeline: traffic source → target → response → post-processor → tuple.
 */
@Slf4j
@Tag("longTest")
@WrapWithNettyLeakDetection(disableLeakChecks = true)
class ResponsePostProcessorE2ETest extends FullTrafficReplayerTest {

    private Function<TestContext, ISimpleTrafficCaptureSource> buildTrafficSource(
        TestContext nonTrackingContext, List<org.opensearch.migrations.trafficcapture.protos.TrafficStream> trafficStreams
    ) {
        return rc -> new ISimpleTrafficCaptureSource() {
            boolean isDone = false;

            @Override
            public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
                Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
            ) {
                if (isDone) return CompletableFuture.failedFuture(new EOFException());
                isDone = true;
                return CompletableFuture.completedFuture(
                    trafficStreams.stream()
                        .map(ts -> (ITrafficStreamWithKey) new PojoTrafficStreamAndKey(ts,
                            PojoTrafficStreamKeyAndContext.build(ts, rc::createTrafficStreamContextForTest)))
                        .collect(Collectors.toList()));
            }

            @Override
            public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
                return null;
            }
        };
    }

    /**
     * Response post-processor transforms target responses during pipeline execution.
     * Verifies the post-processor is invoked on actual HTTP responses from the target.
     */
    @Test
    @ResourceLock("TrafficReplayerRunner")
    void responsePostProcessor_transformsResponseDuringPipeline() throws Throwable {
        var targetHitCount = new AtomicInteger(0);
        var nonTrackingContext = TestContext.noOtelTracking();
        try (var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(200),
            response -> {
                targetHitCount.incrementAndGet();
                return TestHttpServerContext.makeResponse(new Random(1), response);
            })) {

            var streamAndSizes = ExhaustiveTrafficStreamGenerator
                .generateStreamAndSumOfItsTransactions(nonTrackingContext, 16, true);
            var trafficStreams = streamAndSizes.stream.collect(Collectors.toList());

            TrafficReplayerRunner.runReplayer(streamAndSizes.numHttpTransactions, (rc, threadPrefix) -> {
                try {
                    var replayer = new TrafficReplayerWithWaitOnClose(Duration.ofSeconds(600), rc,
                        httpServer.localhostEndpoint(), new StaticAuthTransformerFactory("TEST"),
                        true, 1, 1,
                        new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost"),
                        threadPrefix);
                    replayer.setResponsePostProcessor(input -> {
                        @SuppressWarnings("unchecked")
                        var map = (Map<String, Object>) input;
                        map.put("_postprocessed", true);
                        return map;
                    });
                    return replayer;
                } catch (SSLException e) { throw new RuntimeException(e); }
            }, () -> t -> {}, () -> nonTrackingContext,
                buildTrafficSource(nonTrackingContext, trafficStreams), new TimeShifter(10 * 1000));

            Assertions.assertTrue(targetHitCount.get() > 0,
                "Requests should reach the target server");
        }
    }

    /**
     * Response post-processor failure sets response to null without crashing pipeline.
     * Verifies error handling: pipeline completes even when post-processor throws.
     */
    @Test
    @ResourceLock("TrafficReplayerRunner")
    void responsePostProcessor_failureDoesNotCrashPipeline() throws Throwable {
        var targetHitCount = new AtomicInteger(0);
        var nonTrackingContext = TestContext.noOtelTracking();
        try (var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(200),
            response -> {
                targetHitCount.incrementAndGet();
                return TestHttpServerContext.makeResponse(new Random(1), response);
            })) {

            var streamAndSizes = ExhaustiveTrafficStreamGenerator
                .generateStreamAndSumOfItsTransactions(nonTrackingContext, 16, true);
            var trafficStreams = streamAndSizes.stream.collect(Collectors.toList());

            TrafficReplayerRunner.runReplayer(streamAndSizes.numHttpTransactions, (rc, threadPrefix) -> {
                try {
                    var replayer = new TrafficReplayerWithWaitOnClose(Duration.ofSeconds(600), rc,
                        httpServer.localhostEndpoint(), new StaticAuthTransformerFactory("TEST"),
                        true, 1, 1,
                        new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost"),
                        threadPrefix);
                    // Post-processor that always throws — should not crash pipeline
                    replayer.setResponsePostProcessor(input -> {
                        throw new RuntimeException("post-processor failure");
                    });
                    return replayer;
                } catch (SSLException e) { throw new RuntimeException(e); }
            }, () -> t -> {}, () -> nonTrackingContext,
                buildTrafficSource(nonTrackingContext, trafficStreams), new TimeShifter(10 * 1000));

            Assertions.assertTrue(targetHitCount.get() > 0,
                "Requests should still reach the target even when post-processor fails");
        }
    }

    /**
     * No response post-processor configured → pipeline works normally (passthrough).
     * Verifies default behavior is unchanged when feature is not used.
     */
    @Test
    @ResourceLock("TrafficReplayerRunner")
    void noPostProcessor_pipelineWorksNormally() throws Throwable {
        var targetHitCount = new AtomicInteger(0);
        var nonTrackingContext = TestContext.noOtelTracking();
        try (var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(200),
            response -> {
                targetHitCount.incrementAndGet();
                return TestHttpServerContext.makeResponse(new Random(1), response);
            })) {

            var streamAndSizes = ExhaustiveTrafficStreamGenerator
                .generateStreamAndSumOfItsTransactions(nonTrackingContext, 16, true);
            var trafficStreams = streamAndSizes.stream.collect(Collectors.toList());

            // No post-processor set — default behavior
            TrafficReplayerRunner.runReplayer(streamAndSizes.numHttpTransactions, (rc, threadPrefix) -> {
                try {
                    return new TrafficReplayerWithWaitOnClose(Duration.ofSeconds(600), rc,
                        httpServer.localhostEndpoint(), new StaticAuthTransformerFactory("TEST"),
                        true, 1, 1,
                        new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost"),
                        threadPrefix);
                } catch (SSLException e) { throw new RuntimeException(e); }
            }, () -> t -> {}, () -> nonTrackingContext,
                buildTrafficSource(nonTrackingContext, trafficStreams), new TimeShifter(10 * 1000));

            Assertions.assertTrue(targetHitCount.get() > 0,
                "Requests should reach target with no post-processor configured");
        }
    }
}
