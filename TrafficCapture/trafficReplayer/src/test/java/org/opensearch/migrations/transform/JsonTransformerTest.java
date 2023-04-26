package org.opensearch.migrations.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class JsonTransformerTest {

    private static final String SPEC_JSON_KEYNAME = "spec";
    private static final String MORE_COMPLICATED_SHIFT_SPEC = "" +
            "{" +
            "\"spec\": [\n" +
            "        {\n" +
            "            \"operation\": \"shift\",\n" +
            "            \"spec\": {\n" +
            "                \"entities\": {\n" +
            "                    // The \"*\" matches each Map pair of \"type\" and \"data\".\n" +
            "                    // We then write the Map pair, to the value of the \"type\" key, in a forced array\n" +
            "                    \"*\" : \"@type[]\"\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "    ]" +
            "}";

    private static ObjectNode parseOrderedJson(String jsonStr) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
        return (ObjectNode) mapper.readTree(jsonStr);//, new TypeReference<LinkedHashMap<String, Object>>(){});
    }

    @SneakyThrows
    private static List parseSpecList(String wrappedJsonSpec) {
        var jsonObject = parseOrderedJson(wrappedJsonSpec);
        return ((ArrayNode) jsonObject.get(SPEC_JSON_KEYNAME)).;
    }

    private static String emitOrderedJson(Object transformedDocument) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true); //optional
        return mapper.writeValueAsString(transformedDocument);
    }

    @Test
    public void testSimpleTransform() throws JsonProcessingException {
        final String SIMPLE_SPEC_ARRAY = "{\"spec\":\"\"}";
        final String TEST_DOCUMENT = "{}";
        var moreComplexSpec = parseSpecList(MORE_COMPLICATED_SHIFT_SPEC);
        var documentJson = parseOrderedJson(TEST_DOCUMENT);
        var spec = parseSpecList(SIMPLE_SPEC_ARRAY);
        var transformer = new JsonTransformer(spec);
        var transformedDocument = transformer.transformJson(documentJson);
        var finalOutputStr = emitOrderedJson(transformedDocument);
        log.error("final document: "+finalOutputStr);

        Assertions.assertEquals(TEST_DOCUMENT, finalOutputStr);
    }

}