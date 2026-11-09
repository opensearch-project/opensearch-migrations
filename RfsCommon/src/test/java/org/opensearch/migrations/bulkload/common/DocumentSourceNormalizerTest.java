package org.opensearch.migrations.bulkload.common;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.dataformat.smile.databind.SmileMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSourceNormalizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> sampleDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", "héllo 世界");
        doc.put("count", 42);
        doc.put("ratio", 2.5);
        doc.put("big", new BigInteger("9223372036854775808"));
        doc.put("flag", true);
        doc.put("missing", null);
        doc.put("nested", Map.of("tags", List.of("a", "b"), "n", List.of(1, 2, 3)));
        return doc;
    }

    private static JsonNode expected() {
        return JSON.valueToTree(sampleDocument());
    }

    // ---- detection ------------------------------------------------------------------------

    @Test
    void detect_recognizesEveryFormatWrittenByJackson() throws Exception {
        var doc = sampleDocument();
        assertEquals(DocumentSourceFormat.JSON, DocumentSourceFormat.detect(JSON.writeValueAsBytes(doc)));
        assertEquals(DocumentSourceFormat.SMILE, DocumentSourceFormat.detect(new SmileMapper().writeValueAsBytes(doc)));
        assertEquals(DocumentSourceFormat.CBOR, DocumentSourceFormat.detect(new CBORMapper().writeValueAsBytes(doc)));
        assertEquals(DocumentSourceFormat.YAML, DocumentSourceFormat.detect(new YAMLMapper().writeValueAsBytes(doc)));
    }

    @Test
    void detect_jsonWithBomOrLeadingWhitespace() {
        assertEquals(DocumentSourceFormat.JSON, DocumentSourceFormat.detect(" \t\r\n{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(DocumentSourceFormat.JSON,
            DocumentSourceFormat.detect(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}));
    }

    @Test
    void detect_cborInitialBytes() {
        // Definite-length maps (major type 5): 0xA0 is an empty map, 0xA1 a one-entry map.
        assertEquals(DocumentSourceFormat.CBOR, DocumentSourceFormat.detect(new byte[] {(byte) 0xA0}));
        assertEquals(DocumentSourceFormat.CBOR, DocumentSourceFormat.detect(new byte[] {(byte) 0xA1, 0x61, 'a', 0x01}));
        // Indefinite-length map, as Jackson writes by default.
        assertEquals(DocumentSourceFormat.CBOR, DocumentSourceFormat.detect(new byte[] {(byte) 0xBF, (byte) 0xFF}));
        // Self-describe tag 55799 before the map.
        assertEquals(DocumentSourceFormat.CBOR,
            DocumentSourceFormat.detect(new byte[] {(byte) 0xD9, (byte) 0xD9, (byte) 0xF7, (byte) 0xA0}));
        // An array (major type 4) is not a document.
        assertEquals(DocumentSourceFormat.UNKNOWN, DocumentSourceFormat.detect(new byte[] {(byte) 0x80}));
    }

    @Test
    void detect_unknownInputs() {
        assertEquals(DocumentSourceFormat.UNKNOWN, DocumentSourceFormat.detect(null));
        assertEquals(DocumentSourceFormat.UNKNOWN, DocumentSourceFormat.detect(new byte[0]));
        assertEquals(DocumentSourceFormat.UNKNOWN, DocumentSourceFormat.detect("title: no marker".getBytes(StandardCharsets.UTF_8)));
        assertEquals(DocumentSourceFormat.UNKNOWN, DocumentSourceFormat.detect("[1,2]".getBytes(StandardCharsets.UTF_8)));
        assertEquals(DocumentSourceFormat.UNKNOWN, DocumentSourceFormat.detect("   ".getBytes(StandardCharsets.UTF_8)));
        // Truncated headers
        assertEquals(DocumentSourceFormat.UNKNOWN, DocumentSourceFormat.detect(":)".getBytes(StandardCharsets.UTF_8)));
        assertEquals(DocumentSourceFormat.UNKNOWN, DocumentSourceFormat.detect("--".getBytes(StandardCharsets.UTF_8)));
    }

    // ---- conversion -----------------------------------------------------------------------

    @Test
    void toJson_jsonIsReturnedWithoutCopying() throws Exception {
        byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertSame(json, DocumentSourceNormalizer.toJson(json));
        byte[] padded = "  {\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertSame(padded, DocumentSourceNormalizer.toJson(padded));
        assertNull(DocumentSourceNormalizer.toJson(null));
        byte[] empty = new byte[0];
        assertSame(empty, DocumentSourceNormalizer.toJson(empty));
    }

    @Test
    void toJson_convertsSmile() throws Exception {
        byte[] smile = new SmileMapper().writeValueAsBytes(sampleDocument());
        assertEquals(expected(), JSON.readTree(DocumentSourceNormalizer.toJson(smile)));
    }

    @Test
    void toJson_convertsCbor() throws Exception {
        byte[] cbor = new CBORMapper().writeValueAsBytes(sampleDocument());
        assertEquals(expected(), JSON.readTree(DocumentSourceNormalizer.toJson(cbor)));
    }

    @Test
    void toJson_convertsCborWithSelfDescribeTagAndDefiniteLengths() throws Exception {
        var factory = CBORFactory.builder()
            .enable(CBORGenerator.Feature.WRITE_TYPE_HEADER)
            .enable(CBORGenerator.Feature.WRITE_MINIMAL_INTS)
            .build();
        // Writing from a tree gives definite-length containers.
        byte[] cbor = new CBORMapper(factory).writeValueAsBytes(expected());
        assertEquals((byte) 0xD9, cbor[0]);
        assertEquals(expected(), JSON.readTree(DocumentSourceNormalizer.toJson(cbor)));
    }

    @Test
    void toJson_convertsYaml() throws Exception {
        byte[] yaml = new YAMLMapper().writeValueAsBytes(sampleDocument());
        assertEquals(expected(), JSON.readTree(DocumentSourceNormalizer.toJson(yaml)));
    }

    @Test
    void toJson_preservesNumericPrecision() throws Exception {
        Map<String, Object> doc = Map.of(
            "long", Long.MAX_VALUE,
            "bigint", new BigInteger("123456789012345678901234567890"),
            "decimal", new BigDecimal("1.10000000000000000000000000001"));
        String json = new String(DocumentSourceNormalizer.toJson(new CBORMapper().writeValueAsBytes(doc)),
            StandardCharsets.UTF_8);
        assertTrue(json.contains("9223372036854775807"), json);
        assertTrue(json.contains("123456789012345678901234567890"), json);
        assertTrue(json.contains("1.10000000000000000000000000001"), json);
        json = new String(DocumentSourceNormalizer.toJson(new SmileMapper().writeValueAsBytes(doc)),
            StandardCharsets.UTF_8);
        assertTrue(json.contains("9223372036854775807"), json);
        assertTrue(json.contains("123456789012345678901234567890"), json);
        assertTrue(json.contains("1.10000000000000000000000000001"), json);
    }

    @Test
    void toJson_binaryValuesBecomeBase64() throws Exception {
        byte[] cbor = new CBORMapper().writeValueAsBytes(Map.of("blob", new byte[] {1, 2, 3}));
        assertEquals("{\"blob\":\"AQID\"}", new String(DocumentSourceNormalizer.toJson(cbor), StandardCharsets.UTF_8));
    }

    @Test
    void toJson_outputIsSingleLine() throws Exception {
        byte[] yaml = new YAMLMapper().writeValueAsBytes(sampleDocument());
        byte[] json = DocumentSourceNormalizer.toJson(yaml);
        for (byte b : json) {
            assertTrue(b != '\n' && b != '\r');
        }
    }

    @Test
    void toJson_unknownFormatFails() {
        var e = assertThrows(DocumentSourceNormalizer.UndecodableSourceException.class,
            () -> DocumentSourceNormalizer.toJson("not a document".getBytes(StandardCharsets.UTF_8)));
        assertEquals(DocumentSourceFormat.UNKNOWN, e.getFormat());
        assertTrue(e.getMessage().contains("6e 6f 74 20"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"smile", "cbor", "yaml"})
    void toJson_truncatedSourceFails(String format) throws Exception {
        byte[] full = switch (format) {
            case "smile" -> new SmileMapper().writeValueAsBytes(sampleDocument());
            case "cbor" -> new CBORMapper().writeValueAsBytes(sampleDocument());
            default -> "---\ntitle: \"unterminated\n".getBytes(StandardCharsets.UTF_8);
        };
        byte[] truncated = format.equals("yaml") ? full : java.util.Arrays.copyOf(full, full.length / 2);
        var e = assertThrows(DocumentSourceNormalizer.UndecodableSourceException.class,
            () -> DocumentSourceNormalizer.toJson(truncated));
        assertEquals(DocumentSourceFormat.valueOf(format.toUpperCase()), e.getFormat());
    }

    @Test
    void toJson_nonObjectRootFails() throws Exception {
        byte[] yamlScalar = "---\njust a string\n".getBytes(StandardCharsets.UTF_8);
        var e = assertThrows(DocumentSourceNormalizer.UndecodableSourceException.class,
            () -> DocumentSourceNormalizer.toJson(yamlScalar));
        assertEquals(DocumentSourceFormat.YAML, e.getFormat());
    }

    @Test
    void toJson_largeYamlIsNotLimitedBySnakeYamlDefault() throws Exception {
        String big = "x".repeat(4 * 1024 * 1024);
        byte[] yaml = new YAMLMapper().writeValueAsBytes(Map.of("big", big));
        assertArrayEquals(JSON.writeValueAsBytes(Map.of("big", big)), DocumentSourceNormalizer.toJson(yaml));
    }
}
