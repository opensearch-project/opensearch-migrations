package org.opensearch.migrations.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.burt.jmespath.jcf.JcfRuntime;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.JsonJMESPathTransformer;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@WrapWithNettyLeakDetection(disableLeakChecks = true)
class JsonTransformerTest {
    static final String TEST_INPUT_REQUEST = "{\n" +
            "  \"method\": \"PUT\",\n" +
            "  \"URI\": \"/oldStyleIndex\",\n" +
            "  \"headers\": {\n" +
            "    \"host\": \"127.0.0.1\"\n" +
            "  },\n"+
            "  \"payload\": {\n" +
            "    \"inlinedJsonBody\": {\n" +
            "      \"mappings\": {\n" +
            "        \"oldType\": {\n" +
            "          \"properties\": {\n" +
            "            \"field1\": {\n" +
            "              \"type\": \"text\"\n" +
            "            },\n" +
            "            \"field2\": {\n" +
            "              \"type\": \"keyword\"\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}\n";

    static final String EXCISE_TYPE_EXPRESSION_STRING = "{\n" +
            "  \"method\": method,\n" +
            "  \"URI\": URI,\n" +
            "  \"headers\": headers,\n" +
            "  \"payload\": {\n" +
            "    \"inlinedJsonBody\": {\n" +
            "      \"mappings\": payload.inlinedJsonBody.mappings.oldType\n" +
            "    }\n" +
            "  }\n" +
            "}";

    ObjectMapper mapper = new ObjectMapper();
    static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE = new TypeReference<>(){};

    public JsonTransformerTest() {
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
    }

    static Map<String, Object> parseStringAsJson(ObjectMapper mapper, String jsonStr) throws JsonProcessingException {
        return mapper.readValue(jsonStr, TYPE_REFERENCE_FOR_MAP_TYPE);
    }

    static String normalize(ObjectMapper mapper, String input) throws JsonProcessingException {
        return mapper.writeValueAsString(mapper.readTree(input));
    }

    static String emitJson(ObjectMapper mapper, Object transformedDocument) throws JsonProcessingException {
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true); //optional
        return mapper.writeValueAsString(transformedDocument);
    }

    @Test
    public void testSimpleTransform() throws JsonProcessingException {
        var documentJson = parseStringAsJson(mapper, TEST_INPUT_REQUEST);
        var transformer = new JsonJMESPathTransformer(new JcfRuntime(), EXCISE_TYPE_EXPRESSION_STRING);
        var transformedDocument = transformer.transformJson(documentJson);
        var outputStr = emitJson(mapper, transformedDocument);

        final String TEST_OUTPUT_REQUEST = "{\n" +
                "    \"method\": \"PUT\",\n" +
                "    \"URI\": \"/oldStyleIndex\",\n" +
                "  \"headers\": {\n" +
                "    \"host\": \"127.0.0.1\"\n" +
                "  },\n"+
                "    \"payload\": {\n" +
                "        \"inlinedJsonBody\": {\n" +
                "            \"mappings\": {\n" +
                "                \"properties\": {\n" +
                "                    \"field1\": {\n" +
                "                        \"type\": \"text\"\n" +
                "                    },\n" +
                "                    \"field2\": {\n" +
                "                        \"type\": \"keyword\"\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}";

        Assertions.assertEquals(normalize(mapper, TEST_OUTPUT_REQUEST), normalize(mapper, outputStr));
    }
}