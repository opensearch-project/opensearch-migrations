package org.opensearch.migrations.transform;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.JsonNormalizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Slf4j
class TypeMappingsSanitizationTransformerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        var resultObj = indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
        var resultStr = OBJECT_MAPPER.writeValueAsString(resultObj);
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
        var expectedString = "{\n" +
            "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\":\"PUT\",\n" +
            "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/time-1-2/_doc/doc2\",\n" +
            "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\":{" +
            "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\":{" +
            "      \"name\":\"Some User\"," +
            "      \"user_name\":\"user\"," +
            "      \"email\":\"user@example.com\"" +
            "    }" +
            "  }" +
            "}";
        var resultObj = indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
        var resultStr = OBJECT_MAPPER.writeValueAsString(resultObj);
        log.atInfo().setMessage("resultStr = {}").setMessage(resultStr).log();
        Assertions.assertEquals(JsonNormalizer.fromString(expectedString), JsonNormalizer.fromObject(resultObj));
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
        return indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
    }

    @Test
    public void testPutSingleTypeIndex() throws Exception {
        final String index = "indexA";
        var result = doPutIndex(index);
        Assertions.assertEquals(JsonNormalizer.fromString(makeMultiTypePutIndexRequest(index)),
            JsonNormalizer.fromObject(result));
    }

    @Test
    public void testMultiTypeIndex() throws Exception {
        final String index = "socialTypes";
        var result = doPutIndex(index);
        var expected = OBJECT_MAPPER.readTree(makeMultiTypePutIndexRequest(index));
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
        mappings.set("properties", OBJECT_MAPPER.valueToTree(newProperties));
        ((ObjectNode)expected).put(JsonKeysForHttpMessage.URI_KEY, "/communal");
        Assertions.assertEquals(JsonNormalizer.fromObject(expected), JsonNormalizer.fromObject(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "_cat/indices", "_cat/indices/nov-*"} )
    public void testDefaultActionPreservesRequest(String uri) throws Exception {
        final String bespokeRequest = "" +
            "{\n" +
            "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"GET\",\n" +
            "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/" + uri + "\"" +
            "}";
        var transformedResult = indexTypeMappingRewriter.transformJson(
            OBJECT_MAPPER.readValue(bespokeRequest, new TypeReference<>(){}));
        Assertions.assertEquals(JsonNormalizer.fromString(bespokeRequest),
            JsonNormalizer.fromObject(transformedResult));
    }
}
