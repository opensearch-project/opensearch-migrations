package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeDocumentSplitBenchmarkTransformTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    @Test
    void benchmarkTransformUsesCreateAndBuildsOnlySelectedPayloads() throws Exception {
        String script;
        try (var input = getClass().getResourceAsStream("/benchmarks/largeDocumentSplitTransform.js")) {
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var providerConfig = Map.of(
            "initializationScript", script,
            "bindingsObject", Map.of(
                "sampleModulo", 2,
                "targetBytes", 2 * 1024 * 1024,
                "valueBytes", 1024,
                "containerCount", 16,
                "nestingDepth", 4,
                "profile", "unit-test",
                "targetIndex", "products-benchmark"
            )
        );
        String config = MAPPER.writeValueAsString(List.of(Map.of("JsonJSTransformerProvider", providerConfig)));
        var source = "{\"title\":\"source\"}".getBytes(StandardCharsets.UTF_8);
        var selected = BulkOperationConverter.fromRawDocument(
            new Document("selected-0", source, Document.Operation.UPSERT, Map.of(), Map.of()), "products");
        var unselected = BulkOperationConverter.fromRawDocument(
            new Document("selected-1", source, Document.Operation.UPSERT, Map.of(), Map.of()), "products");

        try (var transformer = new TransformationLoader().getTransformerFactoryLoader(config)) {
            @SuppressWarnings("unchecked")
            var transformed = (List<Map<String, Object>>) transformer.transformJson(
                List.of(selected.toTransformerMap(MAPPER), unselected.toTransformerMap(MAPPER)));
            var first = MAPPER.convertValue(transformed.get(0), IndexOp.class);
            var second = MAPPER.convertValue(transformed.get(1), IndexOp.class);
            String[] firstLines = BulkNdjson.toBulkNdjson(List.of(first), MAPPER).split("\n");
            String[] secondLines = BulkNdjson.toBulkNdjson(List.of(second), MAPPER).split("\n");
            String[] selectedLines = firstLines[1].length() > secondLines[1].length() ? firstLines : secondLines;
            String[] unselectedLines = firstLines[1].length() > secondLines[1].length() ? secondLines : firstLines;

            assertEquals("create", MAPPER.readTree(selectedLines[0]).path("index").path("op_type").asText());
            assertEquals("products-benchmark", MAPPER.readTree(selectedLines[0]).path("index").path("_index").asText());
            assertFalse(MAPPER.readTree(selectedLines[0]).path("index").has("version"));
            assertTrue(selectedLines[1].length() > 1_900_000, "calibrated payload should be close to 2 MiB");
            assertEquals("unit-test",
                MAPPER.readTree(selectedLines[1]).path("benchmark_payload").path("profile").asText());
            assertTrue(unselectedLines[1].length() < 1024, "unsampled documents should receive only the decoration");
            assertEquals(0,
                MAPPER.readTree(unselectedLines[1]).path("benchmark_payload").path("target_bytes").asInt());
        }
    }
}
