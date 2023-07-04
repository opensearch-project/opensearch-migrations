package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.transform.JoltJsonTransformer;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class HeaderTransformerTest {

    private static final String SILLY_TARGET_CLUSTER_NAME = "remoteguest";
    private static final String SOURCE_CLUSTER_NAME = "localhost";

    @Test
    public void testTransformer() throws Exception {
        // mock object.  values don't matter at all - not what we're testing
        final var dummyAggregatedResponse = new AggregatedTransformedResponse(17, null,
                null, AggregatedTransformedResponse.HttpRequestTransformationStatus.COMPLETED);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var jsonHandler = JoltJsonTransformer.newBuilder()
                .addHostSwitchOperation(SILLY_TARGET_CLUSTER_NAME)
                .build();
        var transformingHandler = new HttpJsonTransformingConsumer(jsonHandler, testPacketCapture, "TEST");
        runRandomPayloadWithTransformer(transformingHandler, dummyAggregatedResponse, testPacketCapture,
                contentLength -> "GET / HTTP/1.1\n" +
                        "HoSt: " + SOURCE_CLUSTER_NAME + "\n" +
                        "content-length: " + contentLength + "\n");
    }

    private void runRandomPayloadWithTransformer(HttpJsonTransformingConsumer transformingHandler,
                                                 AggregatedRawResponse dummyAggregatedResponse,
                                                 TestCapturePacketToHttpHandler testPacketCapture,
                                                 Function<Integer,String> makeHeaders)
            throws ExecutionException, InterruptedException
    {
        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1).mapToObj(i-> TestUtils.makeRandomString(r, 10)).map(o->(String)o)
                .collect(Collectors.toList());

        var referenceStringBuilder = new StringBuilder();
        DiagnosticTrackableCompletableFuture<String,Void> allConsumesFuture =
                TestUtils.chainedDualWriteHeaderAndPayloadParts(transformingHandler,
                        stringParts,
                        referenceStringBuilder,
                        makeHeaders
                );

        var innermostFinalizeCallCount = new AtomicInteger();
        var finalizationFuture = allConsumesFuture.thenCompose(v->transformingHandler.finalizeRequest(),
                ()->"HeaderTransformerTest.runRandomPayloadWithTransformer.allConsumes");
        finalizationFuture.map(f->f.whenComplete((aggregatedRawResponse,t)->{
            Assertions.assertNull(t);
            Assertions.assertNotNull(aggregatedRawResponse);
            // do nothing but check connectivity between the layers in the bottom most handler
            innermostFinalizeCallCount.incrementAndGet();
            Assertions.assertEquals(dummyAggregatedResponse, aggregatedRawResponse);
        }), ()->"HeaderTransformerTest.runRandomPayloadWithTransformer.assertionCheck");
        finalizationFuture.get();
        Assertions.assertEquals(TestUtils.resolveReferenceString(referenceStringBuilder,
                        List.of(new AbstractMap.SimpleEntry(SOURCE_CLUSTER_NAME, SILLY_TARGET_CLUSTER_NAME))),
                testPacketCapture.getCapturedAsString());
        Assertions.assertEquals(1, innermostFinalizeCallCount.get());
    }

    @Test
    public void testMalformedPayloadIsPassedThrough() throws Exception {
        var referenceStringBuilder = new StringBuilder();
        // mock object.  values don't matter at all - not what we're testing
        final var dummyAggregatedResponse = new AggregatedTransformedResponse(12, null,
                null, AggregatedTransformedResponse.HttpRequestTransformationStatus.COMPLETED);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler = new HttpJsonTransformingConsumer(
                TrafficReplayer.buildDefaultJsonTransformer(SILLY_TARGET_CLUSTER_NAME, "Basic YWRtaW46YWRtaW4="),
                testPacketCapture, "TEST");

        runRandomPayloadWithTransformer(transformingHandler, dummyAggregatedResponse, testPacketCapture,
                contentLength -> "GET / HTTP/1.1\n" +
                        "HoSt: " + SOURCE_CLUSTER_NAME + "\n" +
                        "content-type: application/json\n" +
                        "content-length: " + contentLength + "\n" +
                        "authorization: Basic YWRtaW46YWRtaW4=\n");
    }

    /**
     * Fixing this one will involve some thought.  Where should we unwind to?  I would say probably all
     * the way back to the HttpTransformer.
     * @throws Exception
     */
    @Test
    public void testMalformedPayload_andTypeMappingUri_IsPassedThrough() throws Exception {
        var referenceStringBuilder = new StringBuilder();
        // mock object.  values don't matter at all - not what we're testing
        final var dummyAggregatedResponse = new AggregatedTransformedResponse(12, null,
                null, AggregatedTransformedResponse.HttpRequestTransformationStatus.COMPLETED);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler = new HttpJsonTransformingConsumer(
                TrafficReplayer.buildDefaultJsonTransformer(SILLY_TARGET_CLUSTER_NAME, null),
                testPacketCapture, "TEST");

        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1).mapToObj(i-> TestUtils.makeRandomString(r, 10)).map(o->(String)o)
                .collect(Collectors.toList());

        DiagnosticTrackableCompletableFuture<String,Void> allConsumesFuture =
                TestUtils.chainedDualWriteHeaderAndPayloadParts(transformingHandler,
                        stringParts,
                        referenceStringBuilder,
                        contentLength -> "PUT /foo HTTP/1.1\n" +
                                "HoSt: " + SOURCE_CLUSTER_NAME + "\n" +
                                "content-type: application/json\n" +
                                "content-length: " + contentLength + "\n"
                );

        var finalizationFuture = allConsumesFuture.thenCompose(v->transformingHandler.finalizeRequest(),
                ()->"HeaderTransformTest.testMalformedPayload_andTypeMappingUri_IsPassedThrough");
        Assertions.assertThrows(Exception.class, ()->finalizationFuture.get());
    }
}
