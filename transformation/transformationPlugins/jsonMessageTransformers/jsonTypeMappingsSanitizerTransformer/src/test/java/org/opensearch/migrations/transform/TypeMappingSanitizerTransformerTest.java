package org.opensearch.migrations.transform;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

class TypeMappingSanitizerTransformerTest {


    private static TypeMappingSanitizerTransformer indexTypeMappingRewriter;
    @BeforeAll
    static void initialize() {
        var indexMappings = Map.of(
            "indexA", Map.of(
                "type1", "indexA_1",
                "type2", "indexA_2"),
            "indexB", Map.of(
                "type1", "indexB",
                "type2", "indexB"),
            "indexC", Map.of(
                "type2", "indexC"));
        indexTypeMappingRewriter = new TypeMappingSanitizerTransformer(indexMappings);
    }

    @Test
    public void test() throws Exception {
        var testString =
        "{\n" +
            "  \"verb\": \"PUT\",\n" +
            "  \"uri\": \"indexA/type2/someuser\",\n" +
            "  \"body\": {\n" +
            "    \"name\": \"Some User\",\n" +
            "    \"user_name\": \"user\",\n" +
            "    \"email\": \"user@example.com\"\n" +
            "  }\n" +
            "}";
        var objMapper = new ObjectMapper();
        var resultObj = indexTypeMappingRewriter.transformJson(objMapper.readValue(testString, Map.class));
        var resultStr = objMapper.writeValueAsString(resultObj);
        System.out.println("resultStr = " + resultStr);
    }
}