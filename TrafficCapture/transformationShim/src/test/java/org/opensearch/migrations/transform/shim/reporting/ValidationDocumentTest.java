package org.opensearch.migrations.transform.shim.reporting;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.ComparisonEntry;
import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.RequestRecord;
import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.ResponseRecord;
import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.ValueDrift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ValidationDocument fullDocument() {
        return new ValidationDocument(
            "2025-03-17T10:00:00Z", "abc-123",
            new RequestRecord("GET", "/solr/mycore/select?q=*:*", Map.of("Host", "solr:8983"), null),
            new RequestRecord("GET", "/mycore/_search?q=*:*", Map.of("Host", "os:9200"), null),
            "mycore", "/solr/{collection}/select",
            100L, 95L, 5.0,
            12L, 15L, 3L,
            List.of(new ComparisonEntry("facet_field", "category", true, null, null,
                List.of(new ValueDrift("books", 50, 48, 4.0)))),
            new ResponseRecord(200, null, null),
            new ResponseRecord(200, null, "{\"hits\":{\"total\":95}}"),
            Map.of("warn-offset", 1)
        );
    }

    @Test
    void fullDocumentRoundTrip() throws Exception {
        ValidationDocument doc = fullDocument();
        String json = MAPPER.writeValueAsString(doc);
        ValidationDocument deserialized = MAPPER.readValue(json, ValidationDocument.class);
        assertEquals(doc, deserialized);
    }

    @Test
    void snakeCaseFieldNames() throws Exception {
        String json = MAPPER.writeValueAsString(fullDocument());
        JsonNode tree = MAPPER.readTree(json);
        assertTrue(tree.has("request_id"));
        assertTrue(tree.has("original_request"));
        assertTrue(tree.has("transformed_request"));
        assertTrue(tree.has("collection_name"));
        assertTrue(tree.has("normalized_endpoint"));
        assertTrue(tree.has("baseline_hit_count"));
        assertTrue(tree.has("candidate_hit_count"));
        assertTrue(tree.has("hit_count_drift_percentage"));
        assertTrue(tree.has("baseline_response_time_ms"));
        assertTrue(tree.has("candidate_response_time_ms"));
        assertTrue(tree.has("response_time_delta_ms"));
        assertTrue(tree.has("custom_metrics"));
        // Nested RequestRecord
        assertTrue(tree.get("original_request").has("method"));
        assertTrue(tree.get("original_request").has("uri"));
        assertTrue(tree.get("original_request").has("headers"));
        // Nested ComparisonEntry
        JsonNode comp = tree.get("comparisons").get(0);
        assertTrue(comp.has("keys_match"));
        assertTrue(comp.has("value_drifts"));
        // Nested ValueDrift
        JsonNode drift = comp.get("value_drifts").get(0);
        assertTrue(drift.has("baseline_value"));
        assertTrue(drift.has("candidate_value"));
        assertTrue(drift.has("drift_percentage"));
        // Nested ResponseRecord
        assertTrue(tree.has("baseline_response"));
        assertTrue(tree.has("candidate_response"));
        assertTrue(tree.get("baseline_response").has("status_code"));
        assertTrue(tree.get("candidate_response").has("status_code"));
        assertTrue(tree.get("candidate_response").has("body"));
    }

    @Test
    void nullFieldsOmitted() throws Exception {
        ValidationDocument doc = new ValidationDocument(
            "2025-03-17T10:00:00Z", "abc-123",
            null, null, null, null,
            null, null, null, null, null, null, null,
            null, null,
            null
        );
        String json = MAPPER.writeValueAsString(doc);
        JsonNode tree = MAPPER.readTree(json);
        assertFalse(tree.has("original_request"));
        assertFalse(tree.has("comparisons"));
        assertFalse(tree.has("baseline_hit_count"));
        assertFalse(tree.has("custom_metrics"));
        // But timestamp and request_id are present
        assertTrue(tree.has("timestamp"));
        assertTrue(tree.has("request_id"));
    }

    @Test
    void nestedRecordsSerializeCorrectly() throws Exception {
        var req = new RequestRecord("POST", "/solr/core1/update", Map.of("Content-Type", "application/json"), "body");
        String json = MAPPER.writeValueAsString(req);
        JsonNode tree = MAPPER.readTree(json);
        assertEquals("POST", tree.get("method").asText());
        assertEquals("/solr/core1/update", tree.get("uri").asText());
        assertEquals("body", tree.get("body").asText());
    }

    @Test
    void responseRecordSerializesCorrectly() throws Exception {
        var resp = new ResponseRecord(200, null, "{\"hits\":{\"total\":10}}");
        String json = MAPPER.writeValueAsString(resp);
        JsonNode tree = MAPPER.readTree(json);
        assertEquals(200, tree.get("status_code").asInt());
        assertTrue(tree.get("error").isNull());
        assertEquals("{\"hits\":{\"total\":10}}", tree.get("body").asText());
    }

    @Test
    void responseRecordWithErrorSerializesCorrectly() throws Exception {
        var resp = new ResponseRecord(-1, "connection refused", null);
        String json = MAPPER.writeValueAsString(resp);
        JsonNode tree = MAPPER.readTree(json);
        assertEquals(-1, tree.get("status_code").asInt());
        assertEquals("connection refused", tree.get("error").asText());
        assertTrue(tree.get("body").isNull());
    }
}
