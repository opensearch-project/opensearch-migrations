package org.opensearch.migrations.transform;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.JsonNormalizer;
import org.opensearch.migrations.transform.typemappings.SourceProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TypeMappingsSanitizationCreateIndexTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static TypeMappingsSanitizationTransformer makeIndexTypeMappingRewriter() throws Exception {
        var indexMappings = Map.of(
            "indexa", Map.of(
                "type1", "indexa_1",
                "type2", "indexa_2",
                "user", "a_user"),
            "indexb", Map.of(
                "type1", "indexb",
                "type2", "indexb"),
            "socialTypes", Map.of(
                "tweet", "communal",
                "user", "communal"));
        var regexMappings = List.of(
                Map.of(
                        "sourceIndexPattern","time-(.*)",
                        "sourceTypePattern", "(.*)",
                        "targetIndexPattern", "time-$1-$2"
                )
        );
        var sourceProperties = new SourceProperties("ES", new SourceProperties.Version(5, 8));
        return new TypeMappingsSanitizationTransformer(indexMappings, regexMappings, sourceProperties, null);
    }

    private static String makeMultiTypePutIndexRequest(String indexName, Boolean includeTypeName) {
        return TestRequestBuilder.makePutIndexRequest(indexName, true, includeTypeName);
    }

    @Test
    public void testPutSingleTypeToMissingTarget() throws Exception {
        final String index = "indexb"; // has multiple indices for its types
        var testString = TestRequestBuilder.makePutIndexRequest(index, false, true);
        try (var indexTypeMappingRewriter = makeIndexTypeMappingRewriter()) {
            var result = (Map<String, Object>)
                indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
            var expected = "{ \"method\": \"GET\", \"URI\": \"/\", \"protocol\" : \"HTTP/1.0\" }";
            Assertions.assertEquals(JsonNormalizer.fromString(expected),
                JsonNormalizer.fromObject(result));
        }
    }

    @Test
    public void testPutSingleTypeIndex() throws Exception {
        final String index = "indexa"; // has multiple indices for its types
        var testString = TestRequestBuilder.makePutIndexRequest(index, false, true);
        try (var indexTypeMappingRewriter = makeIndexTypeMappingRewriter()) {
            var result = (Map<String, Object>)
                indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
            var expectedString = "{\n" +
                "  \"URI\" : \"/a_user\",\n" +
                "  \"method\" : \"PUT\",\n" +
                "  \"protocol\" : \"HTTP/1.1\",\n" +
                "  \"payload\" : {\n" +
                "    \"inlinedJsonBody\" : {\n" +
                "      \"mappings\" : {\n" +
                "        \"properties\" : {\n" +
                "          \"email\" : {\n" +
                "            \"type\" : \"keyword\"\n" +
                "          },\n" +
                "          \"name\" : {\n" +
                "            \"type\" : \"text\"\n" +
                "          },\n" +
                "          \"user_name\" : {\n" +
                "            \"type\" : \"keyword\"\n" +
                "          }\n" +
                "        }\n" +
                "      },\n" +
                "      \"settings\" : {\n" +
                "        \"number_of_shards\" : 1\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
            Assertions.assertEquals(JsonNormalizer.fromString(expectedString),
                JsonNormalizer.fromObject(result));
        }
    }

    @Test
    public void testMultiTypeIndexMerged() throws Exception {
        final String index = "socialTypes";
        var testString = makeMultiTypePutIndexRequest(index, true);
        try (var indexTypeMappingRewriter = makeIndexTypeMappingRewriter()) {
            var result = (Map<String, Object>)
                indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
            var expected = OBJECT_MAPPER.readTree(makeMultiTypePutIndexRequest(index, null));
            var mappings = ((ObjectNode) expected.path(JsonKeysForHttpMessage.PAYLOAD_KEY)
                .path(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY)
                .path("mappings"));
            mappings.remove("following");
            var newProperties = new HashMap<String, Object>();
            var user = mappings.remove("user");
            user.path("properties").properties().forEach(e -> newProperties.put(e.getKey(), e.getValue()));
            var tweet = mappings.remove("tweet");
            tweet.path("properties").properties().forEach(e -> newProperties.put(e.getKey(), e.getValue()));
            mappings.set("properties", OBJECT_MAPPER.valueToTree(newProperties));
            ((ObjectNode) expected).put(JsonKeysForHttpMessage.URI_KEY, "/communal");
            Assertions.assertEquals(JsonNormalizer.fromObject(expected), JsonNormalizer.fromObject(result));
        }
    }

    @Test
    public void testCreateIndexWithoutTypeButWithMappings() throws Exception{
        var uri = TestRequestBuilder.formatCreateIndexUri("geonames", false);;
        var testString = "{\n" +
            "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
            "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"" + uri + "\",\n" +
            "  \"" + JsonKeysForHttpMessage.PROTOCOL_KEY + "\": \"HTTP/1.1\"," +
            "  \"" + JsonKeysForHttpMessage.HEADERS_KEY + "\": {\n" +
            "    \"Host\": \"capture-proxy:9200\"\n" +
            "  }," +
            "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
            "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\": {\n" +
            "      \"settings\": {\n" +
            "        \"index\": {\n" +
            "          \"number_of_shards\": 3,  \n" +
            "          \"number_of_replicas\": 2 \n" +
            "        }\n" +
            "      }," +
            "      \"mappings\": {" +
            "        \"properties\": {\n" +
            "          \"field1\": { \"type\": \"text\" }\n" +
            "        }" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
        try (var indexTypeMappingRewriter = makeIndexTypeMappingRewriter()) {
            Object resultObj = indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
            Assertions.assertEquals(JsonNormalizer.fromString(testString), JsonNormalizer.fromObject(resultObj));
        }
    }

    @Test
    public void testCreateIndexWithoutType() throws Exception{
        var testString = "{\n" +
            "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
            "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/geonames\",\n" +
            "  \"" + JsonKeysForHttpMessage.PROTOCOL_KEY + "\": \"HTTP/1.1\"," +
            "  \"" + JsonKeysForHttpMessage.HEADERS_KEY + "\": {\n" +
            "    \"Host\": \"capture-proxy:9200\"\n" +
            "  }," +
            "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
            "    \"settings\": {\n" +
            "      \"index\": {\n" +
            "        \"number_of_shards\": 3,  \n" +
            "        \"number_of_replicas\": 2 \n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
        try (var indexTypeMappingRewriter = makeIndexTypeMappingRewriter()) {
            Object resultObj = indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
            Assertions.assertEquals(JsonNormalizer.fromString(testString), JsonNormalizer.fromObject(resultObj));
        }
    }
}
