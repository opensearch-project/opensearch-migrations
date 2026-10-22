package org.opensearch.migrations.bulkload.common.bulk;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BulkNdjsonTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"delete\":{\"_index\":\"target\",\"_id\":\"target-only\"}}",
        "not a JSON body"
    })
    void deletesNeverSerializeRetainedSourceBodies(String oldSource) throws Exception {
        var mapper = ObjectMapperFactory.createDefaultMapper();
        var documents = List.of(
            new Document("deleted", oldSource.getBytes(StandardCharsets.UTF_8), Document.Operation.DELETE,
                Map.of(Document.HINT_ROUTING, "route"), Map.of()),
            new Document("replacement", "{\"value\":\"after\"}".getBytes(StandardCharsets.UTF_8),
                Document.Operation.UPSERT, Map.of(), Map.of())
        );

        var lines = new String(BulkNdjson.toRawNdjsonBytes(documents, "target", false, mapper),
            StandardCharsets.UTF_8).lines().toList();

        assertEquals(3, lines.size(), "Delete has no body; the following index has one body");
        assertEquals("deleted", mapper.readTree(lines.get(0)).path("delete").path("_id").asText());
        assertEquals("route", mapper.readTree(lines.get(0)).path("delete").path("routing").asText());
        assertEquals("replacement", mapper.readTree(lines.get(1)).path("index").path("_id").asText());
        assertEquals("after", mapper.readTree(lines.get(2)).path("value").asText());
    }
}
