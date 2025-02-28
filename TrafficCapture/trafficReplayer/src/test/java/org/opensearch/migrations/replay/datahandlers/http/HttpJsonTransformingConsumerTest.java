package org.opensearch.migrations.replay.datahandlers.http;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.TestCapturePacketToHttpHandler;
import org.opensearch.migrations.replay.TestUtils;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonCompositeTransformer;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;
import org.opensearch.migrations.transform.RemovingAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.utils.TrackedFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@WrapWithNettyLeakDetection
class HttpJsonTransformingConsumerTest extends InstrumentationTest {

    private static final String NDJSON_TEST_REQUEST = (
        "POST /test HTTP/1.1\r\n" +
            "Host: foo.example\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 97\r\n" +
            "\r\n" +
            "{\"index\":{\"_index\":\"test\",\"_id\":\"2\"}}\n" +
            "{\"field1\":\"value1\"}\n" +
            "{\"delete\":{\"_index\":\"test\",\"_id\":\"1\"}}\n");

    private static Stream<Arguments> provideTestParameters() {
        Integer[] attemptedChunks = { 1, 2, 4, 8, 100, 1000, Integer.MAX_VALUE };
        Boolean[] transformationOptions = { true, };
        String[] requestFiles = {
            "/requests/raw/post_formUrlEncoded_withFixedLength.txt",
            "/requests/raw/post_formUrlEncoded_withLargeHeader.txt",
            "/requests/raw/post_formUrlEncoded_withDuplicateHeaders.txt",
            "/requests/raw/get_withAuthHeader.txt",
            "/requests/raw/post_json_gzip.gz",
            "/requests/raw/post_withPlainText.txt",
        };

        return Stream.of(attemptedChunks)
            .flatMap(
                chunks -> Stream.of(transformationOptions)
                    .flatMap(
                        transformation -> Stream.of(requestFiles)
                            .map(file -> Arguments.of(chunks, transformation, file))
                    )
            );
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testSomeRequestProcessing() throws Exception {
        var args = provideTestParameters().findFirst().get();
        testRequestProcessing((Integer) args.get()[0], (Boolean) args.get()[1], (String) args.get()[2]);
    }

    @ParameterizedTest
    @MethodSource("provideTestParameters")
    @Tag("longTest")
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testRequestProcessing(Integer attemptedChunks, Boolean hostTransformation, String requestFile)
        throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(
            Duration.ofMillis(Math.min(100 / attemptedChunks, 1)),
            dummyAggregatedResponse
        );
        var transformingHandler = new HttpJsonTransformingConsumer<>(
            new TransformationLoader().getTransformerFactoryLoaderWithNewHostName(hostTransformation ? "bar.example" : null),
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );
        byte[] testBytes;
        try (var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(requestFile)) {
            testBytes = sampleStream.readAllBytes();
        }

        var chunks = Math.min(attemptedChunks, testBytes.length);
        sliceRandomChunks(testBytes, chunks).forEach(transformingHandler::consumeBytes);

        var returnedResponse = transformingHandler.finalizeRequest().get();

        var expectedBytes = (hostTransformation)
            ? replaceBytes(testBytes,
                "foo.example".getBytes(StandardCharsets.UTF_8),
                "bar.example".getBytes(StandardCharsets.UTF_8))
            : testBytes;

        Assertions.assertEquals(testBytes.length, expectedBytes.length, "Expected transformation byte length to not change."
            + "This can occur due to charset parsing differences with encoded body bytes");

        var expectedTransformationStatus = (hostTransformation)
            ? HttpRequestTransformationStatus.completed()
            : HttpRequestTransformationStatus.skipped();


        Assertions.assertEquals(expectedTransformationStatus, returnedResponse.transformationStatus);
        Assertions.assertEquals(
            new String(expectedBytes, StandardCharsets.UTF_8),
            testPacketCapture.getCapturedAsString()
        );
        Assertions.assertArrayEquals(expectedBytes, testPacketCapture.getBytesCaptured());

        var numConsumes = testPacketCapture.getNumConsumes().get();
        Assertions.assertTrue(
            chunks + 1 == numConsumes || chunks == numConsumes,
            "Expected output consumes to equal input consumes or input consumes + 1, but was " + numConsumes
        );
    }

