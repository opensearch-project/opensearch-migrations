package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.SourceFormat.InvalidDocumentSourceException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.fasterxml.jackson.dataformat.smile.SmileGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceFormatTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> DOCUMENT = new LinkedHashMap<>();
    static {
        DOCUMENT.put("title", "line one\nline two");
        DOCUMENT.put("count", 42);
        DOCUMENT.put("big", 9007199254740993L);
        DOCUMENT.put("ratio", 1.5);
        DOCUMENT.put("tags", List.of("a", "b"));
        DOCUMENT.put("nested", Map.of("flag", true));
        DOCUMENT.put("nothing", null);
    }

    private static byte[] write(JsonFactory factory, Object value) throws Exception {
        return new ObjectMapper(factory).writeValueAsBytes(value);
    }

    @Test
    void jsonFastPathReturnsSameArray() {
        byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertSame(json, SourceFormat.toJson(json));
        assertEquals(SourceFormat.JSON, SourceFormat.detect(json));
    }

    @Test
    void jsonWithLeadingWhitespaceIsUnchanged() {
        byte[] json = " \n\t{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertEquals(SourceFormat.JSON, SourceFormat.detect(json));
        assertSame(json, SourceFormat.toJson(json));
    }

    @Test
    void jsonByteOrderMarkIsStripped() {
        byte[] json = "\uFEFF{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertEquals(SourceFormat.JSON, SourceFormat.detect(json));
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), SourceFormat.toJson(json));
    }

    @Test
    void smileIsConvertedToJson() throws Exception {
        byte[] smile = write(new SmileFactory(), DOCUMENT);
        assertEquals(SourceFormat.SMILE, SourceFormat.detect(smile));
        assertEquals(JSON.valueToTree(DOCUMENT), JSON.readTree(SourceFormat.toJson(smile)));
    }

    @Test
    void smileWithRawBinaryAndSharedNamesIsConvertedLikeGeoipDatabases() throws Exception {
        // ES writes .geoip_databases with header flags 0x05: shared property names and raw binary.
        var factory = SmileFactory.builder()
            .enable(SmileGenerator.Feature.CHECK_SHARED_NAMES)
            .disable(SmileGenerator.Feature.ENCODE_BINARY_AS_7BIT)
            .build();
        byte[] chunk = new byte[256];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) i; // includes '\n' and every other control byte
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", "GeoLite2-ASN.mmdb");
        doc.put("chunk", 0);
        doc.put("data", chunk);
        byte[] smile = write(factory, doc);
        assertEquals(0x05, smile[3] & 0x07, "header flags");

        byte[] json = SourceFormat.toJson(smile);
        JsonNode tree = JSON.readTree(json);
        assertEquals("GeoLite2-ASN.mmdb", tree.path("name").asText());
        assertArrayEquals(chunk, Base64.getDecoder().decode(tree.path("data").asText()));
        for (byte b : json) {
            assertEquals(false, b == '\n' || b == '\r', "converted JSON must be a single line");
        }
    }

    @Test
    void cborIsConvertedToJson() throws Exception {
        byte[] cbor = write(new CBORFactory(), DOCUMENT);
        assertEquals(SourceFormat.CBOR, SourceFormat.detect(cbor));
        assertEquals(JSON.valueToTree(DOCUMENT), JSON.readTree(SourceFormat.toJson(cbor)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a1616101", "d9d9f7a1616101"}) // definite-length map; self-describe tag
    void cborVariantsAreDetected(String hex) {
        byte[] cbor = java.util.HexFormat.of().parseHex(hex);
        assertEquals(SourceFormat.CBOR, SourceFormat.detect(cbor));
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), SourceFormat.toJson(cbor));
    }

    @Test
    void yamlIsConvertedToJson() throws Exception {
        byte[] yaml = write(new YAMLFactory(), DOCUMENT);
        assertEquals(SourceFormat.YAML, SourceFormat.detect(yaml));
        assertEquals(JSON.valueToTree(DOCUMENT), JSON.readTree(SourceFormat.toJson(yaml)));
    }

    @Test
    void yamlLargerThanSnakeYamlDefaultLimitIsConverted() throws Exception {
        String big = "x".repeat(4 * 1024 * 1024);
        byte[] yaml = write(new YAMLFactory(), Map.of("big", big));
        assertEquals(big, JSON.readTree(SourceFormat.toJson(yaml)).path("big").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a: 1\n", "[1,2]", "x", "ZV\u0000"})
    void unknownFormatsAreRejected(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        assertEquals(SourceFormat.UNKNOWN, SourceFormat.detect(bytes));
        assertThrows(InvalidDocumentSourceException.class, () -> SourceFormat.toJson(bytes));
    }

    @Test
    void corruptSmileIsRejected() throws Exception {
        byte[] smile = write(new SmileFactory(), DOCUMENT);
        byte[] truncated = java.util.Arrays.copyOf(smile, smile.length / 2);
        assertThrows(InvalidDocumentSourceException.class, () -> SourceFormat.toJson(truncated));
    }
}
