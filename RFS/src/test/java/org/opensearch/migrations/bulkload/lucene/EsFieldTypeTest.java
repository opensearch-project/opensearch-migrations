package org.opensearch.migrations.bulkload.lucene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class EsFieldTypeTest {

    @ParameterizedTest
    @CsvSource({
        "byte, NUMERIC",
        "short, NUMERIC",
        "integer, NUMERIC",
        "long, NUMERIC",
        "float, NUMERIC",
        "double, NUMERIC",
        "half_float, NUMERIC",
        "token_count, NUMERIC",
        "unsigned_long, UNSIGNED_LONG",
        "scaled_float, SCALED_FLOAT",
        "boolean, BOOLEAN",
        "date, DATE",
        "date_nanos, DATE_NANOS",
        "ip, IP",
        "geo_point, GEO_POINT",
        "keyword, STRING",
        "text, STRING",
        "string, STRING",
        "constant_keyword, STRING",
        "version, STRING",
        "wildcard, STRING",
        "match_only_text, STRING",
        "binary, BINARY"
    })
    void testFromMappingType(String mappingType, String expectedType) {
        assertEquals(EsFieldType.valueOf(expectedType), EsFieldType.fromMappingType(mappingType));
    }

    @Test
    void testNullMappingTypeReturnsUnsupported() {
        assertEquals(EsFieldType.UNSUPPORTED, EsFieldType.fromMappingType(null));
    }

    @Test
    void testUnknownMappingTypeReturnsUnsupported() {
        assertEquals(EsFieldType.UNSUPPORTED, EsFieldType.fromMappingType("unknown_type"));
        assertEquals(EsFieldType.UNSUPPORTED, EsFieldType.fromMappingType("object"));
        assertEquals(EsFieldType.UNSUPPORTED, EsFieldType.fromMappingType("nested"));
        assertEquals(EsFieldType.UNSUPPORTED, EsFieldType.fromMappingType("geo_shape"));
    }
}
