package org.opensearch.migrations.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.util.ResourceLeakDetector;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.transform.JoltJsonTransformBuilder;
import org.opensearch.migrations.transform.JoltJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
public class PayloadRepackingTest {

    public static Stream<List<Object>> expandList(Stream<List<Object>> stream, List possibilities) {
        return stream.flatMap(list-> possibilities.stream().map(innerB -> {
            var rval = new ArrayList<Object>(list);
            rval.add(innerB);
            return rval;
        }));
    }

    public static Arguments[] makeCombinations() {
        List<Object> allBools = List.of(true, false);
        Stream<List<Object>> seedLists = allBools.stream().map(b->List.of(b));
        return expandList(expandList(seedLists, allBools), allBools)
               .map(list->Arguments.of(list.toArray(Object[]::new)))
                .toArray(Arguments[]::new);
    }

    @ParameterizedTest
    @MethodSource("makeCombinations")
    public void testSimplePayloadTransform(boolean doGzip, boolean doChunked) throws Exception {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        var transformerBuilder = JoltJsonTransformer.newBuilder();

        if (doGzip) { transformerBuilder.addCannedOperation(JoltJsonTransformBuilder.CANNED_OPERATION.ADD_GZIP); }
        if (doChunked) { transformerBuilder.addCannedOperation(JoltJsonTransformBuilder.CANNED_OPERATION.MAKE_CHUNKED); }

        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
                .mapToObj(i -> TestUtils.makeRandomString(r, 64))
                .map(o -> (String) o)
                .collect(Collectors.toList());

        DefaultHttpHeaders expectedRequestHeaders = new DefaultHttpHeaders();
        // netty's decompressor and aggregator remove some header values (& add others)
        expectedRequestHeaders.add("host", "localhost");
        expectedRequestHeaders.add("Content-Length", "46");

        runPipelineAndValidate(transformerBuilder.build(), null, stringParts,
                expectedRequestHeaders,
                referenceStringBuilder -> TestUtils.resolveReferenceString(referenceStringBuilder));
    }

    private static void runPipelineAndValidate(JsonTransformer transformer,
                                               String extraHeaders,
                                               List<String> stringParts,
                                               DefaultHttpHeaders expectedRequestHeaders,
                                               Function<StringBuilder,String> expectedOutputGenerator) throws Exception {
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100),
                new AggregatedRawResponse(-1, Duration.ZERO, new ArrayList<>()));
        var transformingHandler = new HttpJsonTransformingConsumer(transformer, testPacketCapture);

        var contentLength = stringParts.stream().mapToInt(s->s.length()).sum();
        var headerString = "GET / HTTP/1.1\n" +
                "host: localhost\n" +
                (extraHeaders == null ? "" : extraHeaders) +
                "content-length: " + contentLength + "\n\n";
        var referenceStringBuilder = new StringBuilder();
        var allConsumesFuture = TestUtils.chainedWriteHeadersAndDualWritePayloadParts(transformingHandler,
                        stringParts, referenceStringBuilder, headerString);

        var innermostFinalizeCallCount = new AtomicInteger();
        DiagnosticTrackableCompletableFuture<String,AggregatedTransformedResponse> finalizationFuture =
                allConsumesFuture.thenCompose(v -> transformingHandler.finalizeRequest(),
                        ()->"PayloadRepackingTest.runPipelineAndValidate.allConsumeFuture");
        finalizationFuture.map(f->f.whenComplete((aggregatedRawResponse, t) -> {
            Assertions.assertNull(t);
            Assertions.assertNotNull(aggregatedRawResponse);
            // do nothing but check connectivity between the layers in the bottom most handler
            innermostFinalizeCallCount.incrementAndGet();
        }), ()->"PayloadRepackingTest.runPipelineAndValidate.assertCheck");
        finalizationFuture.get();

        TestUtils.verifyCapturedResponseMatchesExpectedPayload(testPacketCapture.getBytesCaptured(),
                expectedRequestHeaders, expectedOutputGenerator.apply(referenceStringBuilder));
    }

    String simplePayloadTransform = "" +
            "  {\n" +
            "    \"operation\": \"shift\",\n" +
            "    \"spec\": {\n" +
            "      \"headers\": \"&\",\n" +
            "      \"method\": \"&\",\n" +
            "      \"URI\": \"&\",\n" +
            "      \"protocol\": \"&\",\n" +
            "      \"payload\": {\n" +
            "        \"inlinedJsonBody\": {\n" +
            "          \"top\": {\n" +
            "            \"*\": {\n" +
            "              \"$\": \"payload.inlinedJsonBody.top[#2].Name\",\n" +
            "              \"@\": \"payload.inlinedJsonBody.top[#2].Value\"\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n";

    @Test
    public void testJsonPayloadTransformation() throws Exception {
        var transformerBuilder = JoltJsonTransformer.newBuilder();

        ObjectMapper mapper = new ObjectMapper();
        var simpleTransform = mapper.readValue(simplePayloadTransform,
                new TypeReference<LinkedHashMap<String, Object>>(){});
        transformerBuilder.addCannedOperation(JoltJsonTransformBuilder.CANNED_OPERATION.PASS_THRU);
        transformerBuilder.addOperationObject(simpleTransform);

        var jsonPayload = "{\"top\": {\"A\": 1,\"B\": 2}}";
        String extraHeaders = "content-type: application/json; charset=UTF-8\n";

        DefaultHttpHeaders expectedRequestHeaders = new DefaultHttpHeaders();
        // netty's decompressor and aggregator remove some header values (& add others)
        expectedRequestHeaders.add("host", "localhost");
        expectedRequestHeaders.add("content-type", "application/json; charset=UTF-8");
        expectedRequestHeaders.add("Content-Length", "55");

        runPipelineAndValidate(transformerBuilder.build(), extraHeaders, List.of(jsonPayload),
                expectedRequestHeaders,
                x -> "{\"top\":[{\"Name\":\"A\",\"Value\":1},{\"Name\":\"B\",\"Value\":2}]}");
    }
}
