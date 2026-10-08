package org.opensearch.migrations.bulkload.common.bulk;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.bulk.enums.VersionType;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BulkOperationConverterTest {

    @Test
    void fromDocument_withVersionHint_setsExternalGteVersioning() {
        var doc = new Document("d1", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT,
            Map.of(Document.HINT_VERSION, "7"), Map.of());

        var op = (IndexOp) BulkOperationConverter.fromDocument(doc, "idx");

        assertEquals(7L, op.getOperation().getVersioning().getVersion());
        assertEquals(VersionType.EXTERNAL_GTE, op.getOperation().getVersioning().getVersionType());
    }

    @Test
    void fromDocument_withoutVersionHint_leavesVersioningUnset() {
        var doc = new Document("d1", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT,
            Map.of(), Map.of());

        var op = (IndexOp) BulkOperationConverter.fromDocument(doc, "idx");

        assertNull(op.getOperation().getVersioning());
    }
}
