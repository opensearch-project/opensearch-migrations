package org.opensearch.migrations.transform;

import java.util.LinkedHashMap;

import org.opensearch.migrations.testutils.JsonNormalizer;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
public class TypeMappingsSanitizationDocBackfillTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void test() throws Exception {
        var testString = "{\n" +
                "  \"index\": { \"_index\": \"performance\", \"_type\": \"network\", \"_id\": \"1\" },\n" +
                "  \"source\": { \"field1\": \"value1\" }\n" +
                "}";

        var expectedString = "{\n" +
            "  \"index\": { \"_index\": \"performance_network\", \"_id\": \"1\" },\n" +
            "  \"source\": { \"field1\": \"value1\" }\n" +
            "}";


        var indexTypeMappingRewriter = new TypeMappingsSanitizationTransformer(null, null);
        var resultObj = indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
        log.atInfo().setMessage("resultStr = {}").addArgument(() -> {
            try {
                return OBJECT_MAPPER.writeValueAsString(resultObj);
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }).log();
        Assertions.assertEquals(JsonNormalizer.fromString(expectedString), JsonNormalizer.fromObject(resultObj));
    }
}
