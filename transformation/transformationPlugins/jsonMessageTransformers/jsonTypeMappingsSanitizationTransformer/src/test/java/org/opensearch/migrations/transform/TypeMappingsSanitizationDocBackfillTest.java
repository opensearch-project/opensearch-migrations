package org.opensearch.migrations.transform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.JsonNormalizer;
import org.opensearch.migrations.transform.typemappings.SourceProperties;

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
            "  \"schema\": \"rfs-opensearch-bulk-v1\",\n" +
            "  \"operation_type\": \"index\",\n" +
            "  \"operation\": { \"_index\": \"performance\", \"_type\": \"network\", \"_id\": \"1\" },\n" +
            "  \"document\": { \"field1\": \"value1\" },\n" +
            "  \"include_document\": true\n" +
            "}";

        var expectedString = "{\n" +
            "  \"schema\": \"rfs-opensearch-bulk-v1\",\n" +
            "  \"operation_type\": \"index\",\n" +
            "  \"operation\": { \"_index\": \"performance_network\", \"_id\": \"1\" },\n" +
            "  \"document\": { \"field1\": \"value1\" },\n" +
            "  \"include_document\": true\n" +
            "}";

        try (var indexTypeMappingRewriter = new TypeMappingsSanitizationTransformer(null, null,
            new SourceProperties("ES", new SourceProperties.Version(7, 10)), null)) {
            var docObj = OBJECT_MAPPER.readValue(testString, LinkedHashMap.class);
            var resultObj = indexTypeMappingRewriter.transformJson(docObj);
            printObject(resultObj);
            Assertions.assertEquals(JsonNormalizer.fromString(expectedString), JsonNormalizer.fromObject(resultObj));
        }
    }

    @Test
    public void testWithTypeMapping() throws Exception {
        var testString = "{\n" +
            "  \"schema\": \"rfs-opensearch-bulk-v1\",\n" +
            "  \"operation_type\": \"index\",\n" +
            "  \"operation\": { \"_index\": \"test_e2e_0001_1234\", \"_type\": \"doc\", \"_id\": \"1\" },\n" +
            "  \"document\": { \"field1\": \"value1\" },\n" +
            "  \"include_document\": true\n" +
            "}";

        var expectedString = "{\n" +
            "  \"schema\": \"rfs-opensearch-bulk-v1\",\n" +
            "  \"operation_type\": \"index\",\n" +
            "  \"operation\": { \"_index\": \"test_e2e_0001_1234_transformed\", \"_id\": \"1\" },\n" +
            "  \"document\": { \"field1\": \"value1\" },\n" +
            "  \"include_document\": true\n" +
            "}";

        try (var indexTypeMappingRewriter = new TypeMappingsSanitizationTransformer(null,
            List.of(
                Map.of(
                    "sourceIndexPattern","(test_e2e_0001_.*)",
                    "sourceTypePattern", ".*",
                    "targetIndexPattern", "$1_transformed"
                ),
                Map.of(
                    "sourceIndexPattern","(.*)",
                    "sourceTypePattern", "(.*)",
                    "targetIndexPattern", "$1"
                )
            ),
            new SourceProperties("ES", new SourceProperties.Version(6, 8)), null)) {
            var docObj = OBJECT_MAPPER.readValue(testString, LinkedHashMap.class);
            var resultObj = indexTypeMappingRewriter.transformJson(docObj);
            printObject(resultObj);
            Assertions.assertEquals(JsonNormalizer.fromString(expectedString), JsonNormalizer.fromObject(resultObj));
        }
    }

    @Test
    public void testWithoutTypeMapping_hasCorrectDefault() throws Exception {
        var testString = "{\n" +
            "  \"schema\": \"rfs-opensearch-bulk-v1\",\n" +
            "  \"operation_type\": \"index\",\n" +
            "  \"operation\": { \"_index\": \"test_e2e_0001_1234\", \"_id\": \"1\" },\n" +
            "  \"document\": { \"field1\": \"value1\" },\n" +
            "  \"include_document\": true\n" +
            "}";

        var expectedString = "{\n" +
            "  \"schema\": \"rfs-opensearch-bulk-v1\",\n" +
            "  \"operation_type\": \"index\",\n" +
            "  \"operation\": { \"_index\": \"test_e2e_0001_1234_transformed\", \"_id\": \"1\" },\n" +
            "  \"document\": { \"field1\": \"value1\" },\n" +
            "  \"include_document\": true\n" +
            "}";

        try (var indexTypeMappingRewriter = new TypeMappingsSanitizationTransformer(null,
            List.of(
                Map.of(
                    "sourceIndexPattern","(test_e2e_0001_.*)",
                    "sourceTypePattern", ".*",
                    "targetIndexPattern", "$1_transformed"
                ),
                Map.of(
                    "sourceIndexPattern","(.*)",
                    "sourceTypePattern", "(.*)",
                    "targetIndexPattern", "$1"
                )
            ),
            new SourceProperties("ES", new SourceProperties.Version(6, 8)), null)) {
            var docObj = OBJECT_MAPPER.readValue(testString, LinkedHashMap.class);
            var resultObj = indexTypeMappingRewriter.transformJson(docObj);
            printObject(resultObj);
            Assertions.assertEquals(JsonNormalizer.fromString(expectedString), JsonNormalizer.fromObject(resultObj));
        }
    }

    @Test
    public void testAsBatch() throws Exception {
        var testDocString = "{\n" +
            "  \"schema\": \"rfs-opensearch-bulk-v1\",\n" +
            "  \"operation_type\": \"index\",\n" +
            "  \"operation\": { \"_index\": \"test_e2e_0001_1234\", \"_id\": \"1\" },\n" +
            "  \"document\": { \"field1\": \"value1\" },\n" +
            "  \"include_document\": true\n" +
            "}";
        var testString = "[" +
            (testDocString + ",").repeat(5)
            + testDocString + "]";

        var expectedString =
            testString.replaceAll("test_e2e_0001_1234", "test_e2e_0001_1234_transformed");

        try (var indexTypeMappingRewriter = new TypeMappingsSanitizationTransformer(null,
            List.of(
                Map.of(
                    "sourceIndexPattern","(test_e2e_0001_.*)",
                    "sourceTypePattern", ".*",
                    "targetIndexPattern", "$1_transformed"
                ),
                Map.of(
                    "sourceIndexPattern","(.*)",
                    "sourceTypePattern", "(.*)",
                    "targetIndexPattern", "$1"
                )
            ),
            new SourceProperties("ES", new SourceProperties.Version(6, 8)), null)) {
            var docObj = OBJECT_MAPPER.readValue(testString, List.class);
            var resultObj = indexTypeMappingRewriter.transformJson(docObj);
            printObject(resultObj);
            Assertions.assertEquals(JsonNormalizer.fromString(expectedString), JsonNormalizer.fromObject(resultObj));
        }
    }



    private static void printObject(Object object) {
        log.atInfo().setMessage("resultStr = {}").addArgument(() -> {
            try {
                return OBJECT_MAPPER.writeValueAsString(object);
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }).log();
    }
}
