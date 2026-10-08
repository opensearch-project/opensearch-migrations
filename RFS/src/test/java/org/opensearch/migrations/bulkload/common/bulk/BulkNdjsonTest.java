package org.opensearch.migrations.bulkload.common.bulk;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkNdjsonTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    @Test
    void rawNdjson_withVersionHint_emitsExternalGteVersioning() {
        var doc = new Document("d1", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT,
            Map.of(Document.HINT_VERSION, "42"), Map.of());

        var action = actionLine(BulkNdjson.toRawNdjsonBytes(List.of(doc), "idx", false, MAPPER)).path("index");

        assertEquals(42L, action.path("version").asLong());
        assertEquals("external_gte", action.path("version_type").asText());
    }

    @Test
    void rawNdjson_withoutVersionHint_omitsVersioning() {
        var doc = new Document("d1", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT,
            Map.of(), Map.of());

        var action = actionLine(BulkNdjson.toRawNdjsonBytes(List.of(doc), "idx", false, MAPPER)).path("index");

        assertFalse(action.has("version"));
        assertFalse(action.has("version_type"));
    }

    @Test
    void rawNdjson_withStrippedIds_dropsVersioningWithTheId() {
        var doc = new Document("d1", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT,
            Map.of(Document.HINT_VERSION, "42"), Map.of());

        var action = actionLine(BulkNdjson.toRawNdjsonBytes(List.of(doc), "idx", true, MAPPER)).path("index");

        assertFalse(action.has("_id"));
        assertFalse(action.has("version"));
        assertFalse(action.has("version_type"));
    }

    @Test
    void rawNdjson_deleteIgnoresVersionHint() {
        var doc = new Document("d1", null, Document.Operation.DELETE, Map.of(Document.HINT_VERSION, "42"), Map.of());

        var action = actionLine(BulkNdjson.toRawNdjsonBytes(List.of(doc), "idx", false, MAPPER));

        assertTrue(action.has("delete"));
        assertFalse(action.path("delete").has("version"));
    }

    @SneakyThrows
    private static JsonNode actionLine(byte[] ndjson) {
        var firstLine = new String(ndjson, StandardCharsets.UTF_8).split("\n", 2)[0];
        return MAPPER.readTree(firstLine);
    }
}
