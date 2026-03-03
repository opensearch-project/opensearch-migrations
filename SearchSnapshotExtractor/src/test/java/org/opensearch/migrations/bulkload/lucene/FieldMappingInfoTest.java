package org.opensearch.migrations.bulkload.lucene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FieldMappingInfoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testDateFieldWithFormat() {
        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.put("type", "date");
        mapping.put("format", "epoch_millis");

        FieldMappingInfo info = FieldMappingInfo.from(mapping);

        assertEquals(EsFieldType.DATE, info.type());
        assertEquals("date", info.mappingType());
        assertEquals("epoch_millis", info.format());
        assertTrue(info.docValues());
    }

    @Test
    void testScaledFloatWithScalingFactor() {
        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.put("type", "scaled_float");
        mapping.put("scaling_factor", 100.0);

        FieldMappingInfo info = FieldMappingInfo.from(mapping);

        assertEquals(EsFieldType.SCALED_FLOAT, info.type());
        assertEquals(100.0, info.scalingFactor());
    }

    @Test
    void testDocValuesDisabled() {
        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.put("type", "keyword");
        mapping.put("doc_values", false);

        FieldMappingInfo info = FieldMappingInfo.from(mapping);

        assertFalse(info.docValues());
    }

    @Test
    void testDocValuesDefaultsToTrue() {
        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.put("type", "keyword");

        FieldMappingInfo info = FieldMappingInfo.from(mapping);

        assertTrue(info.docValues());
    }

    @Test
    void testGeoPointType() {
        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.put("type", "geo_point");

        FieldMappingInfo info = FieldMappingInfo.from(mapping);

        assertEquals(EsFieldType.GEO_POINT, info.type());
    }

    @Test
    void testDateNanosType() {
        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.put("type", "date_nanos");

        FieldMappingInfo info = FieldMappingInfo.from(mapping);

        assertEquals(EsFieldType.DATE_NANOS, info.type());
    }

    @Test
    void testUnsignedLongType() {
        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.put("type", "unsigned_long");

        FieldMappingInfo info = FieldMappingInfo.from(mapping);

        assertEquals(EsFieldType.UNSIGNED_LONG, info.type());
    }
}
