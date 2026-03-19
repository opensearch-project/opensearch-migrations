package org.opensearch.migrations.transform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.typemappings.SourceProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TypeMappingsSanitizationMetadataTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static TypeMappingsSanitizationTransformer makeIndexTypeMappingRewriter() throws Exception {
        var indexMappings = Map.of(
                "split", Map.of("type1", "split_a", "type2", "split_b"),
                "union", Map.of("type1", "union", "type2", "union"),
                "drop", Map.<String, String>of(),
                "keep", Map.of("_doc", "keep")
        );
        var sourceProperties = new SourceProperties("ES", new SourceProperties.Version(6, 8));
        var regexMappings = List.of(
                Map.of(
                        "sourceIndexPattern","time-(.*)",
                        "sourceTypePattern", "(.*)",
                        "targetIndexPattern", "time-$1-$2"
                )
        );
        return new TypeMappingsSanitizationTransformer(indexMappings, regexMappings, sourceProperties, null);
    }

    private static String makeMetadataRequest(String indexName, String type1, String type2) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"type\": \"org.opensearch.migrations.bulkload.version_es_6_8.IndexMetadataData_ES_6_8\",\n");
        json.append("  \"name\": \"").append(indexName).append("\",\n");
        json.append("  \"body\": {\n");
        json.append("    \"mappings\": {\n");
        json.append("      \"").append(type1).append("\": {\n");
        json.append("        \"properties\": {\n");
        json.append("          \"field1\": { \"type\": \"text\" }\n");
        json.append("        }\n");
        json.append("      }\n");
        if (type2 != null) {
            json.append("      ,\n");
            json.append("      \"").append(type2).append("\": {\n");
            json.append("        \"properties\": {\n");
            json.append("          \"field2\": { \"type\": \"keyword\" }\n");
            json.append("        }\n");
            json.append("      }\n");
        }
        json.append("    }\n");
        json.append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    @Test
    public void testSplitTypeMappings() throws Exception {
        String inputJson = makeMetadataRequest("split", "type1", "type2");
        String expectedJson = "[" +
                "{\"type\":\"org.opensearch.migrations.bulkload.version_es_6_8.IndexMetadataData_ES_6_8\",\"name\":\"split_a\",\"body\":{\"mappings\":{\"_doc\":{\"properties\":{\"field1\":{\"type\":\"text\"}}}}}}," +
                "{\"type\":\"org.opensearch.migrations.bulkload.version_es_6_8.IndexMetadataData_ES_6_8\",\"name\":\"split_b\",\"body\":{\"mappings\":{\"_doc\":{\"properties\":{\"field2\":{\"type\":\"keyword\"}}}}}}" +
                "]";
        try (var transformer = makeIndexTypeMappingRewriter()) {
            Object result = transformer.transformJson(OBJECT_MAPPER.readValue(inputJson, LinkedHashMap.class));
            String resultJson = OBJECT_MAPPER.writeValueAsString(result);
            Assertions.assertEquals(expectedJson, resultJson, "Split transformation did not match expected output");
        }
    }

    @Test
    public void testUnionTypeMappings() throws Exception {
        String inputJson = makeMetadataRequest("union", "type1", "type2");
        String expectedJson = "[" +
                "{\"type\":\"org.opensearch.migrations.bulkload.version_es_6_8.IndexMetadataData_ES_6_8\",\"name\":\"union\",\"body\":{\"mappings\":{\"_doc\":{\"properties\":{\"field1\":{\"type\":\"text\"},\"field2\":{\"type\":\"keyword\"}}}}}}" +
                "]";
        try (var transformer = makeIndexTypeMappingRewriter()) {
            Object result = transformer.transformJson(OBJECT_MAPPER.readValue(inputJson, LinkedHashMap.class));
            String resultJson = OBJECT_MAPPER.writeValueAsString(result);
            Assertions.assertEquals(expectedJson, resultJson, "Union transformation did not match expected output");
        }
    }

    @Test
    public void testDropTypeMappings() throws Exception {
        String inputJson = makeMetadataRequest("drop", "type1", "type2");
        String expectedJson = "[]";
        try (var transformer = makeIndexTypeMappingRewriter()) {
            Object result = transformer.transformJson(OBJECT_MAPPER.readValue(inputJson, LinkedHashMap.class));
            String resultJson = OBJECT_MAPPER.writeValueAsString(result);
            Assertions.assertEquals(expectedJson, resultJson, "Drop transformation did not match expected output");
        }
    }

    @Test
    public void testKeepTypeMappings() throws Exception {
        String inputJson = makeMetadataRequest("keep", "_doc", null);
        String expectedJson = "[" +
                "{\"type\":\"org.opensearch.migrations.bulkload.version_es_6_8.IndexMetadataData_ES_6_8\",\"name\":\"keep\",\"body\":{\"mappings\":{\"_doc\":{\"properties\":{\"field1\":{\"type\":\"text\"}}}}}}" +
                "]";
        try (var transformer = makeIndexTypeMappingRewriter()) {
            Object result = transformer.transformJson(OBJECT_MAPPER.readValue(inputJson, LinkedHashMap.class));
            String resultJson = OBJECT_MAPPER.writeValueAsString(result);
            Assertions.assertEquals(expectedJson, resultJson, "Keep transformation did not match expected output");
        }
    }
}
