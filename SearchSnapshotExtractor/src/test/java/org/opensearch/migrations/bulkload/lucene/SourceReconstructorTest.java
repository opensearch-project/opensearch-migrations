package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit tests for {@link SourceReconstructor} covering every supported
 * field type across every reconstruction pathway the class supports.
 *
 * The reconstructor has four pathways, each corresponding to different Lucene /
 * Elasticsearch eras:
 *
 *   1. Stored fields (all versions) — {@link #reconstructFromStoredFields}
 *      Number / byte[] / String leaf values harvested from {@code _source}-less
 *      segments that still have stored fields for specific columns.
 *
 *   2. doc_values (ES 2.x+ for most types; ES 1.x opt-in) — {@link #reconstructFromDocValues}
 *      NUMERIC / SORTED / SORTED_SET / SORTED_NUMERIC / BINARY columnar storage.
 *
 *   3. Points / BKD (Lucene 6+, i.e. ES 5+ / OS 1+) — {@link #reconstructFromPoints}
 *      Packed byte[] payloads from BKD trees, used when neither stored fields
 *      nor doc_values exist for a numeric/ip/date field.
 *
 *   4. Numeric terms (Lucene 4/5, i.e. ES 1.x / ES 2.x) — {@link #reconstructFromNumericTerms}
 *      Trie-encoded prefix-coded long/int terms in the inverted index. Decoded via
 *      {@code NumericUtils.prefixCodedToLong} upstream, then reinterpreted here by
 *      mapping type (IP / DATE / NUMERIC bit-reinterpretation for float/double).
 *
 * Each pathway × type combination is either exercised directly as an individual
 * {@code @Test} or included in the {@link #allTypesMatrix} parameterized sweep.
 */
class SourceReconstructorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- Helpers to construct mocks/records without repeating boilerplate ----------

    private static FieldMappingInfo mapping(EsFieldType type, String mappingType) {
        return new FieldMappingInfo(type, mappingType, null, null, true, null);
    }

    private static FieldMappingInfo mapping(EsFieldType type, String mappingType, String format) {
        return new FieldMappingInfo(type, mappingType, format, null, true, null);
    }

    private static FieldMappingInfo mappingScaled(double scalingFactor) {
        return new FieldMappingInfo(EsFieldType.SCALED_FLOAT, "scaled_float", null, scalingFactor, true, null);
    }

    private static FieldMappingContext contextOf(String fieldName, FieldMappingInfo info) {
        var ctx = new FieldMappingContext(null);
        // FieldMappingContext has no public setter; register via reflection on the private map.
        try {
            var f = FieldMappingContext.class.getDeclaredField("fieldMappings");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var m = (java.util.Map<String, FieldMappingInfo>) f.get(ctx);
            m.put(fieldName, info);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return ctx;
    }

    private static LuceneField storedString(String name, String value) {
        var f = mock(LuceneField.class);
        when(f.name()).thenReturn(name);
        when(f.stringValue()).thenReturn(value);
        return f;
    }

    private static LuceneField storedNumber(String name, Number n) {
        var f = mock(LuceneField.class);
        when(f.name()).thenReturn(name);
        when(f.numericValue()).thenReturn(n);
        return f;
    }

    private static LuceneField storedBinary(String name, byte[] bytes) {
        var f = mock(LuceneField.class);
        when(f.name()).thenReturn(name);
        when(f.binaryValue()).thenReturn(bytes);
        return f;
    }

    private static LuceneDocument document(LuceneField... fields) {
        var doc = mock(LuceneDocument.class);
        List<? extends LuceneField> list = List.of(fields);
        org.mockito.Mockito.doReturn(list).when(doc).getFields();
        return doc;
    }

    /** Reader with no doc-values, no points, and no numeric terms. Only stored fields drive output. */
    private static LuceneLeafReader storedOnlyReader() {
        var reader = mock(LuceneLeafReader.class);
        when(reader.getDocValueFields()).thenReturn(Collections.emptyList());
        try {
            // Default behaviour: no fallback path returns anything.
            when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return reader;
    }

    /** Packs a long into Lucene's 8-byte sortable BKD format (sign-bit-flipped big-endian). */
    private static byte[] packLong(long value) {
        long raw = value ^ 0x8000000000000000L;
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (raw & 0xFF);
            raw >>>= 8;
        }
        return b;
    }

    /** Packs an int into Lucene's 4-byte sortable BKD format (sign-bit-flipped big-endian). */
    private static byte[] packInt(int value) {
        int raw = value ^ 0x80000000;
        byte[] b = new byte[4];
        for (int i = 3; i >= 0; i--) {
            b[i] = (byte) (raw & 0xFF);
            raw >>>= 8;
        }
        return b;
    }

    /** Packs a float into Lucene's 4-byte sortable BKD format (mirrors Float.floatToSortableInt). */
    private static byte[] packFloat(float value) {
        int bits = Float.floatToIntBits(value);
        // floatToSortableInt: bits ^ ((bits >> 31) & 0x7fffffff)
        int sortable = bits ^ ((bits >> 31) & 0x7fffffff);
        return packInt(sortable);
    }

    /** Packs a double into Lucene's 8-byte sortable BKD format (mirrors Double.doubleToSortableLong). */
    private static byte[] packDouble(double value) {
        long bits = Double.doubleToLongBits(value);
        long sortable = bits ^ ((bits >> 63) & 0x7fffffffffffffffL);
        return packLong(sortable);
    }

    /** Builds the 16-byte IPv4-mapped IPv6 representation Lucene uses for IP points/docValues. */
    private static byte[] ipv4MappedIpv6(int a, int b, int c, int d) {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return bytes;
    }

    /** Convenience helper: parse SourceReconstructor JSON output for a single field. */
    private static JsonNode parseField(String json, String field) {
        try {
            return MAPPER.readTree(json).get(field);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ==========================================================================================
    // 1. STORED FIELDS PATH
    // ==========================================================================================

    @Test
    void reconstructFromStoredFields_booleanTF() {
        var reader = storedOnlyReader();
        var doc = document(storedString("enabled", "T"), storedString("disabled", "F"));
        var ctx = new FieldMappingContext(null);
        // boolean stored as T/F does not require a mapping — heuristic handles it.
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals(true, parseField(json, "enabled").asBoolean());
        assertEquals(false, parseField(json, "disabled").asBoolean());
    }

    @Test
    void reconstructFromStoredFields_numericPassthroughLong() {
        var reader = storedOnlyReader();
        var doc = document(storedNumber("count", 42L));
        var ctx = contextOf("count", mapping(EsFieldType.NUMERIC, "long"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals(42L, parseField(json, "count").asLong());
    }

    @Test
    void reconstructFromStoredFields_dateLongToIso() {
        var reader = storedOnlyReader();
        // 1_700_000_000_000 ms = 2023-11-14T22:13:20Z
        var doc = document(storedNumber("ts", 1_700_000_000_000L));
        var ctx = contextOf("ts", mapping(EsFieldType.DATE, "date"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals("2023-11-14T22:13:20Z", parseField(json, "ts").asText());
    }

    @Test
    void reconstructFromStoredFields_dateEpochMillisFormat() {
        var reader = storedOnlyReader();
        var doc = document(storedNumber("ts", 1_700_000_000_000L));
        var ctx = contextOf("ts", mapping(EsFieldType.DATE, "date", "epoch_millis"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals(1_700_000_000_000L, parseField(json, "ts").asLong());
    }

    @Test
    void reconstructFromStoredFields_dateNanosToIso() {
        var reader = storedOnlyReader();
        // 1_700_000_000_000_000_500 ns = 2023-11-14T22:13:20.000000500Z
        long nanos = 1_700_000_000_000_000_500L;
        var doc = document(storedNumber("ts", nanos));
        var ctx = contextOf("ts", mapping(EsFieldType.DATE_NANOS, "date_nanos"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals("2023-11-14T22:13:20.000000500Z", parseField(json, "ts").asText());
    }

    @Test
    void reconstructFromStoredFields_scaledFloat() {
        var reader = storedOnlyReader();
        // stored raw 12345 with scaling_factor 100 => 123.45
        var doc = document(storedNumber("price", 12345L));
        var ctx = contextOf("price", mappingScaled(100.0));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals(123.45, parseField(json, "price").asDouble(), 1e-9);
    }

    @Test
    void reconstructFromStoredFields_ipLongEs2x() {
        var reader = storedOnlyReader();
        // 192.168.1.100 as 32-bit int
        long ipLong = (192L << 24) | (168L << 16) | (1L << 8) | 100L;
        var doc = document(storedNumber("source_ip", ipLong));
        var ctx = contextOf("source_ip", mapping(EsFieldType.IP, "ip"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals("192.168.1.100", parseField(json, "source_ip").asText());
    }

    @Test
    void reconstructFromStoredFields_ipBinaryIpv4MappedIpv6Es5Plus() {
        var reader = storedOnlyReader();
        var doc = document(storedBinary("source_ip", ipv4MappedIpv6(10, 0, 0, 1)));
        var ctx = contextOf("source_ip", mapping(EsFieldType.IP, "ip"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals("10.0.0.1", parseField(json, "source_ip").asText());
    }

    @Test
    void reconstructFromStoredFields_ipStringNumericEs2x() {
        var reader = storedOnlyReader();
        long ipLong = (172L << 24) | (16L << 16) | (0L << 8) | 5L;
        var doc = document(storedString("source_ip", String.valueOf(ipLong)));
        var ctx = contextOf("source_ip", mapping(EsFieldType.IP, "ip"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals("172.16.0.5", parseField(json, "source_ip").asText());
    }

    @Test
    void reconstructFromStoredFields_stringUtf8Binary() {
        var reader = storedOnlyReader();
        var doc = document(storedBinary("name", "héllo".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var ctx = contextOf("name", mapping(EsFieldType.STRING, "keyword"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals("héllo", parseField(json, "name").asText());
    }

    @Test
    void reconstructFromStoredFields_binaryBase64() {
        var reader = storedOnlyReader();
        byte[] payload = {0x00, 0x10, (byte) 0xFF, 0x7F};
        var doc = document(storedBinary("blob", payload));
        var ctx = contextOf("blob", mapping(EsFieldType.BINARY, "binary"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertEquals(Base64.getEncoder().encodeToString(payload), parseField(json, "blob").asText());
    }

    @Test
    void reconstructFromStoredFields_geoPointIsDroppedForDocValueRecovery() {
        // geo_point stored fields are intentionally skipped to defer to doc_values.
        var reader = storedOnlyReader();
        var doc = document(storedBinary("loc", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        var ctx = contextOf("loc", mapping(EsFieldType.GEO_POINT, "geo_point"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        // No stored output should appear (null return means empty reconstruction).
        assertNull(json, "geo_point stored-field path should yield null to defer to doc_values");
    }

    // ==========================================================================================
    // 2. DOC_VALUES PATH
    // ==========================================================================================

    /** Builds a reader whose sole recovery channel is a single doc-value field. */
    private static LuceneLeafReader docValueReader(String name, DocValueFieldInfo.DocValueType type,
                                                    boolean isBoolean, Object value) throws IOException {
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple(name, type, isBoolean);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn(value);
        when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.empty());
        return reader;
    }

    @Test
    void reconstructFromDocValues_booleanNumericZeroOne() throws IOException {
        var reader = docValueReader("enabled", DocValueFieldInfo.DocValueType.NUMERIC, true, 1L);
        var ctx = contextOf("enabled", mapping(EsFieldType.BOOLEAN, "boolean"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(true, parseField(json, "enabled").asBoolean());
    }

    /** Regression: multi-valued boolean doc_values (SORTED_NUMERIC) must coerce each Long
     *  element to true/false. Previously only scalars were converted, leaving arrays as
     *  [1, 0] which OpenSearch rejects with a mapper_parsing_exception. */
    @Test
    void reconstructFromDocValues_booleanArrayCoercesEachElement() throws IOException {
        var reader = docValueReader("flags", DocValueFieldInfo.DocValueType.SORTED_NUMERIC, true,
                List.of(1L, 0L));
        var ctx = contextOf("flags", mapping(EsFieldType.BOOLEAN, "boolean"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        JsonNode arr = parseField(json, "flags");
        assertTrue(arr.isArray(), "expected array for multi-valued boolean field");
        assertEquals(2, arr.size());
        assertEquals(true, arr.get(0).asBoolean());
        assertEquals(false, arr.get(1).asBoolean());
        // And importantly: elements must be JSON booleans, not ints.
        assertTrue(arr.get(0).isBoolean() && arr.get(1).isBoolean(),
                "array elements must be JSON booleans, not numeric — got: " + arr);
    }

    @Test
    void reconstructFromDocValues_numericLongPassthrough() throws IOException {
        var reader = docValueReader("count", DocValueFieldInfo.DocValueType.NUMERIC, false, 999L);
        var ctx = contextOf("count", mapping(EsFieldType.NUMERIC, "long"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(999L, parseField(json, "count").asLong());
    }

    @Test
    void reconstructFromDocValues_numericFloatFromSortableInt() throws IOException {
        // float 3.14f - its int bits stored as Long via doc_values
        long bits = Float.floatToIntBits(3.14f);
        var reader = docValueReader("price", DocValueFieldInfo.DocValueType.NUMERIC, false, bits);
        var ctx = contextOf("price", mapping(EsFieldType.NUMERIC, "float"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(3.14, parseField(json, "price").asDouble(), 1e-5);
    }

    @Test
    void reconstructFromDocValues_numericDoubleFromLongBits() throws IOException {
        long bits = Double.doubleToLongBits(2.718281828);
        var reader = docValueReader("e", DocValueFieldInfo.DocValueType.NUMERIC, false, bits);
        var ctx = contextOf("e", mapping(EsFieldType.NUMERIC, "double"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(2.718281828, parseField(json, "e").asDouble(), 1e-9);
    }

    @Test
    void reconstructFromDocValues_scaledFloatDivides() throws IOException {
        var reader = docValueReader("price", DocValueFieldInfo.DocValueType.NUMERIC, false, 12345L);
        var ctx = contextOf("price", mappingScaled(100.0));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(123.45, parseField(json, "price").asDouble(), 1e-9);
    }

    @Test
    void reconstructFromDocValues_dateEpochMillisFormatsIso() throws IOException {
        var reader = docValueReader("ts", DocValueFieldInfo.DocValueType.NUMERIC, false, 1_700_000_000_000L);
        var ctx = contextOf("ts", mapping(EsFieldType.DATE, "date"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("2023-11-14T22:13:20Z", parseField(json, "ts").asText());
    }

    @Test
    void reconstructFromDocValues_dateNanosFormatsIso() throws IOException {
        long nanos = 1_700_000_000_000_000_500L;
        var reader = docValueReader("ts", DocValueFieldInfo.DocValueType.NUMERIC, false, nanos);
        var ctx = contextOf("ts", mapping(EsFieldType.DATE_NANOS, "date_nanos"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("2023-11-14T22:13:20.000000500Z", parseField(json, "ts").asText());
    }

    @Test
    void reconstructFromDocValues_ipBytes16IPv4Mapped() throws IOException {
        var reader = docValueReader("source_ip", DocValueFieldInfo.DocValueType.SORTED_SET, false,
                ipv4MappedIpv6(10, 1, 2, 3));
        var ctx = contextOf("source_ip", mapping(EsFieldType.IP, "ip"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("10.1.2.3", parseField(json, "source_ip").asText());
    }

    @Test
    void reconstructFromDocValues_ipLongEs2x() throws IOException {
        long ipLong = (8L << 24) | (8L << 16) | (8L << 8) | 8L;
        var reader = docValueReader("source_ip", DocValueFieldInfo.DocValueType.NUMERIC, false, ipLong);
        var ctx = contextOf("source_ip", mapping(EsFieldType.IP, "ip"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("8.8.8.8", parseField(json, "source_ip").asText());
    }

    @Test
    void reconstructFromDocValues_unsignedLongNegativeMasked() throws IOException {
        // -1L as unsigned_long is 2^64 - 1
        var reader = docValueReader("u", DocValueFieldInfo.DocValueType.NUMERIC, false, -1L);
        var ctx = contextOf("u", mapping(EsFieldType.UNSIGNED_LONG, "unsigned_long"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(new java.math.BigInteger("18446744073709551615"), parseField(json, "u").bigIntegerValue());
    }

    @Test
    void reconstructFromDocValues_stringPassthrough() throws IOException {
        var reader = docValueReader("name", DocValueFieldInfo.DocValueType.SORTED, false, "foo");
        var ctx = contextOf("name", mapping(EsFieldType.STRING, "keyword"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("foo", parseField(json, "name").asText());
    }

    @Test
    void reconstructFromDocValues_wildcardDecodesBase64() throws IOException {
        String original = "wildcard-value";
        String base64 = Base64.getEncoder().encodeToString(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var reader = docValueReader("w", DocValueFieldInfo.DocValueType.BINARY, false, base64);
        var ctx = contextOf("w", mapping(EsFieldType.STRING, "wildcard"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(original, parseField(json, "w").asText());
    }

    @Test
    void reconstructFromDocValues_geoPointFromMortonLong() throws IOException {
        // Morton encoding is verified end-to-end rather than bit-checked here.
        // Use a simple Morton-encoded value and assert round-trip through decodeGeoPoint.
        // We'll pick lat=0 lon=0 equivalent: Morton encoded as 0.
        var reader = docValueReader("loc", DocValueFieldInfo.DocValueType.NUMERIC, false, 0L);
        var ctx = contextOf("loc", mapping(EsFieldType.GEO_POINT, "geo_point"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        JsonNode loc = parseField(json, "loc");
        assertNotNull(loc);
        // For Morton hash 0 the decoded values land on the negative boundary; just verify
        // the structure exists so the parameterized sweep catches regressions in shape.
        assertTrue(loc.has("lat") && loc.has("lon"), "geo_point should decode to {lat, lon}");
    }

    @Test
    void reconstructFromDocValues_binaryBase64() throws IOException {
        byte[] payload = {0x00, 0x10, (byte) 0xFF, 0x7F};
        var reader = docValueReader("blob", DocValueFieldInfo.DocValueType.BINARY, false, payload);
        var ctx = contextOf("blob", mapping(EsFieldType.BINARY, "binary"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(Base64.getEncoder().encodeToString(payload), parseField(json, "blob").asText());
    }

    // ==========================================================================================
    // 3. POINTS (BKD) FALLBACK — Lucene 6+, i.e. ES 5+ / OS 1+
    // ==========================================================================================

    /** Builds a reader whose sole recovery channel is a single packed Points value. */
    private static LuceneLeafReader pointsReader(String name, EsFieldType esType, byte[] packed)
            throws IOException {
        var reader = mock(LuceneLeafReader.class);
        when(reader.getDocValueFields()).thenReturn(Collections.emptyList());
        when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(name),
                org.mockito.ArgumentMatchers.eq(esType),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.of(List.of(packed)));
        return reader;
    }

    @Test
    void reconstructFromPoints_longBkd() throws IOException {
        var reader = pointsReader("count", EsFieldType.NUMERIC, packLong(123456789L));
        var ctx = contextOf("count", mapping(EsFieldType.NUMERIC, "long"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(123456789L, parseField(json, "count").asLong());
    }

    @Test
    void reconstructFromPoints_negativeLongBkd() throws IOException {
        var reader = pointsReader("signed", EsFieldType.NUMERIC, packLong(-42L));
        var ctx = contextOf("signed", mapping(EsFieldType.NUMERIC, "long"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(-42L, parseField(json, "signed").asLong());
    }

    @Test
    void reconstructFromPoints_intBkd() throws IOException {
        var reader = pointsReader("small", EsFieldType.NUMERIC, packInt(-1234));
        var ctx = contextOf("small", mapping(EsFieldType.NUMERIC, "integer"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(-1234, parseField(json, "small").asInt());
    }

    @Test
    void reconstructFromPoints_floatBkd() throws IOException {
        var reader = pointsReader("f", EsFieldType.NUMERIC, packFloat(1.5f));
        var ctx = contextOf("f", mapping(EsFieldType.NUMERIC, "float"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(1.5, parseField(json, "f").asDouble(), 1e-6);
    }

    @Test
    void reconstructFromPoints_doubleBkd() throws IOException {
        var reader = pointsReader("d", EsFieldType.NUMERIC, packDouble(-0.25));
        var ctx = contextOf("d", mapping(EsFieldType.NUMERIC, "double"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(-0.25, parseField(json, "d").asDouble(), 1e-12);
    }

    @Test
    void reconstructFromPoints_dateBkdToIso() throws IOException {
        var reader = pointsReader("ts", EsFieldType.DATE, packLong(1_700_000_000_000L));
        var ctx = contextOf("ts", mapping(EsFieldType.DATE, "date"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("2023-11-14T22:13:20Z", parseField(json, "ts").asText());
    }

    @Test
    void reconstructFromPoints_dateNanosBkd() throws IOException {
        long nanos = 1_700_000_000_000_000_500L;
        var reader = pointsReader("ts", EsFieldType.DATE_NANOS, packLong(nanos));
        var ctx = contextOf("ts", mapping(EsFieldType.DATE_NANOS, "date_nanos"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("2023-11-14T22:13:20.000000500Z", parseField(json, "ts").asText());
    }

    @Test
    void reconstructFromPoints_ipBkd16Bytes() throws IOException {
        var reader = pointsReader("source_ip", EsFieldType.IP, ipv4MappedIpv6(192, 168, 1, 100));
        var ctx = contextOf("source_ip", mapping(EsFieldType.IP, "ip"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("192.168.1.100", parseField(json, "source_ip").asText());
    }

    // ==========================================================================================
    // 4. NUMERIC-TERM FALLBACK — Lucene 4/5, i.e. ES 1.x / ES 2.x
    // ==========================================================================================

    /** Builds a reader whose sole recovery channel is a single decoded-long term. */
    private static LuceneLeafReader numericTermReader(String name, EsFieldType esType, long decoded)
            throws IOException {
        var reader = mock(LuceneLeafReader.class);
        when(reader.getDocValueFields()).thenReturn(Collections.emptyList());
        when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(name),
                org.mockito.ArgumentMatchers.eq(esType),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.of(decoded));
        return reader;
    }

    @Test
    void reconstructFromNumericTerms_ipFromLong() throws IOException {
        long ipLong = (10L << 24) | (0L << 16) | (0L << 8) | 1L;
        var reader = numericTermReader("source_ip", EsFieldType.IP, ipLong);
        var ctx = contextOf("source_ip", mapping(EsFieldType.IP, "ip"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("10.0.0.1", parseField(json, "source_ip").asText());
    }

    @Test
    void reconstructFromNumericTerms_dateFromLong() throws IOException {
        var reader = numericTermReader("ts", EsFieldType.DATE, 1_700_000_000_000L);
        var ctx = contextOf("ts", mapping(EsFieldType.DATE, "date"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("2023-11-14T22:13:20Z", parseField(json, "ts").asText());
    }

    @Test
    void reconstructFromNumericTerms_longPassthrough() throws IOException {
        var reader = numericTermReader("count", EsFieldType.NUMERIC, 42L);
        var ctx = contextOf("count", mapping(EsFieldType.NUMERIC, "long"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(42L, parseField(json, "count").asLong());
    }

    @Test
    void reconstructFromNumericTerms_intFromLong() throws IOException {
        var reader = numericTermReader("n", EsFieldType.NUMERIC, -1234L);
        var ctx = contextOf("n", mapping(EsFieldType.NUMERIC, "integer"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(-1234, parseField(json, "n").asInt());
    }

    @Test
    void reconstructFromNumericTerms_floatFromSortableIntBits() throws IOException {
        // Lucene 4/5 stores float as sortableInt: bits ^ ((bits >> 31) & 0x7fffffff)
        int bits = Float.floatToIntBits(3.14f);
        int sortable = bits ^ ((bits >> 31) & 0x7fffffff);
        var reader = numericTermReader("f", EsFieldType.NUMERIC, (long) sortable);
        var ctx = contextOf("f", mapping(EsFieldType.NUMERIC, "float"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(3.14, parseField(json, "f").asDouble(), 1e-5);
    }

    @Test
    void reconstructFromNumericTerms_doubleFromSortableLongBits() throws IOException {
        long bits = Double.doubleToLongBits(-0.5);
        long sortable = bits ^ ((bits >> 63) & 0x7fffffffffffffffL);
        var reader = numericTermReader("d", EsFieldType.NUMERIC, sortable);
        var ctx = contextOf("d", mapping(EsFieldType.NUMERIC, "double"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(-0.5, parseField(json, "d").asDouble(), 1e-12);
    }

    @Test
    void reconstructFromNumericTerms_scaledFloatDivides() throws IOException {
        var reader = numericTermReader("price", EsFieldType.SCALED_FLOAT, 12345L);
        var ctx = contextOf("price", mappingScaled(100.0));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(123.45, parseField(json, "price").asDouble(), 1e-9);
    }

    @Test
    void reconstructFromNumericTerms_unsignedLongNegativeMasked() throws IOException {
        var reader = numericTermReader("u", EsFieldType.UNSIGNED_LONG, -1L);
        var ctx = contextOf("u", mapping(EsFieldType.UNSIGNED_LONG, "unsigned_long"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(new java.math.BigInteger("18446744073709551615"), parseField(json, "u").bigIntegerValue());
    }

    // ==========================================================================================
    // 5. STRING TERMS FALLBACK — analyzed text without store / doc_values
    // ==========================================================================================

    @Test
    void reconstructFromStringTerms_joinsInPositionOrder() throws IOException {
        var reader = mock(LuceneLeafReader.class);
        when(reader.getDocValueFields()).thenReturn(Collections.emptyList());
        when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq("body"),
                org.mockito.ArgumentMatchers.eq(EsFieldType.STRING),
                org.mockito.ArgumentMatchers.any()))
            // SourceReconstructor receives the already-joined String from LuceneLeafReader.
            .thenReturn(Optional.of("quick brown fox"));
        var ctx = contextOf("body", mapping(EsFieldType.STRING, "text"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals("quick brown fox", parseField(json, "body").asText());
    }

    @Test
    void reconstructFromStringTerms_booleanFromTerm() throws IOException {
        var reader = mock(LuceneLeafReader.class);
        when(reader.getDocValueFields()).thenReturn(Collections.emptyList());
        when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq("enabled"),
                org.mockito.ArgumentMatchers.eq(EsFieldType.BOOLEAN),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.of("T"));
        var ctx = contextOf("enabled", mapping(EsFieldType.BOOLEAN, "boolean"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(true, parseField(json, "enabled").asBoolean());
    }

    // ==========================================================================================
    // 6. PRIORITY / PRECEDENCE — stored wins over doc_values, doc_values wins over fallback.
    // ==========================================================================================

    @Test
    void storedFieldsTakePrecedenceOverDocValues() throws IOException {
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple("count", DocValueFieldInfo.DocValueType.NUMERIC, false);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn(999L);
        when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.empty());
        var doc = document(storedNumber("count", 42L));
        var ctx = contextOf("count", mapping(EsFieldType.NUMERIC, "long"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        // Stored field's 42 should win over doc_values' 999.
        assertEquals(42L, parseField(json, "count").asLong());
    }

    @Test
    void docValuesTakePrecedenceOverPointsFallback() throws IOException {
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple("count", DocValueFieldInfo.DocValueType.NUMERIC, false);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn(77L);
        when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.of(List.of(packLong(999L))));
        var ctx = contextOf("count", mapping(EsFieldType.NUMERIC, "long"));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        assertEquals(77L, parseField(json, "count").asLong());
    }

    @Test
    void disabledDocValuesInMappingSkipsDocValuesPath() throws IOException {
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple("count", DocValueFieldInfo.DocValueType.NUMERIC, false);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn(77L);
        when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.empty());

        var ctx = new FieldMappingContext(null);
        try {
            var f = FieldMappingContext.class.getDeclaredField("fieldMappings");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var m = (java.util.Map<String, FieldMappingInfo>) f.get(ctx);
            m.put("count", new FieldMappingInfo(EsFieldType.NUMERIC, "long", null, null, false, null));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        // Mapping says doc_values disabled — reconstructor must not surface the value from the
        // doc_values read, and with no stored field nor fallback the document ends up empty → null.
        assertNull(json);
    }

    // ==========================================================================================
    // 7. PARAMETERIZED SWEEP — all types × all pathways in one compact table.
    //
    // This guards against silent regressions if a new pathway-specific branch is added and
    // breaks an existing (type, pathway) combination. The expected JSON-node value for each
    // combination is encoded up-front so the expectations are visible as a single table.
    // ==========================================================================================

    enum Path { STORED_FIELD, DOC_VALUES, POINTS, NUMERIC_TERMS }

    private record Row(
            String name,
            EsFieldType type,
            String mappingType,
            Double scalingFactor,
            String format,
            Path path,
            Object inputValue,        // raw value to plant in the chosen pathway
            DocValueFieldInfo.DocValueType dvType, // only for DOC_VALUES
            Object expectedJson       // expected value in JSON tree (String / Number / Boolean / Map / BigInteger)
    ) {}

    static Stream<Arguments> allTypesMatrix() {
        return Stream.of(
            // ---- Stored fields ----
            Arguments.of(new Row("boolean-stored", EsFieldType.BOOLEAN, "boolean", null, null,
                Path.STORED_FIELD, "T", null, Boolean.TRUE)),
            Arguments.of(new Row("long-stored", EsFieldType.NUMERIC, "long", null, null,
                Path.STORED_FIELD, 42L, null, 42L)),
            Arguments.of(new Row("date-stored", EsFieldType.DATE, "date", null, null,
                Path.STORED_FIELD, 1_700_000_000_000L, null, "2023-11-14T22:13:20Z")),
            Arguments.of(new Row("ip-stored-es2", EsFieldType.IP, "ip", null, null,
                Path.STORED_FIELD, ((192L << 24) | (168L << 16) | (1L << 8) | 100L), null, "192.168.1.100")),
            Arguments.of(new Row("scaled-stored", EsFieldType.SCALED_FLOAT, "scaled_float", 100.0, null,
                Path.STORED_FIELD, 12345L, null, 123.45)),

            // ---- doc_values ----
            Arguments.of(new Row("boolean-dv", EsFieldType.BOOLEAN, "boolean", null, null,
                Path.DOC_VALUES, 1L, DocValueFieldInfo.DocValueType.NUMERIC, Boolean.TRUE)),
            Arguments.of(new Row("long-dv", EsFieldType.NUMERIC, "long", null, null,
                Path.DOC_VALUES, 99L, DocValueFieldInfo.DocValueType.NUMERIC, 99L)),
            Arguments.of(new Row("double-dv", EsFieldType.NUMERIC, "double", null, null,
                Path.DOC_VALUES, Double.doubleToLongBits(2.5),
                DocValueFieldInfo.DocValueType.NUMERIC, 2.5)),
            Arguments.of(new Row("date-dv", EsFieldType.DATE, "date", null, null,
                Path.DOC_VALUES, 1_700_000_000_000L,
                DocValueFieldInfo.DocValueType.NUMERIC, "2023-11-14T22:13:20Z")),
            Arguments.of(new Row("ip-dv-binary", EsFieldType.IP, "ip", null, null,
                Path.DOC_VALUES, ipv4MappedIpv6(10, 0, 0, 1),
                DocValueFieldInfo.DocValueType.SORTED_SET, "10.0.0.1")),
            Arguments.of(new Row("string-dv", EsFieldType.STRING, "keyword", null, null,
                Path.DOC_VALUES, "foo",
                DocValueFieldInfo.DocValueType.SORTED, "foo")),

            // ---- Points / BKD (ES 5+) ----
            Arguments.of(new Row("long-bkd", EsFieldType.NUMERIC, "long", null, null,
                Path.POINTS, packLong(777L), null, 777L)),
            Arguments.of(new Row("double-bkd", EsFieldType.NUMERIC, "double", null, null,
                Path.POINTS, packDouble(-0.25), null, -0.25)),
            Arguments.of(new Row("date-bkd", EsFieldType.DATE, "date", null, null,
                Path.POINTS, packLong(1_700_000_000_000L), null, "2023-11-14T22:13:20Z")),
            Arguments.of(new Row("ip-bkd", EsFieldType.IP, "ip", null, null,
                Path.POINTS, ipv4MappedIpv6(192, 168, 1, 100), null, "192.168.1.100")),

            // ---- Numeric terms (ES 1.x / 2.x) ----
            Arguments.of(new Row("long-trie", EsFieldType.NUMERIC, "long", null, null,
                Path.NUMERIC_TERMS, 111L, null, 111L)),
            Arguments.of(new Row("ip-trie", EsFieldType.IP, "ip", null, null,
                Path.NUMERIC_TERMS, ((10L << 24) | (0L << 16) | (0L << 8) | 1L), null, "10.0.0.1")),
            Arguments.of(new Row("date-trie", EsFieldType.DATE, "date", null, null,
                Path.NUMERIC_TERMS, 1_700_000_000_000L, null, "2023-11-14T22:13:20Z")),
            Arguments.of(new Row("scaled-trie", EsFieldType.SCALED_FLOAT, "scaled_float", 100.0, null,
                Path.NUMERIC_TERMS, 12345L, null, 123.45))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTypesMatrix")
    void allTypesMatrix(Row row) throws IOException {
        var info = new FieldMappingInfo(row.type(), row.mappingType(), row.format(),
                row.scalingFactor(), true, null);
        var ctx = contextOf("field", info);

        LuceneLeafReader reader;
        LuceneDocument doc = document();
        switch (row.path()) {
            case STORED_FIELD -> {
                reader = storedOnlyReader();
                LuceneField field;
                if (row.inputValue() instanceof Number n) {
                    field = storedNumber("field", n);
                } else if (row.inputValue() instanceof byte[] bytes) {
                    field = storedBinary("field", bytes);
                } else {
                    field = storedString("field", (String) row.inputValue());
                }
                doc = document(field);
            }
            case DOC_VALUES -> reader = docValueReader("field", row.dvType(), false, row.inputValue());
            case POINTS -> reader = pointsReader("field", row.type(), (byte[]) row.inputValue());
            case NUMERIC_TERMS -> reader = numericTermReader("field", row.type(), ((Number) row.inputValue()).longValue());
            default -> throw new IllegalStateException("Unhandled path: " + row.path());
        }

        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        assertNotNull(json, () -> "Expected non-null reconstruction for " + row.name());
        JsonNode actual = parseField(json, "field");
        assertNotNull(actual, () -> "Expected field in JSON for " + row.name() + " → " + json);

        Object expected = row.expectedJson();
        if (expected instanceof Boolean b) {
            assertEquals(b, actual.asBoolean(), row.name());
        } else if (expected instanceof Long l) {
            assertEquals(l.longValue(), actual.asLong(), row.name());
        } else if (expected instanceof Integer i) {
            assertEquals(i.intValue(), actual.asInt(), row.name());
        } else if (expected instanceof Double d) {
            assertEquals(d, actual.asDouble(), 1e-9, row.name());
        } else if (expected instanceof String s) {
            assertEquals(s, actual.asText(), row.name());
        } else if (expected instanceof java.math.BigInteger bi) {
            assertEquals(bi, actual.bigIntegerValue(), row.name());
        } else {
            throw new IllegalStateException("Unhandled expected type: " + expected.getClass() + " for " + row.name());
        }
    }

    // ==========================================================================================
    // 8. mergeWithDocValues — ensures fields absent from existing source are merged in.
    // ==========================================================================================

    @Test
    void mergeWithDocValues_addsMissingField() throws IOException {
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple("count", DocValueFieldInfo.DocValueType.NUMERIC, false);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn(42L);

        var ctx = contextOf("count", mapping(EsFieldType.NUMERIC, "long"));
        String merged = SourceReconstructor.mergeWithDocValues(
                "{\"name\":\"alice\"}", reader, 0, document(), ctx);

        Map<?,?> result = MAPPER.readValue(merged, Map.class);
        assertEquals("alice", result.get("name"));
        assertEquals(42, ((Number) result.get("count")).intValue());
    }

    @Test
    void mergeWithDocValues_preservesExistingFieldValue() throws IOException {
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple("count", DocValueFieldInfo.DocValueType.NUMERIC, false);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn(99L);

        var ctx = contextOf("count", mapping(EsFieldType.NUMERIC, "long"));
        String merged = SourceReconstructor.mergeWithDocValues(
                "{\"count\":7}", reader, 0, document(), ctx);

        Map<?,?> result = MAPPER.readValue(merged, Map.class);
        // Existing source value should not be overwritten by the doc_values value.
        assertEquals(7, ((Number) result.get("count")).intValue());
    }

    // ==========================================================================================
    // 9. shouldSkipField — internal Elasticsearch fields (_id, _source, _type, etc) never appear.
    // ==========================================================================================

    @Test
    void internalFieldsSuchAsUnderscoreIdAreNeverEmitted() {
        var reader = storedOnlyReader();
        var doc = document(
                storedString("_id", "abc"),
                storedString("_uid", "abc"),
                storedString("_type", "doc"),
                storedString("_source", "{}"),
                storedString("name", "alice")
        );
        var ctx = contextOf("name", mapping(EsFieldType.STRING, "keyword"));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertTrue(tree.has("name"));
        assertTrue(!tree.has("_id"), "_id must be filtered");
        assertTrue(!tree.has("_uid"), "_uid must be filtered");
        assertTrue(!tree.has("_type"), "_type must be filtered");
        assertTrue(!tree.has("_source"), "_source must be filtered");
    }

    // ==========================================================================================
    // 6. NESTED / DOTTED FIELD NAMES  (addresses PR #2771 reviewer concern)
    // ==========================================================================================
    // Dotted fields are ambiguous in Lucene: `title.keyword` is a multi-field sub-field (safe to
    // drop — parent `title` recovers it); `address.city` is an object subfield (a distinct
    // mapped field that MUST survive reconstruction, nested under `address`).
    //
    // `shouldSkipField` distinguishes them via the mapping context: object subfields are tracked
    // under `properties` and come back from `mappingContext.getFieldInfo(...)`; multi-field
    // sub-fields are not. `putNested` / `hasNested` then write/read the dotted path into the
    // appropriate intermediate map.

    /** Register multiple field mappings on a single context. */
    private static FieldMappingContext contextOfMany(java.util.Map<String, FieldMappingInfo> entries) {
        var ctx = new FieldMappingContext(null);
        try {
            var f = FieldMappingContext.class.getDeclaredField("fieldMappings");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var m = (java.util.Map<String, FieldMappingInfo>) f.get(ctx);
            m.putAll(entries);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return ctx;
    }

    @Test
    void objectSubfield_isNestedUnderParent() {
        // `address.city` is a legitimate object subfield — must appear as
        // {"address":{"city":"NYC"}} in the reconstructed source.
        var reader = storedOnlyReader();
        var doc = document(storedString("address.city", "NYC"));
        var ctx = contextOfMany(java.util.Map.of(
            "address.city", mapping(EsFieldType.STRING, "keyword")
        ));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertTrue(tree.has("address"), "parent object must be present: " + json);
        assertNotNull(tree.path("address").get("city"),
            "address.city must be nested under address: " + json);
        assertEquals("NYC", tree.path("address").get("city").asText());
    }

    @Test
    void multipleObjectSubfields_shareParent() {
        // Two subfields under the same parent object should merge into a single
        // nested map, not overwrite each other.
        var reader = storedOnlyReader();
        var doc = document(
            storedString("address.city", "NYC"),
            storedString("address.zip", "10001")
        );
        var ctx = contextOfMany(java.util.Map.of(
            "address.city", mapping(EsFieldType.STRING, "keyword"),
            "address.zip", mapping(EsFieldType.STRING, "keyword")
        ));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        JsonNode addr = tree.path("address");
        assertEquals("NYC", addr.get("city").asText(), "city preserved: " + json);
        assertEquals("10001", addr.get("zip").asText(), "zip preserved: " + json);
    }

    @Test
    void deepObjectSubfield_threeLevels() {
        // `user.profile.email` — three-level nesting must be preserved.
        var reader = storedOnlyReader();
        var doc = document(storedString("user.profile.email", "a@b.com"));
        var ctx = contextOfMany(java.util.Map.of(
            "user.profile.email", mapping(EsFieldType.STRING, "keyword")
        ));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertEquals("a@b.com", tree.path("user").path("profile").path("email").asText(),
            "deep nesting preserved: " + json);
    }

    @Test
    void multiFieldSubfield_withoutMapping_isSkipped() {
        // `title.keyword` is NOT in the mapping — shouldSkipField must drop it
        // (parent `title` reconstructs the sub-field upstream at index time).
        var reader = storedOnlyReader();
        var doc = document(
            storedString("title", "hello"),
            storedString("title.keyword", "hello")
        );
        var ctx = contextOfMany(java.util.Map.of(
            "title", mapping(EsFieldType.STRING, "text")
            // NOTE: title.keyword intentionally NOT registered.
        ));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertEquals("hello", tree.path("title").asText());
        assertTrue(!tree.has("title.keyword"), "multi-field sub-field must be skipped: " + json);
        // Also must not leak into a nested {"title":{"keyword":"hello"}} shape — title is a
        // scalar string, not an object.
        assertTrue(tree.path("title").isTextual(), "title must remain scalar text: " + json);
    }

    @Test
    void nullMappingContext_skipsAllDottedFields() {
        // Defensive: when no mapping is available, we have no way to tell multi-field
        // sub-fields apart from object subfields — drop anything dotted.
        var reader = storedOnlyReader();
        var doc = document(
            storedString("plain", "kept"),
            storedString("address.city", "dropped")
        );
        var ctx = new FieldMappingContext(null);  // empty — no field info.
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertEquals("kept", tree.path("plain").asText());
        assertTrue(!tree.has("address"), "dotted field dropped when no mapping: " + json);
    }

    @Test
    void underscorePrefix_stillSkipped_evenIfInMapping() {
        // Internal fields (_id, _routing) are ALWAYS skipped,
        // even if they happen to be registered in the mapping — they're consumed
        // by the reader itself, not re-emitted into _source.
        var reader = storedOnlyReader();
        var doc = document(
            storedString("_id", "abc"),
            storedString("_routing", "shard1"),
            storedString("name", "alice")
        );
        var ctx = contextOfMany(java.util.Map.of(
            "name", mapping(EsFieldType.STRING, "keyword"),
            // Even if "_id" / "_routing" were somehow in mapping, they must be skipped.
            "_id", mapping(EsFieldType.STRING, "keyword"),
            "_routing", mapping(EsFieldType.STRING, "keyword")
        ));
        String json = SourceReconstructor.reconstructSource(reader, 0, doc, ctx);
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertEquals("alice", tree.path("name").asText());
        assertTrue(!tree.has("_id"), "_id must be skipped: " + json);
        assertTrue(!tree.has("_routing"), "_routing must be skipped: " + json);
    }
    // ==========================================================================================
    // 10. MERGE PATH CORRECTNESS REGRESSIONS (bugs found by correctness critics — PR #2771)
    // ==========================================================================================
    // These exercise the `mergeWithDocValues` code paths that handle
    // partial-_source indices (includes/excludes). Before the fixes, size-based dirty detection
    // dropped nested inserts and `putAll` shallow-merged nested objects — both silently lost data.

    @Test
    void mergeWithDocValues_dottedSubfield_intoExistingParentMap_isReSerialized() throws IOException {
        // REGRESSION: mergeWithDocValues used `existing.size() != sizeBefore` to decide whether to
        // re-serialize. putNested writing `address.zip` into an already-present `address` inner map
        // does NOT change the top-level size → original unchanged string was returned, losing zip.
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple("address.zip", DocValueFieldInfo.DocValueType.SORTED, false);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn("10001");
        try {
            when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        } catch (IOException e) { throw new RuntimeException(e); }

        var ctx = contextOfMany(java.util.Map.of(
            "address.zip", mapping(EsFieldType.STRING, "keyword")
        ));
        String merged = SourceReconstructor.mergeWithDocValues(
                "{\"address\":{\"city\":\"NYC\"}}", reader, 0, document(), ctx);

        JsonNode tree = MAPPER.readTree(merged);
        assertEquals("NYC", tree.path("address").path("city").asText(),
            "existing subfield preserved: " + merged);
        assertEquals("10001", tree.path("address").path("zip").asText(),
            "doc-value subfield written into existing parent map: " + merged);
    }

    @Test
    void mergeWithDocValues_noChange_returnsOriginalString() {
        // Sanity: when nothing is added, we return the exact original string (no re-serialize churn).
        var reader = storedOnlyReader();  // no docvalues, no points, no terms
        var ctx = contextOf("name", mapping(EsFieldType.STRING, "keyword"));
        String original = "{\"name\":\"alice\"}";
        String merged = SourceReconstructor.mergeWithDocValues(original, reader, 0, document(), ctx);
        assertEquals(original, merged, "unchanged payload must be returned verbatim");
    }

    @Test
    void reconstructSource_literalDottedKey_inExistingSource_notDoubleEmitted() throws IOException {
        // REGRESSION: hasNested used to walk only the nested chain, so a literal dotted key like
        // `address.city` kept in the partial _source would NOT be considered "present" and
        // putNested would write a second copy at `{"address":{"city":...}}` — emitting both shapes.
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple("address.city", DocValueFieldInfo.DocValueType.SORTED, false);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn("SHOULD_NOT_APPEAR");
        try {
            when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        } catch (IOException e) { throw new RuntimeException(e); }

        var ctx = contextOfMany(java.util.Map.of(
            "address.city", mapping(EsFieldType.STRING, "keyword")
        ));
        // partial source with the literal dotted key preserved verbatim (some ingest pipelines do this)
        String merged = SourceReconstructor.mergeWithDocValues(
                "{\"address.city\":\"NYC\"}", reader, 0, document(), ctx);

        JsonNode tree = MAPPER.readTree(merged);
        assertEquals("NYC", tree.path("address.city").asText(),
            "literal dotted key preserved: " + merged);
        // Must NOT also emit a nested {address:{city:...}} copy.
        assertTrue(!tree.has("address") || tree.path("address").isNull(),
            "nested duplicate must not be emitted: " + merged);
    }

    @Test
    void populateFromSegment_scalarAtNestedParent_dropsSubfieldWithWarn() throws IOException {
        // REGRESSION: putNested previously returned silently when an ancestor path held a scalar.
        // Now the path is still skipped (can't nest under a scalar) but a warn log is emitted so
        // operators notice the dropped value. This test locks in the no-crash, no-corruption contract.
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple("address.city", DocValueFieldInfo.DocValueType.SORTED, false);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn("NYC");
        try {
            when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        } catch (IOException e) { throw new RuntimeException(e); }

        var ctx = contextOfMany(java.util.Map.of(
            "address.city", mapping(EsFieldType.STRING, "keyword")
        ));
        // existing source has `address` as a plain scalar string (pathological but possible from old snapshots)
        String merged = SourceReconstructor.mergeWithDocValues(
                "{\"address\":\"plain scalar address\"}", reader, 0, document(), ctx);

        // Scalar parent wins; subfield quietly dropped (warn log only). The existing source must be returned
        // UNCHANGED — no corruption, no partial write.
        assertEquals("{\"address\":\"plain scalar address\"}", merged,
            "scalar parent blocks nested write; output unchanged: " + merged);
    }

    @Test
    void reconstructSource_dottedSubfieldViaDocValues_isNested() throws IOException {
        // Coverage gap: the doc-values loop is one of three recovery paths that calls putNested.
        // Previously only the stored-fields path had an explicit dotted test. Lock in the shape.
        var reader = mock(LuceneLeafReader.class);
        var info = new DocValueFieldInfo.Simple("user.email", DocValueFieldInfo.DocValueType.SORTED, false);
        when(reader.getDocValueFields()).thenReturn(List.of(info));
        when(reader.getDocValue(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(info))).thenReturn("alice@example.com");
        try {
            when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        } catch (IOException e) { throw new RuntimeException(e); }

        var ctx = contextOfMany(java.util.Map.of(
            "user.email", mapping(EsFieldType.STRING, "keyword")
        ));
        String json = SourceReconstructor.reconstructSource(reader, 0, document(), ctx);
        JsonNode tree = MAPPER.readTree(json);
        assertEquals("alice@example.com", tree.path("user").path("email").asText(),
            "dotted subfield from doc_values path must nest: " + json);
    }

    // ==========================================================================================
    // 11. OBJECT-ARRAY SUBFIELD DISTRIBUTION (mergeWithDocValues into seeded List<Map>)
    // ==========================================================================================
    // Real-world case: an index mapping has `files` as `type: object` with subfields
    // `files.cksum`, `files.size`, `files.name`, ... Some subfields are in `_source.excludes`, so
    // the snapshot's seed _source holds `files` as an ArrayList<Map> with only the non-excluded
    // subfields; the excluded ones must be recovered from doc_values. Prior to object-array
    // distribution, putNested refused to write under the List parent and logged a warn, silently
    // dropping the recovered value. These tests exercise the new distribution branch.
    //
    // Ordering caveat: doc_values return subfield values in traversal order (SortedNumeric:
    // ascending numeric; SortedSet: ascending term), not original array insertion order.
    // Element-to-element binding across subfields recovered from doc_values is therefore
    // approximate — useful for presence/search/aggregation, not display-accurate tuples.

    /** Multi-field docvalues reader: seed N fields at once with per-field return values. */
    private static LuceneLeafReader multiDocValueReader(java.util.Map<String, DocValueFieldSpec> fields) {
        var reader = mock(LuceneLeafReader.class);
        java.util.List<DocValueFieldInfo> infos = new java.util.ArrayList<>();
        fields.forEach((name, spec) -> {
            var info = new DocValueFieldInfo.Simple(name, spec.type, spec.isBoolean);
            infos.add(info);
            try {
                when(reader.getDocValue(org.mockito.ArgumentMatchers.eq(0),
                        org.mockito.ArgumentMatchers.eq(info))).thenReturn(spec.value);
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        when(reader.getDocValueFields()).thenReturn(infos);
        try {
            when(reader.getValueFromPointsOrTerms(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        } catch (IOException e) { throw new RuntimeException(e); }
        return reader;
    }

    /** Spec for a doc-value field used by {@link #multiDocValueReader}. */
    private record DocValueFieldSpec(DocValueFieldInfo.DocValueType type, boolean isBoolean, Object value) {}

    @Test
    void mergeWithDocValues_distributesSubfield_intoSeededObjectArray() throws IOException {
        // Reproduces the enron2_archive.av5 case: seed _source carries `files` as List<Map> with
        // the non-excluded subfield `cksum`, and `files.size` arrives via multi-valued doc_values.
        // Expected: `size` distributes positionally into each array element.
        var reader = multiDocValueReader(java.util.Map.of(
            "files.size", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_NUMERIC, false,
                java.util.List.of(100L, 500L))
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.size", mapping(EsFieldType.NUMERIC, "long"),
            "files.cksum", mapping(EsFieldType.STRING, "keyword")
        ));
        String seed = "{\"files\":[{\"cksum\":\"h1\"},{\"cksum\":\"h2\"}]}";
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);

        JsonNode tree = MAPPER.readTree(merged);
        JsonNode files = tree.path("files");
        assertTrue(files.isArray(), "files must remain an array: " + merged);
        assertEquals(2, files.size(), "array size preserved: " + merged);
        assertEquals("h1", files.get(0).path("cksum").asText(), "element 0 cksum preserved: " + merged);
        assertEquals(100L, files.get(0).path("size").asLong(), "element 0 size distributed: " + merged);
        assertEquals("h2", files.get(1).path("cksum").asText(), "element 1 cksum preserved: " + merged);
        assertEquals(500L, files.get(1).path("size").asLong(), "element 1 size distributed: " + merged);
    }

    @Test
    void mergeWithDocValues_distributesMultipleSubfields_intoSeededObjectArray() throws IOException {
        // Two excluded subfields (size, name) recovered from doc_values, seeded cksum in _source.
        // Locks in that repeated putNested calls against the same List<Map> parent compose
        // correctly — each subfield distributes independently without clobbering prior writes.
        var reader = multiDocValueReader(java.util.Map.of(
            "files.size", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_NUMERIC, false,
                java.util.List.of(100L, 500L, 2000L)),
            "files.name", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_SET, false,
                java.util.List.of("a.txt", "b.txt", "c.txt"))
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.size", mapping(EsFieldType.NUMERIC, "long"),
            "files.name", mapping(EsFieldType.STRING, "keyword"),
            "files.cksum", mapping(EsFieldType.STRING, "keyword")
        ));
        String seed = "{\"files\":[{\"cksum\":\"h1\"},{\"cksum\":\"h2\"},{\"cksum\":\"h3\"}]}";
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);

        JsonNode files = MAPPER.readTree(merged).path("files");
        assertEquals(3, files.size());
        assertEquals(100L, files.get(0).path("size").asLong());
        assertEquals("a.txt", files.get(0).path("name").asText());
        assertEquals("h1", files.get(0).path("cksum").asText());
        assertEquals(2000L, files.get(2).path("size").asLong());
        assertEquals("c.txt", files.get(2).path("name").asText());
        assertEquals("h3", files.get(2).path("cksum").asText());
    }

    @Test
    void mergeWithDocValues_sizeMismatch_dropsValueAndPreservesSeed() throws IOException {
        // Positional distribution requires equal lengths. A size mismatch cannot safely distribute
        // (no ordinal→element binding), so the branch warns and drops — seed stays intact.
        var reader = multiDocValueReader(java.util.Map.of(
            "files.size", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_NUMERIC, false,
                java.util.List.of(100L, 500L, 2000L))  // 3 values
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.size", mapping(EsFieldType.NUMERIC, "long"),
            "files.cksum", mapping(EsFieldType.STRING, "keyword")
        ));
        String seed = "{\"files\":[{\"cksum\":\"h1\"},{\"cksum\":\"h2\"}]}";  // 2 elements
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);

        JsonNode files = MAPPER.readTree(merged).path("files");
        assertEquals(2, files.size(), "seed array length preserved: " + merged);
        assertTrue(!files.get(0).has("size") && !files.get(1).has("size"),
            "size must NOT be partially distributed on length mismatch: " + merged);
        assertEquals("h1", files.get(0).path("cksum").asText());
        assertEquals("h2", files.get(1).path("cksum").asText());
    }

    @Test
    void mergeWithDocValues_scalarBroadcast_appliesToEveryElement() throws IOException {
        // A single-valued doc_value (NUMERIC, not SORTED_NUMERIC) against a multi-element
        // object-array seed broadcasts to every element. Real-world case: every file in the
        // array shares the same attribute (e.g. document-level tag stored under a subfield).
        var reader = multiDocValueReader(java.util.Map.of(
            "files.tstatus", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.NUMERIC, false, 1L)
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.tstatus", mapping(EsFieldType.NUMERIC, "long"),
            "files.cksum", mapping(EsFieldType.STRING, "keyword")
        ));
        String seed = "{\"files\":[{\"cksum\":\"h1\"},{\"cksum\":\"h2\"}]}";
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);

        JsonNode files = MAPPER.readTree(merged).path("files");
        assertEquals(1L, files.get(0).path("tstatus").asLong(), "element 0 gets broadcast: " + merged);
        assertEquals(1L, files.get(1).path("tstatus").asLong(), "element 1 gets broadcast: " + merged);
    }

    @Test
    void mergeWithDocValues_distribution_preservesExistingElementSubfield() throws IOException {
        // First-write-wins: if an element already has the subfield (e.g. partial _source
        // retained it for some elements but not others), distribution must NOT overwrite.
        // Existing non-null values take precedence; null in the seed is treated as absent and
        // would be overwritten — but this test pins the positive guarantee for present values.
        var reader = multiDocValueReader(java.util.Map.of(
            "files.size", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_NUMERIC, false,
                java.util.List.of(100L, 500L))
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.size", mapping(EsFieldType.NUMERIC, "long"),
            "files.cksum", mapping(EsFieldType.STRING, "keyword")
        ));
        // Element 0 already carries size=999 from a hypothetical upstream pipeline — must survive.
        String seed = "{\"files\":[{\"cksum\":\"h1\",\"size\":999},{\"cksum\":\"h2\"}]}";
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);

        JsonNode files = MAPPER.readTree(merged).path("files");
        assertEquals(999L, files.get(0).path("size").asLong(),
            "existing element subfield must be preserved: " + merged);
        assertEquals(500L, files.get(1).path("size").asLong(),
            "missing element subfield gets distributed value: " + merged);
    }

    @Test
    void mergeWithDocValues_reMergeIsIdempotent() throws IOException {
        // Guards against double-distribution on a second merge pass. hasNested must recognise
        // that EVERY list element already carries the subfield and short-circuit. If this test
        // fails, populateFromSegment would silently double-write (second write is ignored by
        // element.containsKey(leaf) check, but modified=true would force a re-serialization
        // that differs from the input — a subtle mutation bug).
        var reader = multiDocValueReader(java.util.Map.of(
            "files.size", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_NUMERIC, false,
                java.util.List.of(100L, 500L))
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.size", mapping(EsFieldType.NUMERIC, "long"),
            "files.cksum", mapping(EsFieldType.STRING, "keyword")
        ));
        String seed = "{\"files\":[{\"cksum\":\"h1\"},{\"cksum\":\"h2\"}]}";
        String firstPass = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);
        String secondPass = SourceReconstructor.mergeWithDocValues(firstPass, reader, 0, document(), ctx);

        assertEquals(firstPass, secondPass,
            "re-merge against same seed+docvalues must be a fixed point: first=" + firstPass
                + " second=" + secondPass);
    }

    @Test
    void mergeWithDocValues_partialElementCoverage_fillsGaps() throws IOException {
        // Some elements already have `size`, others do not. hasNested returns false (not every
        // element carries it), distribution proceeds. The length check uses docvalues.size vs
        // list.size — NOT list-elements-missing-the-subfield — so when partial pre-population is
        // present, distribution still requires the docvalues array to match the full array. The
        // gap-fill happens only inside distributeSubfieldAcrossList via element.containsKey.
        var reader = multiDocValueReader(java.util.Map.of(
            "files.size", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_NUMERIC, false,
                java.util.List.of(100L, 500L, 2000L))
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.size", mapping(EsFieldType.NUMERIC, "long"),
            "files.cksum", mapping(EsFieldType.STRING, "keyword")
        ));
        // Middle element already has size=999 — must be preserved; others get distributed.
        String seed = "{\"files\":[{\"cksum\":\"h1\"},{\"cksum\":\"h2\",\"size\":999},{\"cksum\":\"h3\"}]}";
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);

        JsonNode files = MAPPER.readTree(merged).path("files");
        assertEquals(100L, files.get(0).path("size").asLong());
        assertEquals(999L, files.get(1).path("size").asLong(), "middle element preserved: " + merged);
        assertEquals(2000L, files.get(2).path("size").asLong());
    }

    @Test
    void mergeWithDocValues_listOfNonMaps_preservesWarnAndDropContract() throws IOException {
        // `files` is a List of scalars (true scalar array, e.g. `"files":["a.txt","b.txt"]`),
        // NOT a List<Map>. The distribute branch must NOT fire — isListOfMaps returns false,
        // control falls through to the existing warn-and-drop branch, seed is returned unchanged.
        var reader = multiDocValueReader(java.util.Map.of(
            "files.size", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_NUMERIC, false,
                java.util.List.of(100L, 500L))
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.size", mapping(EsFieldType.NUMERIC, "long")
        ));
        String seed = "{\"files\":[\"a.txt\",\"b.txt\"]}";
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);

        assertEquals(seed, merged, "scalar-array parent preserves warn-and-drop contract: " + merged);
    }

    @Test
    void mergeWithDocValues_emptyObjectArray_fallsThroughToWarnAndDrop() throws IOException {
        // An empty `files:[]` is ambiguous (could be either scalar-array or object-array). The
        // distribute branch deliberately does NOT fire for empty lists — no elements to distribute
        // into anyway — so the existing warn-and-drop contract is preserved. Seed returned as-is.
        var reader = multiDocValueReader(java.util.Map.of(
            "files.size", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_NUMERIC, false,
                java.util.List.of(100L))
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.size", mapping(EsFieldType.NUMERIC, "long")
        ));
        String seed = "{\"files\":[]}";
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);

        assertEquals(seed, merged, "empty array is not distributed into: " + merged);
    }

    @Test
    void mergeWithDocValues_deepNestingUnderObjectArray_warnsAndDrops() throws IOException {
        // `files[i].meta.size` requires building a nested object inside each List<Map> element
        // from doc_values alone — outside the guarantees the distribute branch makes. The
        // recovered value is dropped with a distinct warn message, seed preserved.
        var reader = multiDocValueReader(java.util.Map.of(
            "files.meta.size", new DocValueFieldSpec(DocValueFieldInfo.DocValueType.SORTED_NUMERIC, false,
                java.util.List.of(100L, 500L))
        ));
        var ctx = contextOfMany(java.util.Map.of(
            "files.meta.size", mapping(EsFieldType.NUMERIC, "long")
        ));
        String seed = "{\"files\":[{\"cksum\":\"h1\"},{\"cksum\":\"h2\"}]}";
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, document(), ctx);

        assertEquals(seed, merged,
            "deep-nesting under a List<Map> parent is unsupported; seed must be unchanged: " + merged);
    }

    @Test
    void mergeWithDocValues_storedFieldSubfield_distributesAcrossObjectArray() throws IOException {
        // Stored-fields path into a List<Map> seed: when a subfield appears as a stored field
        // (not doc_values), it is emitted as a single scalar (LuceneDocument exposes per-doc
        // stored fields). For a multi-element array this is scalar broadcast — each element
        // gets the same stored value. Documents the stored-fields codepath through putNested's
        // new List<Map> branch, separate from the doc_values path.
        var reader = storedOnlyReader();
        var ctx = contextOfMany(java.util.Map.of(
            "files.tstatus", mapping(EsFieldType.NUMERIC, "long"),
            "files.cksum", mapping(EsFieldType.STRING, "keyword")
        ));
        var doc = document(storedNumber("files.tstatus", 1L));
        String seed = "{\"files\":[{\"cksum\":\"h1\"},{\"cksum\":\"h2\"}]}";
        String merged = SourceReconstructor.mergeWithDocValues(seed, reader, 0, doc, ctx);

        JsonNode files = MAPPER.readTree(merged).path("files");
        assertEquals(1L, files.get(0).path("tstatus").asLong(), "element 0 broadcast: " + merged);
        assertEquals(1L, files.get(1).path("tstatus").asLong(), "element 1 broadcast: " + merged);
        assertEquals("h1", files.get(0).path("cksum").asText());
        assertEquals("h2", files.get(1).path("cksum").asText());
    }
}
