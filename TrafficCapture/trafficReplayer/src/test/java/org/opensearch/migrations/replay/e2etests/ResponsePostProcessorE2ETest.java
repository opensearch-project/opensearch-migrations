package org.opensearch.migrations.replay.e2etests;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// REBUILD-LIMBO(G10) -- inherited bodies remain marked and recoverable; the live G9
// replacement follows the marked regions. Javadoc stays outside the regions.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: ExhaustiveTrafficStreamGenerator ISimpleTrafficCaptureSource ITrafficSourceContexts ITrafficStreamKey PojoTrafficStreamAndKey . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import javax.net.ssl.SSLException;

import java.io.EOFException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
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

*/
// REBUILD-LIMBO-END(G10)

@Tag("longTest")
class ResponsePostProcessorE2ETest extends FullTrafficReplayerTest {

    @Test
    void deployedPostProcessorTransformsTheTupleResponse() throws Exception {
        var result = replayWithPostProcessor(input -> {
            @SuppressWarnings("unchecked")
            var response = new LinkedHashMap<>((Map<String, Object>) input);
            response.put("_postprocessed", true);
            return response;
        });

        var response = firstTargetResponse(result);
        Assertions.assertEquals(true, response.get("_postprocessed"));
        Assertions.assertTrue(result.fatalFailures().isEmpty());
    }

    @Test
    void postProcessorFailureLeavesOnlyThatResponseEmptyAndStillCommits()
        throws Exception {
        var result = replayWithPostProcessor(input -> {
            throw new IllegalStateException("test post-processor failure");
        });

        var targetResponses = targetResponses(result);
        Assertions.assertEquals(1, targetResponses.size());
        Assertions.assertNull(targetResponses.get(0));
        Assertions.assertEquals(
            1L,
            result.committedOffsets().get(TOPIC_PARTITION).offset()
        );
        Assertions.assertTrue(result.fatalFailures().isEmpty());
    }

    private static ReplayResult replayWithPostProcessor(
        IJsonTransformer postProcessor
    ) throws Exception {
        try (var server = SimpleNettyHttpServer.makeServer(false, request ->
            new SimpleHttpResponse(
                Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                "OK".getBytes(StandardCharsets.UTF_8),
                "OK",
                200
            )
        )) {
            return replayOne(
                server.localhostEndpoint(),
                requestResponseAndClose("GET /postprocess HTTP/1.1\r\nHost: source\r\n\r\n"),
                () -> new TransformationLoader()
                    .getTransformerFactoryLoaderWithNewHostName("localhost"),
                () -> postProcessor
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> targetResponses(
        ReplayResult result
    ) {
        return (List<Map<String, Object>>) result.tuples()
            .get(0)
            .get("targetResponses");
    }

    private static Map<String, Object> firstTargetResponse(ReplayResult result) {
        var responses = targetResponses(result);
        Assertions.assertEquals(1, responses.size());
        return responses.get(0);
    }
}
/**
 * End-to-end integration tests for response post-processor extension point.
 * Exercises the full pipeline: traffic source → target → response → post-processor → tuple.
 */
// REBUILD-LIMBO-START(G10)
/*
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
            public CompletableFuture<List<org.opensearch.migrations.replay.traffic.source.SourceInput>>
            readNextTrafficStreamChunk(
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
            public CompletionStage<Void> acknowledgeSessionTermination(ConnectionSessionKey sessionKey) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey) {
                // This fixture has no per-connection source registry.
            }
        };
    }

*/
// REBUILD-LIMBO-END(G10)
    /**
     * Response post-processor transforms target responses during pipeline execution.
     * Verifies the post-processor is invoked on actual HTTP responses from the target.
     */
// REBUILD-LIMBO-START(G10)
/*
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

*/
// REBUILD-LIMBO-END(G10)
    /**
     * Response post-processor failure sets response to null without crashing pipeline.
     * Verifies error handling: pipeline completes even when post-processor throws.
     */
// REBUILD-LIMBO-START(G10)
/*
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

*/
// REBUILD-LIMBO-END(G10)
    /**
     * No response post-processor configured → pipeline works normally (passthrough).
     * Verifies default behavior is unchanged when feature is not used.
     */
// REBUILD-LIMBO-START(G10)
/*
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

*/
// REBUILD-LIMBO-END(G10)