    @Test
    public void testRemoveAuthHeadersWorks() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler = new HttpJsonTransformingConsumer<>(
            new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("test.domain"),
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
        Assertions.assertEquals(HttpRequestTransformationStatus.skipped(), returnedResponse.transformationStatus);
        Assertions.assertEquals(new String(testBytes, StandardCharsets.UTF_8), testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(testBytes, testPacketCapture.getBytesCaptured());
    }

    @Test
    @Tag("longTest")
    public void testRemovePayloadWorks() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        String redactBody = "{ " +
            "    \"operation\": \"modify-overwrite-beta\", " +
            "    \"spec\": { " +
            "       \"payload\": { " +
            "         \"inlinedTextBody\": \"ReplacedPlainText\" " +
            "       } " +
            "   } " +
            "}";
        String fullConfig = "[{\"JsonJoltTransformerProvider\": { \"script\": " + redactBody + "}}]";
        IJsonTransformer jsonJoltTransformer = new TransformationLoader().getTransformerFactoryLoader(fullConfig);

        var transformingHandler = new HttpJsonTransformingConsumer<>(
            jsonJoltTransformer,
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );
        byte[] testBytes;
        try (
            var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(
                "/requests/raw/post_withPlainText.txt"
            )
        ) {
            assert sampleStream != null;
            testBytes = sampleStream.readAllBytes();
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        var expectedString = new String(testBytes, StandardCharsets.UTF_8)
            .replace("This is a test\r\n","ReplacedPlainText")
            .replace("Content-Length: 15", "Content-Length: 17");
        Assertions.assertEquals(expectedString, testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(expectedString.getBytes(StandardCharsets.UTF_8),
            testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(HttpRequestTransformationStatus.completed(), returnedResponse.transformationStatus);
        Assertions.assertNull(returnedResponse.transformationStatus.getException());
    }

    @Test
    @Tag("longTest")
    public void testRemoveCompressionWorks() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        String redactBody = "{ " +
                "    \"operation\": \"remove\", " +
                "    \"spec\": { " +
                "       \"headers\": { " +
                "         \"cOnTeNt-Encoding\": \"\"" +
                "       } " +
                "   } " +
                "}";
        String fullConfig = "[{\"JsonJoltTransformerProvider\": { \"script\": " + redactBody + "}}]";
        IJsonTransformer jsonJoltTransformer = new TransformationLoader().getTransformerFactoryLoader(fullConfig);

        var transformingHandler = new HttpJsonTransformingConsumer<>(
                jsonJoltTransformer,
                null,
                testPacketCapture,
                rootContext.getTestConnectionRequestContext(0)
        );
        byte[] testBytes;
        try (
                var sampleStream = HttpJsonTransformingConsumer.class.getResourceAsStream(
                        "/requests/raw/post_json_gzip.gz"
                )
        ) {
            assert sampleStream != null;
            testBytes = sampleStream.readAllBytes();
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        var expectedString = new String(testBytes, StandardCharsets.UTF_8)
                .replace("Content-Encoding: gzip\r\n", "")
                .replaceAll("Content-Length: .*", "Content-Length: 45")
                .replaceAll("(Content-Length: .*[\r\n]*)[\\s\\S]*", "$1"+
                        "{\"name\": \"John\", \"age\": 30, \"city\": \"Austin\"}");
        Assertions.assertEquals(expectedString, testPacketCapture.getCapturedAsString());
        Assertions.assertEquals(HttpRequestTransformationStatus.completed(), returnedResponse.transformationStatus);
        Assertions.assertNull(returnedResponse.transformationStatus.getException());
    }

    @Test
    public void testPartialBodyIsPassedThrough() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var complexTransformer = new JsonCompositeTransformer(new IJsonTransformer() {
            @Override
            public Object transformJson(Object incomingJson) {
                var payload = (Map) ((Map) incomingJson).get("payload");
                Assertions.assertNull(payload.get(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY));
                Assertions.assertNull(payload.get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY));
                ((Map) ((Map) incomingJson).get("headers"))
                    .put("extraKey", "extraValue");
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
        var expectedString = new String(testBytes, StandardCharsets.UTF_8)
            .replace("\r\n\r\n","\r\nextraKey: extraValue\r\n\r\n");
        Assertions.assertEquals(expectedString, testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(expectedString.getBytes(StandardCharsets.UTF_8),
            testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(HttpRequestTransformationStatus.completed(), returnedResponse.transformationStatus);
        Assertions.assertNull(returnedResponse.transformationStatus.getException());
    }

    @Test
    public void testNewlineDelimitedJsonBodyIsHandled() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 19, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var sizeCalculatingTransformer = new JsonCompositeTransformer(incomingJson -> {
            var payload = (Map) ((Map) incomingJson).get("payload");
            Assertions.assertNull(payload.get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY));
            Assertions.assertNull(payload.get(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY));
            var list = (List) payload.get(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY);
            ((Map) ((Map) incomingJson).get("headers"))
                .put("listSize", ""+list.size());
            return incomingJson;
        });
        var transformingHandler = new HttpJsonTransformingConsumer<AggregatedRawResponse>(
            sizeCalculatingTransformer,
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );

        transformingHandler.consumeBytes(NDJSON_TEST_REQUEST.getBytes(StandardCharsets.UTF_8));
        var returnedResponse = transformingHandler.finalizeRequest().get();
        var expectedString = NDJSON_TEST_REQUEST.replace("\r\n\r\n","\r\nlistSize: 3\r\n\r\n");
        Assertions.assertEquals(expectedString, testPacketCapture.getCapturedAsString());
        Assertions.assertEquals(HttpRequestTransformationStatus.completed(), returnedResponse.transformationStatus);
        Assertions.assertNull(returnedResponse.transformationStatus.getException());
    }

    @Test
    public void testPartialNewlineDelimitedJsonBodyIsHandled() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 19, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var sizeCalculatingTransformer = new JsonCompositeTransformer(incomingJson -> {
            var payload = (Map) ((Map) incomingJson).get("payload");
            Assertions.assertFalse(payload.containsKey(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY));
            Assertions.assertFalse(payload.containsKey(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY));
            Assertions.assertNotNull(payload.get(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY));
            var list = (List) payload.get(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY);
            var leftoverString = (String) payload.get(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY);
            var headers = (Map<String,Object>) ((Map<String,Object>) incomingJson).get("headers");
            headers.put("listSize", "" + list.size());
            headers.put("leftover", "" + leftoverString.getBytes(StandardCharsets.UTF_8).length);
            return incomingJson;
        });
        var transformingHandler = new HttpJsonTransformingConsumer<AggregatedRawResponse>(
            sizeCalculatingTransformer,
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );

        var testString = NDJSON_TEST_REQUEST
            .replace("Content-Length: 97", "Content-Length: 87")
            .substring(0, NDJSON_TEST_REQUEST.length()-10);
        var testBytes = testString.getBytes(StandardCharsets.UTF_8);
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        var expectedString = new String(testBytes, StandardCharsets.UTF_8)
            .replace("\r\n\r\n","\r\nlistSize: 2\r\nleftover: 30\r\n\r\n");
        Assertions.assertEquals(expectedString, testPacketCapture.getCapturedAsString());
        Assertions.assertEquals(HttpRequestTransformationStatus.completed(), returnedResponse.transformationStatus);
        Assertions.assertNull(returnedResponse.transformationStatus.getException());
    }

    @Test
    public void testMalformedPayload_andThrowingTransformation_IsPassedThrough() throws Exception {
        final String HOST_NAME = "foo.example";
        var referenceStringBuilder = new StringBuilder();
        // mock object. values don't matter at all - not what we're testing
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 12, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);

        var transformingHandler = new HttpJsonTransformingConsumer<>(
            new TransformationLoader().getTransformerFactoryLoader(
                HOST_NAME,
                null,
                new ObjectMapper().writeValueAsString(List.of(
                    Map.of("JsonJinjavaTransformerProvider", Map.of(
                        "template", "{%- throw \"intentional exception\" -%}"
                    ))
                ))),
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );

        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
            .mapToObj(i -> TestUtils.makeRandomString(r, 10))
            .map(o -> (String) o)
            .collect(Collectors.toList());

        TrackedFuture<String, Void> allConsumesFuture = TestUtils.chainedDualWriteHeaderAndPayloadParts(
            transformingHandler,
            stringParts,
            referenceStringBuilder,
            contentLength -> "PUT /foo HTTP/1.1\r\n"
                + "HoSt: " + HOST_NAME + "\r\n"
                + "content-type: application/json\r\n"
                + "content-length: "
                + contentLength
                + "\r\n"
        );

        var finalizationFuture = allConsumesFuture.getDeferredFutureThroughHandle(
            (v,t) -> transformingHandler.finalizeRequest(),
            () -> "HeaderTransformTest.testMalformedPayload_andTypeMappingUri_IsPassedThrough"
        );
        var outputAndResult = finalizationFuture.get();
        Assertions.assertInstanceOf(TransformationException.class,
            TrackedFuture.unwindPossibleCompletionException(outputAndResult.transformationStatus.getException()));
        var combinedOutputBuf = outputAndResult.transformedOutput.getResponseAsByteBuf();
        Assertions.assertTrue(combinedOutputBuf.readableBytes() == 0);
        combinedOutputBuf.release();
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

    public static byte[] replaceBytes(byte[] originalBytes, byte[] targetBytes, byte[] replacementBytes) {
        ByteBuf buffer = Unpooled.wrappedBuffer(originalBytes);
        ByteBuf target = Unpooled.wrappedBuffer(targetBytes);
        ByteBuf replacement = Unpooled.wrappedBuffer(replacementBytes);
        ByteBuf resultBuffer = null;
        try {
            int matchIndex = indexOf(buffer, target);

            if (matchIndex == -1) {
                // No match, return original bytes
                return originalBytes;
            }
            resultBuffer = Unpooled.buffer();
            resultBuffer.writeBytes(buffer, 0, matchIndex);
            resultBuffer.writeBytes(replacement);
            resultBuffer.writeBytes(buffer, matchIndex + target.readableBytes(), buffer.readableBytes() - (matchIndex + target.readableBytes()));
            byte[] resultBytes = new byte[resultBuffer.readableBytes()];
            resultBuffer.readBytes(resultBytes);
            return resultBytes;
        } finally {
            ReferenceCountUtil.release(buffer);
            ReferenceCountUtil.release(target);
            ReferenceCountUtil.release(replacement);
            ReferenceCountUtil.release(resultBuffer);
        }
    }

    // Helper method to find the index of target bytes in the buffer
    private static int indexOf(ByteBuf buffer, ByteBuf target) {
        final int bufferLength = buffer.readableBytes();
        final int targetLength = target.readableBytes();

        if (targetLength == 0 || bufferLength < targetLength) {
            return -1;  // No match possible if target is empty or buffer is smaller
        }

        byte firstByte = target.getByte(0);
        for (int i = 0; i <= bufferLength - targetLength; i++) {
            if (buffer.getByte(buffer.readerIndex() + i) != firstByte) {
                continue;
            }

            boolean found = true;
            for (int j = 1; j < targetLength; j++) {
                if (buffer.getByte(buffer.readerIndex() + i + j) != target.getByte(j)) {
                    found = false;
                    break;
                }
            }

            if (found) {
                return i;
            }
        }

        return -1;  // Target not found
    }
}
