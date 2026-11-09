package org.opensearch.migrations.bulkload.common.bulk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentSourceNormalizer;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.dataformat.smile.databind.SmileMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMILE and CBOR sources, as stored by clusters that were sent those content types, reach the bulk
 * encoder only after the source reader converts them; the encoder itself accepts only JSON.
 */
class BulkNdjsonSourceFormatTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    private static Map<String, Object> sampleDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", "héllo\nworld");
        doc.put("count", 9007199254740993L);
        doc.put("ratio", 0.1);
        doc.put("nested", Map.of("tags", List.of("a", "b")));
        return doc;
    }

    private static byte[] encode(String format) throws IOException {
        return switch (format) {
            case "smile" -> new SmileMapper().writeValueAsBytes(sampleDocument());
            case "cbor" -> new CBORMapper().writeValueAsBytes(sampleDocument());
            default -> throw new IllegalArgumentException(format);
        };
    }

    private static Document upsert(String id, byte[] source) {
        return new Document(id, source, Document.Operation.UPSERT, Map.of(), Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"smile", "cbor"})
    void convertedSourcesEncodeAsSingleLineBulkBodies(String format) throws Exception {
        var docs = List.of(
            upsert("a", DocumentSourceNormalizer.toJson(encode(format))),
            upsert("b", DocumentSourceNormalizer.toJson(encode(format))));

        String ndjson = new String(BulkNdjson.toRawNdjsonBytes(docs, "idx", false, MAPPER), StandardCharsets.UTF_8);

        var lines = ndjson.split("\n");
        assertEquals(4, lines.length, ndjson);
        JsonNode expected = MAPPER.valueToTree(sampleDocument());
        assertEquals("a", MAPPER.readTree(lines[0]).path("index").path("_id").asText());
        assertEquals(expected, MAPPER.readTree(lines[1]));
        assertEquals("b", MAPPER.readTree(lines[2]).path("index").path("_id").asText());
        assertEquals(expected, MAPPER.readTree(lines[3]));
        assertTrue(lines[1].contains("9007199254740993"), lines[1]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"smile", "cbor"})
    void convertedSourcesParseForTransformations(String format) throws Exception {
        var doc = upsert("a", DocumentSourceNormalizer.toJson(encode(format)));
        var op = BulkOperationConverter.fromDocument(doc, "idx");
        assertEquals(MAPPER.convertValue(sampleDocument(), Map.class), op.getDocument());
        var raw = BulkOperationConverter.fromRawDocument(doc, "idx");
        assertEquals(MAPPER.convertValue(sampleDocument(), Map.class), raw.getOriginalSource());
    }

    @ParameterizedTest
    @ValueSource(strings = {"smile", "cbor"})
    void unconvertedSourcesAreRejectedWithTheirFormat(String format) throws Exception {
        var doc = upsert("a", encode(format));
        var e = assertThrows(IOException.class, () -> BulkNdjson.encodeRaw(doc, "idx", false, MAPPER));
        assertTrue(e.getMessage().contains("detected format: " + format.toUpperCase()), e.getMessage());
    }
}
