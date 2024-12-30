package org.opensearch.migrations.transform;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.JsonNormalizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            "indexa", Map.of(
                "type1", "indexa_1",
                "type2", "indexa_2"),
            "indexb", Map.of(
                "type1", "indexb",
                "type2", "indexb"),
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
                "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/indexa/type2/someuser\",\n" +
                "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
                "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\": {" +
                "      \"name\": \"Some User\",\n" +
                "      \"user_name\": \"user\",\n" +
                "      \"email\": \"user@example.com\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        var resultObj = indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
        Assertions.assertEquals(JsonNormalizer.fromString(testString.replace("indexa/type2/", "indexa_2/_doc/")),
            JsonNormalizer.fromObject(resultObj));
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
            "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/time-nov11-cpu/_doc/doc2\",\n" +
            "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\":{" +
            "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\":{" +
            "      \"name\":\"Some User\"," +
            "      \"user_name\":\"user\"," +
            "      \"email\":\"user@example.com\"" +
            "    }" +
            "  }" +
            "}";
        var resultObj = indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
        log.atInfo().setMessage("resultStr = {}").setMessage(OBJECT_MAPPER.writeValueAsString(resultObj)).log();
        Assertions.assertEquals(JsonNormalizer.fromString(expectedString),
            JsonNormalizer.fromObject(resultObj));
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
