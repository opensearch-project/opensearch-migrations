package org.opensearch.migrations.transform;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

class TypeMappingsSanitizationTransformerTest {

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
            "indexC", Map.of(
                "type2", "indexC"));
            //"time-(*)", Map.of("(*)", "time-\\1-\\2"));
        indexTypeMappingRewriter = new TypeMappingsSanitizationTransformer(indexMappings);
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
        var objMapper = new ObjectMapper();
        var resultObj = indexTypeMappingRewriter.transformJson(objMapper.readValue(testString, LinkedHashMap.class));
        var resultStr = objMapper.writeValueAsString(resultObj);
        System.out.println("resultStr = " + resultStr);
    }

    @Test
    public void testPutIndex() throws Exception {
        var testString =
            "{\n" +
                "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
                "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/indexA\",\n" +
                "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
                "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\": " +
                "{\n" +
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
                "    }\n" +
                "  }\n" +
                "}" +
                "\n" +
                "  }\n" +
                "}";
        var objMapper = new ObjectMapper();
        var resultObj = indexTypeMappingRewriter.transformJson(objMapper.readValue(testString, LinkedHashMap.class));
        var resultStr = objMapper.writeValueAsString(resultObj);
        System.out.println("resultStr = " + resultStr);
    }
}
