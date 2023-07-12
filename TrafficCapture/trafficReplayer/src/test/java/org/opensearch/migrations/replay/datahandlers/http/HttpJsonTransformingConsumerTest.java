package org.opensearch.migrations.replay.datahandlers.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.AggregatedTransformedResponse;
import org.opensearch.migrations.replay.TestCapturePacketToHttpHandler;
import org.opensearch.migrations.transform.CompositeJsonTransformer;
import org.opensearch.migrations.transform.JoltJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

class HttpJsonTransformingConsumerTest {
    @Test
    public void testPassThroughSinglePacketPost() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null,null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler = new HttpJsonTransformingConsumer(JoltJsonTransformer.newBuilder().build(),
                testPacketCapture, "TEST");
        byte[] testBytes;
        try (var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(
                "/requests/raw/post_formUrlEncoded_withFixedLength.txt")) {
            testBytes = sampleStream.readAllBytes();
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        Assertions.assertEquals(new String(testBytes, StandardCharsets.UTF_8),
                testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(testBytes, testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(AggregatedTransformedResponse.HttpRequestTransformationStatus.SKIPPED,
                returnedResponse.getTransformationStatus());
    }

    @Test
    public void testPassThroughSinglePacketWithoutBodyTransformationPost() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null,null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler = new HttpJsonTransformingConsumer(
                JoltJsonTransformer.newBuilder()
                        .addHostSwitchOperation("test.domain")
                        .build(),
                testPacketCapture, "TEST");
        byte[] testBytes;
        try (var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(
                "/requests/raw/post_formUrlEncoded_withFixedLength.txt")) {
            testBytes = sampleStream.readAllBytes();
            testBytes = new String(testBytes, StandardCharsets.UTF_8)
                    .replace("foo.example", "test.domain")
                    .getBytes(StandardCharsets.UTF_8);
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        Assertions.assertEquals(new String(testBytes, StandardCharsets.UTF_8),
                testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(testBytes, testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(AggregatedTransformedResponse.HttpRequestTransformationStatus.SKIPPED,
                returnedResponse.getTransformationStatus());
    }

    @Test
    public void testPartialBodyThrowsAndIsRedriven() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var complexTransformer = new CompositeJsonTransformer(new JsonTransformer() {
            @Override
            public Object transformJson(Object incomingJson) {
                // just walk everything - that's enough to touch the payload and throw
                walkMaps(incomingJson);
                return incomingJson;
            }
            private void walkMaps(Object o) {
                if (o instanceof Map) {
                    for (var v : ((Map)o).values()) {
                        walkMaps(v);
                    }
                }
            }
        });
        var transformingHandler = new HttpJsonTransformingConsumer(complexTransformer,  testPacketCapture, "TEST");
        byte[] testBytes;
        try (var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(
                "/requests/raw/post_formUrlEncoded_withFixedLength.txt")) {
            var allBytes = sampleStream.readAllBytes();
            Arrays.fill(allBytes, allBytes.length-10, allBytes.length, (byte)' ');
            testBytes = Arrays.copyOfRange(allBytes, 0, allBytes.length);
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        Assertions.assertEquals(new String(testBytes, StandardCharsets.UTF_8),
                testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(testBytes, testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(AggregatedTransformedResponse.HttpRequestTransformationStatus.ERROR,
                returnedResponse.getTransformationStatus());
        Assertions.assertInstanceOf(NettyJsonBodyAccumulateHandler.IncompleteJsonBodyException.class,
                returnedResponse.getErrorCause());
    }
}