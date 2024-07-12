package org.opensearch.migrations.replay.datahandlers.http;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.TestCapturePacketToHttpHandler;
import org.opensearch.migrations.replay.TransformationLoader;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonCompositeTransformer;
import org.opensearch.migrations.transform.RemovingAuthTransformerFactory;

@WrapWithNettyLeakDetection
class HttpJsonTransformingConsumerTest extends InstrumentationTest {

    private static Stream<Arguments> provideTestParameters() {
        Integer[] attemptedChunks = { 1, 2, 4, 8, 100, 1000, Integer.MAX_VALUE };
        Boolean[] transformationOptions = { true, false };
        String[] requestFiles = {
            "/requests/raw/post_formUrlEncoded_withFixedLength.txt",
            "/requests/raw/post_formUrlEncoded_withLargeHeader.txt",
            "/requests/raw/post_formUrlEncoded_withDuplicateHeaders.txt",
            "/requests/raw/get_withAuthHeader.txt" };

        return Stream.of(attemptedChunks)
            .flatMap(
                chunks -> Stream.of(transformationOptions)
                    .flatMap(
                        transformation -> Stream.of(requestFiles)
                            .map(file -> Arguments.of(chunks, transformation, file))
                    )
            );
    }

    @ParameterizedTest
    @MethodSource("provideTestParameters")
    public void testRequestProcessing(Integer attemptedChunks, Boolean hostTransformation, String requestFile)
        throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(
            Duration.ofMillis(Math.min(100 / attemptedChunks, 1)),
            dummyAggregatedResponse
        );
        var transformingHandler = new HttpJsonTransformingConsumer<AggregatedRawResponse>(
            new TransformationLoader().getTransformerFactoryLoader(hostTransformation ? "bar.example" : null),
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );
        byte[] testBytes;
        try (var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(requestFile)) {
            testBytes = sampleStream.readAllBytes();
        }

        var chunks = Math.min(attemptedChunks, testBytes.length);
        sliceRandomChunks(testBytes, chunks).forEach(chunk -> transformingHandler.consumeBytes(chunk));

        var returnedResponse = transformingHandler.finalizeRequest().get();

        var expectedBytes = (hostTransformation)
            ? new String(testBytes, StandardCharsets.UTF_8).replace("foo.example", "bar.example")
                .getBytes(StandardCharsets.UTF_8)
            : testBytes;

        var expectedTransformationStatus = (hostTransformation)
            ? HttpRequestTransformationStatus.COMPLETED
            : HttpRequestTransformationStatus.SKIPPED;

        Assertions.assertEquals(
            new String(expectedBytes, StandardCharsets.UTF_8),
            testPacketCapture.getCapturedAsString()
        );
        Assertions.assertArrayEquals(expectedBytes, testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(expectedTransformationStatus, returnedResponse.transformationStatus);

        var numConsumes = testPacketCapture.getNumConsumes().get();
        Assertions.assertTrue(
            chunks + 1 == numConsumes || chunks == numConsumes,
            "Expected output consumes to equal input consumes or input consumes + 1, but was " + numConsumes
        );
    }

    @Test
    public void testRemoveAuthHeadersWorks() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler = new HttpJsonTransformingConsumer<AggregatedRawResponse>(
            new TransformationLoader().getTransformerFactoryLoader("test.domain"),
            RemovingAuthTransformerFactory.instance,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );
        byte[] testBytes;
        try (
            var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(
                "/requests/raw/get_withAuthHeader.txt"
            )
        ) {
            testBytes = sampleStream.readAllBytes();
            testBytes = new String(testBytes, StandardCharsets.UTF_8).replace("foo.example", "test.domain")
                .replace("auTHorization: Basic YWRtaW46YWRtaW4=\r\n", "")
                .getBytes(StandardCharsets.UTF_8);
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        Assertions.assertEquals(new String(testBytes, StandardCharsets.UTF_8), testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(testBytes, testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(HttpRequestTransformationStatus.SKIPPED, returnedResponse.transformationStatus);
    }

    @Test
    public void testPartialBodyThrowsAndIsRedriven() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var complexTransformer = new JsonCompositeTransformer(new IJsonTransformer() {
            @Override
            public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
                // just walk everything - that's enough to touch the payload and throw
                walkMaps(incomingJson);
                return incomingJson;
            }

            private void walkMaps(Object o) {
                if (o instanceof Map) {
                    for (var v : ((Map) o).values()) {
                        walkMaps(v);
                    }
                }
            }
        });
        var transformingHandler = new HttpJsonTransformingConsumer<AggregatedRawResponse>(
            complexTransformer,
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );
        byte[] testBytes;
        try (
            var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(
                "/requests/raw/post_formUrlEncoded_withFixedLength.txt"
            )
        ) {
            var allBytes = sampleStream.readAllBytes();
            Arrays.fill(allBytes, allBytes.length - 30, allBytes.length, (byte) ' ');
            testBytes = Arrays.copyOfRange(allBytes, 0, allBytes.length);
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        Assertions.assertEquals(new String(testBytes, StandardCharsets.UTF_8), testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(testBytes, testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(HttpRequestTransformationStatus.ERROR, returnedResponse.transformationStatus);
        Assertions.assertInstanceOf(
            NettyJsonBodyAccumulateHandler.IncompleteJsonBodyException.class,
            returnedResponse.error
        );
    }

    public static List<byte[]> sliceRandomChunks(byte[] bytes, int numChunks) {
        Random random = new Random(0);
        List<Integer> chunkSizes = new ArrayList<>(numChunks);
        int totalSizeRemaining = bytes.length;
        for (int i = 0; i < numChunks - 1; i++) {
            // Ensure at least one byte per remaining chunk
            int remainingChunks = numChunks - i;
            int sizeForThisChunk = 1 + random.nextInt(totalSizeRemaining - remainingChunks + 1);
            chunkSizes.add(sizeForThisChunk);
            totalSizeRemaining -= sizeForThisChunk;
        }
        // Last chunk gets the remaining bytes
        chunkSizes.add(totalSizeRemaining);

        // Shuffle the array to distribute large chunks randomly
        Collections.shuffle(chunkSizes, random);

        var byteList = new ArrayList<byte[]>(numChunks);
        int start = 0;
        for (int size : chunkSizes) {
            byte[] chunk = Arrays.copyOfRange(bytes, start, start + size);
            byteList.add(chunk);
            start += size;
        }
        return byteList;
    }
}
