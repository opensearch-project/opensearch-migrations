package org.opensearch.migrations.transform;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.JsonNormalizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@Slf4j
public class TypeMappingsSanitizationTransformerBulkTest {

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
            "indexc", Map.of(
                "type2", "indexc"));
        var regexIndexMappings = List.of(
            List.of("time-(.*)", "(.*)", "time-\\1-\\2"));
        indexTypeMappingRewriter = new TypeMappingsSanitizationTransformer(indexMappings, regexIndexMappings);
    }

    @Test
    public void testBulkRequest() throws Exception {
        var testString =
            "{\n" +
                "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
                "  \"" + JsonKeysForHttpMessage.PROTOCOL_KEY + "\": \"HTTP/1.1\",\n" +
                "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/_bulk\",\n" +
                "  \"" + JsonKeysForHttpMessage.HEADERS_KEY + "\": {},\n" +
                "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
                "    \"" + JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY + "\": [\n" +
                "{ \"index\": { \"_index\": \"indexa\", \"_type\": \"type1\", \"_id\": \"1\" } },\n" +
                "{ \"field1\": \"value1\" },\n" +

                "{ \"index\": { \"_index\": \"indexa\", \"_type\": \"typeDontMap\", \"_id\": \"1\" } },\n" +
                "{ \"field1\": \"value9\" },\n" +

                "{ \"delete\": { \"_index\": \"test\", \"_type\": \"type1\", \"_id\": \"2\" } },\n" +

                "{ \"delete\": { \"_index\": \"time-January_1970\", \"_type\": \"cpu\", \"_id\": \"8\" } },\n" +

                "{ \"create\": { \"_index\": \"indexc\", \"_type\": \"type1\", \"_id\": \"3\" } },\n" +
                "{ \"field1\": \"value3\" },\n" +

                "{ \"create\": { \"_index\": \"indexc\", \"_type\": \"type2\", \"_id\": \"14\" } },\n" +
                "{ \"field14\": \"value14\" },\n" +

                "{ \"update\": {\"_id\": \"1\", \"_type\": \"type1\", \"_index\": \"indexb\"} },\n" +
                "{ \"doc\": {\"field2\": \"value2\"} },\n" +

                "{ \"update\": {\"_id\": \"1\", \"_type\": \"type2\", \"_index\": \"indexb\"} },\n" +
                "{ \"doc\": {\"field10\": \"value10\"} },\n" +

                "{ \"update\": {\"_id\": \"1\", \"_type\": \"type3\", \"_index\": \"indexb\"} },\n" +
                "{ \"doc\": {\"field11\": \"value11\"} },\n" +

                "{ \"delete\": {\"_id\": \"12\", \"_index\": \"index_without_typemappings\"} },\n" +

                "{ \"update\": {\"_id\": \"13\", \"_index\": \"index_without_typemappings\"} },\n" +
                "{ \"doc\": {\"field13\": \"value11\"} }\n" +

                "    ]\n" +
                "  }\n" +
                "}";

        var expectedString =
            "{\n" +
                "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
                "  \"" + JsonKeysForHttpMessage.PROTOCOL_KEY + "\": \"HTTP/1.1\",\n" +
                "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/_bulk\",\n" +
                "  \"" + JsonKeysForHttpMessage.HEADERS_KEY + "\": {},\n" +
                "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
                "    \"" + JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY + "\": [\n" +
                "{ \"index\": { \"_index\": \"indexa_1\", \"_id\": \"1\" } },\n" +
                "{ \"field1\": \"value1\" },\n" +

                "{ \"delete\": { \"_index\": \"time-January_1970-cpu\", \"_id\": \"8\" } },\n" +

                "{ \"create\": { \"_index\": \"indexc\", \"_id\": \"14\" } },\n" +
                "{ \"field14\": \"value14\" },\n" +

                "{ \"update\": {\"_id\": \"1\", \"_index\": \"indexb\"} },\n" +
                "{ \"doc\": {\"field2\": \"value2\"} },\n" +

                "{ \"update\": {\"_id\": \"1\", \"_index\": \"indexb\"} },\n" +
                "{ \"doc\": {\"field10\": \"value10\"} },\n" +

                "{ \"delete\": {\"_id\": \"12\", \"_index\": \"index_without_typemappings\"} },\n" +

                "{ \"update\": {\"_id\": \"13\", \"_index\": \"index_without_typemappings\"} },\n" +
                "{ \"doc\": {\"field13\": \"value11\"} }\n" +

                "    ]\n" +
                "  }\n" +
                "}";


        var resultObj = indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
        log.atInfo().setMessage("resultStr = {}").addArgument(() -> {
            try {
                return OBJECT_MAPPER.writeValueAsString(resultObj);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).log();
        Assertions.assertEquals(JsonNormalizer.fromString(expectedString), JsonNormalizer.fromObject(resultObj));
    }

}
