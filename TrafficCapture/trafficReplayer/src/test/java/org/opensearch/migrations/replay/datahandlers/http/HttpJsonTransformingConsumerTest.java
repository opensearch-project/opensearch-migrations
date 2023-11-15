package org.opensearch.migrations.replay.datahandlers.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.TestCapturePacketToHttpHandler;
import org.opensearch.migrations.replay.TestRequestKey;
import org.opensearch.migrations.replay.TransformationLoader;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonCompositeTransformer;
import org.opensearch.migrations.transform.RemovingAuthTransformerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

@WrapWithNettyLeakDetection
class HttpJsonTransformingConsumerTest {
    @Test
    public void testPassThroughSinglePacketPost() throws Exception {
        final var dummyAggregatedResponse =
                new AggregatedRawResponse(17, null, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler =
                new HttpJsonTransformingConsumer<AggregatedRawResponse>(new TransformationLoader()
                        .getTransformerFactoryLoader(null),
                        null, testPacketCapture, "TEST",
                        TestRequestKey.getTestConnectionRequestId(0));
        byte[] testBytes;
        try (var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(
                "/requests/raw/post_formUrlEncoded_withFixedLength.txt")) {
            testBytes = sampleStream.readAllBytes();
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        Assertions.assertEquals(new String(testBytes, StandardCharsets.UTF_8), testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(testBytes, testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(HttpRequestTransformationStatus.SKIPPED, returnedResponse.transformationStatus);
    }

    @Test
    public void testPassThroughSinglePacketWithoutBodyTransformationPost() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler =
                new HttpJsonTransformingConsumer<AggregatedRawResponse>(
                        new TransformationLoader().getTransformerFactoryLoader("test.domain"),
                        null, testPacketCapture, "TEST",
                        TestRequestKey.getTestConnectionRequestId(0));
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
        Assertions.assertEquals(HttpRequestTransformationStatus.SKIPPED, returnedResponse.transformationStatus);
    }

    @Test
    public void testRemoveAuthHeadersWorks() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler =
                new HttpJsonTransformingConsumer<AggregatedRawResponse>(
                        new TransformationLoader().getTransformerFactoryLoader("test.domain"),
                        RemovingAuthTransformerFactory.instance,
                        testPacketCapture, "TEST",
                        TestRequestKey.getTestConnectionRequestId(0));
        byte[] testBytes;
        try (var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(
                "/requests/raw/get_withAuthHeader.txt")) {
            testBytes = sampleStream.readAllBytes();
            testBytes = new String(testBytes, StandardCharsets.UTF_8)
                    .replace("foo.example", "test.domain")
                    .replace("auTHorization: Basic YWRtaW46YWRtaW4=\n", "")
                    .getBytes(StandardCharsets.UTF_8);
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        Assertions.assertEquals(new String(testBytes, StandardCharsets.UTF_8),
                testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(testBytes, testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(HttpRequestTransformationStatus.SKIPPED, returnedResponse.transformationStatus);
    }

    @Test
    public void testPartialBodyThrowsAndIsRedriven() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var complexTransformer = new JsonCompositeTransformer(new IJsonTransformer() {
            @Override
            public Map<String,Object> transformJson(Map<String,Object> incomingJson) {
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
        var transformingHandler =
                new HttpJsonTransformingConsumer<AggregatedRawResponse>(complexTransformer, null,
                        testPacketCapture, "TEST", TestRequestKey.getTestConnectionRequestId(0));
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
        Assertions.assertEquals(HttpRequestTransformationStatus.ERROR, returnedResponse.transformationStatus);
        Assertions.assertInstanceOf(NettyJsonBodyAccumulateHandler.IncompleteJsonBodyException.class,
                returnedResponse.error);
    }


}