package org.opensearch.migrations.bulkload.common.bulk;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SourceFormat.InvalidDocumentSourceException;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BulkNdjsonSourceFormatTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final Map<String, Object> SOURCE = Map.of("title", "two\nlines", "count", 7, "tags", List.of("x"));

    private static Document doc(String id, byte[] source) {
        return new Document(id, source, Document.Operation.UPSERT, Map.of(), Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"smile", "cbor"})
    void binarySourcesAreSentAsSingleLineJson(String format) throws Exception {
        JsonFactory factory = format.equals("smile") ? new SmileFactory() : new CBORFactory();
        byte[] source = new ObjectMapper(factory).writeValueAsBytes(SOURCE);

        String ndjson = new String(BulkNdjson.toRawNdjsonBytes(List.of(doc("1", source)), "idx", false, MAPPER),
            StandardCharsets.UTF_8);

        var lines = ndjson.split("\n");
        assertEquals(2, lines.length, ndjson);
        assertEquals("1", MAPPER.readTree(lines[0]).path("index").path("_id").asText());
        assertEquals(MAPPER.valueToTree(SOURCE), MAPPER.readTree(lines[1]));
    }

    @Test
    void jsonSourceBodyIsTheOriginalArray() {
        byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        var encoded = BulkNdjson.encodeRaw(doc("1", json), "idx", false, MAPPER);
        assertSame(json, encoded.body());
    }

    @Test
    void unknownSourceFailsWithPerDocumentException() {
        byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);
        assertThrows(InvalidDocumentSourceException.class,
            () -> BulkNdjson.encodeRaw(doc("1", notJson), "idx", false, MAPPER));
    }

    @Test
    void malformedJsonFailsWithPerDocumentException() {
        byte[] malformed = "{\"a\":".getBytes(StandardCharsets.UTF_8);
        assertThrows(InvalidDocumentSourceException.class,
            () -> BulkNdjson.encodeRaw(doc("1", malformed), "idx", false, MAPPER));
    }
}
