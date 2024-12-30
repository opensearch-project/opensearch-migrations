package org.opensearch.migrations.transform.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.TestUtils;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.transform.JsonJoltTransformBuilder;
import org.opensearch.migrations.transform.JsonJoltTransformer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
public class PayloadRepackingTest extends InstrumentationTest {

    public static Stream<List<Object>> expandList(Stream<List<Object>> stream, List possibilities) {
        return stream.flatMap(list -> possibilities.stream().map(innerB -> {
            var rval = new ArrayList<Object>(list);
            rval.add(innerB);
            return rval;
        }));
    }

    public static Arguments[] makeCombinations() {
        List<Object> allBools = List.of(true, false);
        Stream<List<Object>> seedLists = allBools.stream().map(b -> List.of(b));
        return expandList(seedLists, allBools).map(list -> Arguments.of(list.toArray(Object[]::new)))
            .toArray(Arguments[]::new);
    }

    @ParameterizedTest
    @MethodSource("makeCombinations")
    public void testSimplePayloadTransform(boolean doGzip, boolean doChunked) throws Exception {
        var transformerBuilder = JsonJoltTransformer.newBuilder();
        if (doGzip) {
            transformerBuilder.addCannedOperation(JsonJoltTransformBuilder.CANNED_OPERATION.ADD_GZIP);
        }
        if (doChunked) {
            transformerBuilder.addCannedOperation(JsonJoltTransformBuilder.CANNED_OPERATION.MAKE_CHUNKED);
        }

        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
            .mapToObj(i -> TestUtils.makeRandomString(r, 64))
            .map(o -> (String) o)
            .collect(Collectors.toList());

        DefaultHttpHeaders expectedRequestHeaders = new DefaultHttpHeaders();
        // netty's decompressor and aggregator remove some header values (& add others)
        expectedRequestHeaders.add("Host", "localhost");
        if (doGzip || doChunked) {
            expectedRequestHeaders.add("content-length", "46");

        } else {
            expectedRequestHeaders.add("Content-Length", "46");

        }

        TestUtils.runPipelineAndValidate(
            rootContext,
            transformerBuilder.build(),
            null,
            null,
            stringParts,
            expectedRequestHeaders, TestUtils::resolveReferenceString
        );
    }

    String simplePayloadTransform = ""
        + "  {\n"
        + "    \"operation\": \"shift\",\n"
        + "    \"spec\": {\n"
        + "      \"headers\": \"&\",\n"
        + "      \"method\": \"&\",\n"
        + "      \"URI\": \"&\",\n"
        + "      \"protocol\": \"&\",\n"
        + "      \"payload\": {\n"
        + "        \"inlinedJsonBody\": {\n"
        + "          \"top\": {\n"
        + "            \"*\": {\n"
        + "              \"$\": \"payload.inlinedJsonBody.top[#2].Name\",\n"
        + "              \"@\": \"payload.inlinedJsonBody.top[#2].Value\"\n"
        + "            }\n"
        + "          }\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n";

    @Test
    public void testJsonPayloadTransformation() throws Exception {
        var transformerBuilder = JsonJoltTransformer.newBuilder();

        ObjectMapper mapper = new ObjectMapper();
        var simpleTransform = mapper.readValue(
            simplePayloadTransform,
            new TypeReference<LinkedHashMap<String, Object>>() {
            }
        );
        transformerBuilder.addCannedOperation(JsonJoltTransformBuilder.CANNED_OPERATION.PASS_THRU);
        transformerBuilder.addOperationObject(simpleTransform);

        var jsonPayload = "{\"top\": {\"A\": 1,\"B\": 2}}";
        String extraHeaders = "content-type: application/json; charset=UTF-8\n";

        DefaultHttpHeaders expectedRequestHeaders = new DefaultHttpHeaders();
        // netty's decompressor and aggregator remove some header values (& add others)
        expectedRequestHeaders.add("Host", "localhost");
        expectedRequestHeaders.add("content-type", "application/json; charset=UTF-8");
        expectedRequestHeaders.add("Content-Length", "55");

        TestUtils.runPipelineAndValidate(
            rootContext,
            transformerBuilder.build(),
            null,
            extraHeaders,
            List.of(jsonPayload),
            expectedRequestHeaders,
            x -> "{\"top\":[{\"Name\":\"A\",\"Value\":1},{\"Name\":\"B\",\"Value\":2}]}"
        );
    }
}
