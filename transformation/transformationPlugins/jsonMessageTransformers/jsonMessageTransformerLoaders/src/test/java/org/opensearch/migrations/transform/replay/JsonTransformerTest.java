package org.opensearch.migrations.transform.replay;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.JsonJMESPathTransformer;
import org.opensearch.migrations.transform.JsonJoltTransformBuilder;
import org.opensearch.migrations.transform.JsonJoltTransformer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.burt.jmespath.jcf.JcfRuntime;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
@WrapWithNettyLeakDetection(disableLeakChecks = true)
class JsonTransformerTest {
    public static final String DUMMY_HOSTNAME_TEST_STRING = "THIS_IS_A_TEST_STRING_THAT_ONLY_EXISTS_IN_ONE_PLACE";
    ObjectMapper mapper = new ObjectMapper();
    static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE = new TypeReference<>() {
    };

    public JsonTransformerTest() {
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
    }

    private Map<String, Object> parseStringAsJson(String jsonStr) throws JsonProcessingException {
        return mapper.readValue(jsonStr, TYPE_REFERENCE_FOR_MAP_TYPE);
    }

    @SneakyThrows
    private Map<String, Object> parseSampleRequestFromResource(String path) {
        try (InputStream inputStream = JsonTransformerTest.class.getResourceAsStream("/requests/" + path)) {
            return mapper.readValue(inputStream, TYPE_REFERENCE_FOR_MAP_TYPE);
        }
    }

    private String emitJson(Object transformedDocument) throws JsonProcessingException {
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true); // optional
        return mapper.writeValueAsString(transformedDocument);
    }

    @Test
    public void testSimpleTransform() throws JsonProcessingException {
        final String TEST_DOCUMENT = "{\"Hello\":\"world\"}";
        var documentJson = parseStringAsJson(TEST_DOCUMENT);
        var transformer = JsonJoltTransformer.newBuilder()
                .addCannedOperation(JsonJoltTransformBuilder.CANNED_OPERATION.PASS_THRU)
                .build();
        var transformedDocument = transformer.transformJson(documentJson);
        var finalOutputStr = emitJson(transformedDocument);

        Assertions.assertEquals(TEST_DOCUMENT, finalOutputStr);
    }

    @Test
    public void testHttpTransform() throws IOException {
        var testResourceName = "parsed/post_formUrlEncoded_withFixedLength.json";
        final var documentJson = parseSampleRequestFromResource(testResourceName);
        var transformer = JsonJoltTransformer.newBuilder().addHostSwitchOperation(DUMMY_HOSTNAME_TEST_STRING).build();
        var transformedDocument = transformer.transformJson(documentJson);
        String transformedJsonOutputStr = emitJson(transformedDocument);
        log.atInfo().setMessage("transformed json document: {}").addArgument(transformedJsonOutputStr).log();
        Assertions.assertTrue(transformedJsonOutputStr.contains(DUMMY_HOSTNAME_TEST_STRING));
    }

        static final String TEST_INPUT_REQUEST = "{\n"
                + "  \"method\": \"PUT\",\n"
                + "  \"URI\": \"/oldStyleIndex\",\n"
                + "  \"headers\": {\n"
                + "    \"host\": \"127.0.0.1\"\n"
                + "  },\n"
                + "  \"payload\": {\n"
                + "    \"inlinedJsonBody\": {\n"
                + "      \"mappings\": {\n"
                + "        \"oldType\": {\n"
                + "          \"properties\": {\n"
                + "            \"field1\": {\n"
                + "              \"type\": \"text\"\n"
                + "            },\n"
                + "            \"field2\": {\n"
                + "              \"type\": \"keyword\"\n"
                + "            }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n";

        static final String EXCISE_TYPE_EXPRESSION_STRING = "{\n"
                + "  \"method\": method,\n"
                + "  \"URI\": URI,\n"
                + "  \"headers\": headers,\n"
                + "  \"payload\": {\n"
                + "    \"inlinedJsonBody\": {\n"
                + "      \"mappings\": payload.inlinedJsonBody.mappings.oldType\n"
                + "    }\n"
                + "  }\n"
                + "}";

        static Map<String, Object> parseStringAsJson(ObjectMapper mapper, String jsonStr) throws JsonProcessingException {
            return mapper.readValue(jsonStr, TYPE_REFERENCE_FOR_MAP_TYPE);
        }

        static String normalize(ObjectMapper mapper, String input) throws JsonProcessingException {
            return mapper.writeValueAsString(mapper.readTree(input));
        }

        static String emitJson(ObjectMapper mapper, Object transformedDocument) throws JsonProcessingException {
            mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true); // optional
            return mapper.writeValueAsString(transformedDocument);
        }

        @Test
        public void testSimpleTransformJMES() throws JsonProcessingException {
            var documentJson = parseStringAsJson(mapper, TEST_INPUT_REQUEST);
            var transformer = new JsonJMESPathTransformer(new JcfRuntime(), EXCISE_TYPE_EXPRESSION_STRING);
            var transformedDocument = transformer.transformJson(documentJson);
            var outputStr = emitJson(mapper, transformedDocument);

            final String TEST_OUTPUT_REQUEST = "{\n"
                    + "    \"method\": \"PUT\",\n"
                    + "    \"URI\": \"/oldStyleIndex\",\n"
                    + "  \"headers\": {\n"
                    + "    \"host\": \"127.0.0.1\"\n"
                    + "  },\n"
                    + "    \"payload\": {\n"
                    + "        \"inlinedJsonBody\": {\n"
                    + "            \"mappings\": {\n"
                    + "                \"properties\": {\n"
                    + "                    \"field1\": {\n"
                    + "                        \"type\": \"text\"\n"
                    + "                    },\n"
                    + "                    \"field2\": {\n"
                    + "                        \"type\": \"keyword\"\n"
                    + "                    }\n"
                    + "                }\n"
                    + "            }\n"
                    + "        }\n"
                    + "    }\n"
                    + "}";

            Assertions.assertEquals(normalize(mapper, TEST_OUTPUT_REQUEST), normalize(mapper, outputStr));
        }
    }
