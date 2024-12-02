package org.opensearch.migrations.transform;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@Slf4j
class TypeMappingsSanitizationTransformerTest {
    private final static ObjectMapper objMapper = new ObjectMapper();

    private static TypeMappingsSanitizationTransformer indexTypeMappingRewriter;
    @BeforeAll
    static void initialize() throws IOException {
        var indexMappings = Map.of(
            "indexA", Map.of(
                "type1", "indexA_1",
                "type2", "indexA_2"),
            "indexB", Map.of(
                "type1", "indexB",
                "type2", "indexB"),
            "socialTypes", Map.of(
                "tweet", "communal",
                "user", "communal"));
        var regexIndexMappings = List.of(
            List.of("time-(.*)", "(.*)", "time-\\1-\\2"));
        indexTypeMappingRewriter = new TypeMappingsSanitizationTransformer(indexMappings, regexIndexMappings);
    }

    @Test
    public void testPutDoc() throws Exception {
        var testString =
            "{\n" +
                "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
                "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/indexA/type2/someuser\",\n" +
                "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
                "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\": {" +
                "      \"name\": \"Some User\",\n" +
                "      \"user_name\": \"user\",\n" +
                "      \"email\": \"user@example.com\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        var resultObj = indexTypeMappingRewriter.transformJson(objMapper.readValue(testString, LinkedHashMap.class));
        var resultStr = objMapper.writeValueAsString(resultObj);
        log.atInfo().setMessage("resultStr = {}").setMessage(resultStr).log();
    }

    @Test
    public void testPutDocRegex() throws Exception {
        var testString =
            "{\n" +
                "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
                "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/time-nov11/cpu/doc2\",\n" +
                "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
                "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\": {" +
                "      \"name\": \"Some User\",\n" +
                "      \"user_name\": \"user\",\n" +
                "      \"email\": \"user@example.com\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        var resultObj = indexTypeMappingRewriter.transformJson(objMapper.readValue(testString, LinkedHashMap.class));
        var resultStr = objMapper.writeValueAsString(resultObj);
        log.atInfo().setMessage("resultStr = {}").setMessage(resultStr).log();
    }

    private static String makeMultiTypePutIndexRequest(String indexName) {
        return "{\n" +
            "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
            "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/" + indexName + "\",\n" +
            "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
            "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\": " +
            "{\n" +
            "  \"settings\" : {\n" +
            "    \"number_of_shards\" : 1\n" +
            "  }," +
            "  \"mappings\": {\n" +
            "    \"user\": {\n" +
            "      \"properties\": {\n" +
            "        \"name\": { \"type\": \"text\" },\n" +
            "        \"user_name\": { \"type\": \"keyword\" },\n" +
            "        \"email\": { \"type\": \"keyword\" }\n" +
            "      }\n" +
            "    },\n" +
            "    \"tweet\": {\n" +
            "      \"properties\": {\n" +
            "        \"content\": { \"type\": \"text\" },\n" +
            "        \"user_name\": { \"type\": \"keyword\" },\n" +
            "        \"tweeted_at\": { \"type\": \"date\" }\n" +
            "      }\n" +
            "    },\n" +
            "    \"following\": {\n" +
            "      \"properties\": {\n" +
            "        \"count\": { \"type\": \"integer\" },\n" +
            "        \"followers\": { \"type\": \"string\" }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}" +
            "\n" +
            "  }\n" +
            "}";
    }

    Map<String, Object> doPutIndex(String indexName) throws Exception {
        var testString = makeMultiTypePutIndexRequest(indexName);
        return indexTypeMappingRewriter.transformJson(objMapper.readValue(testString, LinkedHashMap.class));
    }

    @Test
    public void testPutSingleTypeIndex() throws Exception {
        final String index = "indexA";
        var result = doPutIndex(index);
        Assertions.assertEquals(objMapper.readValue(makeMultiTypePutIndexRequest(index), LinkedHashMap.class),
            result);
    }

    @Test
    public void testMultiTypeIndex() throws Exception {
        final String index = "socialTypes";
        var result = doPutIndex(index);
        var expected = objMapper.readTree(makeMultiTypePutIndexRequest(index));
        var mappings = ((ObjectNode) expected.path(JsonKeysForHttpMessage.PAYLOAD_KEY)
            .path(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY)
            .path("mappings"));
        mappings.remove("following");
        var newProperties = new HashMap<String, Object>();
        newProperties.put("type", Map.of("type", "keyword"));
        var user = mappings.remove("user");
        user.path("properties").fields().forEachRemaining(e -> newProperties.put(e.getKey(), e.getValue()));
        var tweet = mappings.remove("tweet");
        tweet.path("properties").fields().forEachRemaining(e -> newProperties.put(e.getKey(), e.getValue()));
        var properties = mappings.put("properties", objMapper.valueToTree(newProperties));
        ((ObjectNode)expected).put(JsonKeysForHttpMessage.URI_KEY, "/communal");
        Assertions.assertEquals(expected, objMapper.readTree(objMapper.writeValueAsString(result)));
    }
}
