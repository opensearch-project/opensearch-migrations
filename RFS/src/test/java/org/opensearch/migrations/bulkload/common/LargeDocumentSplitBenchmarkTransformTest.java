package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeDocumentSplitBenchmarkTransformTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final byte[] SOURCE = "{\"title\":\"source\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void benchmarkTransformUsesCreateAndBuildsOnlySelectedPayloads() throws Exception {
        var selected = BulkOperationConverter.fromRawDocument(
            new Document("selected-0", SOURCE, Document.Operation.UPSERT, Map.of(), Map.of()), "products");
        var unselected = BulkOperationConverter.fromRawDocument(
            new Document("selected-1", SOURCE, Document.Operation.UPSERT, Map.of(), Map.of()), "products");

        try (var transformer = transformer(Map.of("sampleModulo", 2))) {
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

    @Test
    void exactIdSelectionBuildsTenConsecutivePayloadsAndNoOthers() throws Exception {
        var selectedIds = IntStream.range(0, 10)
            .mapToObj(i -> "document-" + String.format("%03d", i))
            .toList();
        var operations = new ArrayList<IndexOp>();
        IntStream.range(0, 12).forEach(i -> operations.add((IndexOp) BulkOperationConverter.fromRawDocument(
            new Document("document-" + String.format("%03d", i), SOURCE,
                Document.Operation.UPSERT, Map.of(), Map.of()),
            "products"
        )));

        try (var transformer = transformer(Map.of(
            "sampleModulo", 1,
            "selectedIds", selectedIds,
            "targetBytes", 64 * 1024
        ))) {
            @SuppressWarnings("unchecked")
            var transformed = (List<Map<String, Object>>) transformer.transformJson(
                operations.stream().map(op -> op.toTransformerMap(MAPPER)).toList());

            assertEquals(12, transformed.size());
            for (int i = 0; i < transformed.size(); i++) {
                var operation = MAPPER.convertValue(transformed.get(i), IndexOp.class);
                var lines = BulkNdjson.toBulkNdjson(List.of(operation), MAPPER).split("\n");
                int payloadBytes = lines[1].length();
                if (i < 10) {
                    assertTrue(payloadBytes > 55_000, "selected document " + i + " should be expanded");
                } else {
                    assertTrue(payloadBytes < 1024, "unselected document " + i + " should only be decorated");
                }
            }
        }
    }

    @Test
    void alreadyExpandedPayloadPassesThroughWithoutBeingRebuilt() throws Exception {
        var source = """
            {"title":"source","benchmark_payload":{"profile":"first-wave","target_bytes":408211400,
            "sentinel":{"preserve":"this"}}}
            """.getBytes(StandardCharsets.UTF_8);
        var operation = BulkOperationConverter.fromDocument(
            new Document("document-000", source, Document.Operation.UPSERT, Map.of(), Map.of()),
            "products"
        );

        try (var transformer = transformer(Map.of(
            "sampleModulo", 1,
            "selectedIds", List.of("document-000"),
            "targetBytes", 2 * 1024 * 1024
        ))) {
            @SuppressWarnings("unchecked")
            var transformed = (List<Map<String, Object>>) transformer.transformJson(
                List.of(operation.toTransformerMap(MAPPER)));
            var transformedOperation = MAPPER.convertValue(transformed.get(0), IndexOp.class);
            var payload = MAPPER.valueToTree(transformedOperation.getDocument()).path("benchmark_payload");

            assertEquals("first-wave", payload.path("profile").asText());
            assertEquals(408_211_400, payload.path("target_bytes").asInt());
            assertEquals("this", payload.path("sentinel").path("preserve").asText());
        }
    }

    private IJsonTransformer transformer(Map<String, Object> overrides) throws Exception {
        String script;
        try (var input = getClass().getResourceAsStream("/benchmarks/largeDocumentSplitTransform.js")) {
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var bindings = new LinkedHashMap<String, Object>();
        bindings.put("sampleModulo", 2);
        bindings.put("targetBytes", 2 * 1024 * 1024);
        bindings.put("valueBytes", 1024);
        bindings.put("containerCount", 16);
        bindings.put("nestingDepth", 4);
        bindings.put("profile", "unit-test");
        bindings.put("targetIndex", "products-benchmark");
        bindings.putAll(overrides);
        var providerConfig = Map.of(
            "initializationScript", script,
            "bindingsObject", bindings
        );
        String config = MAPPER.writeValueAsString(List.of(Map.of("JsonJSTransformerProvider", providerConfig)));
        return new TransformationLoader().getTransformerFactoryLoader(config);
    }
}
