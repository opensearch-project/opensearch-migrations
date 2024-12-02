package org.opensearch.migrations.transform;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

@Slf4j
public class TypeMappingsSanitizationTransformerBulkTest {

    private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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
        var regexIndexMappings = List.of(
            List.of("time-(.*)", "(.*)", "time-$1-$2"));
        indexTypeMappingRewriter = new TypeMappingsSanitizationTransformer(indexMappings, regexIndexMappings);
    }

    @Test
    public void testBulk() throws Exception {
        var testString =
            "{\n" +
                "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
                "  \"" + JsonKeysForHttpMessage.PROTOCOL_KEY + "\": \"HTTP/1.1\",\n" +
                "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/_bulk\",\n" +
                "  \"" + JsonKeysForHttpMessage.HEADERS_KEY + "\": {},\n" +
                "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
                "    \"" + JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY + "\": [\n" +
//                "{ \"index\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"1\" } },\n" +
//                "{ \"field1\" : \"value1\" },\n" +
                "{ \"delete\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"2\" } },\n" +
                "{ \"delete\" : { \"_index\" : \"time-January_1970\", \"_type\" : \"cpu\", \"_id\" : \"8\" } }\n" +
//                "{ \"create\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"3\" } },\n" +
//                "{ \"field1\" : \"value3\" },\n" +
//                "{ \"update\" : {\"_id\" : \"1\", \"_type\" : \"type1\", \"_index\" : \"test\"} },\n" +
//                "{ \"doc\" : {\"field2\" : \"value2\"} }\n" +
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
//                "{ \"index\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"1\" } },\n" +
//                "{ \"field1\" : \"value1\" },\n" +
                "{ \"delete\" : { \"_index\" : \"time-January_1970-cpu\", \"_id\" : \"8\" } }\n" +
//                "{ \"create\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"3\" } },\n" +
//                "{ \"field1\" : \"value3\" },\n" +
//                "{ \"update\" : {\"_id\" : \"1\", \"_type\" : \"type1\", \"_index\" : \"test\"} },\n" +
//                "{ \"doc\" : {\"field2\" : \"value2\"} }\n" +
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
        Assertions.assertEquals(normalize(OBJECT_MAPPER.readValue(expectedString, LinkedHashMap.class)), normalize(resultObj));
    }

    static String normalize(Object obj) throws Exception {
        return new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .writeValueAsString(obj);
    }
}
