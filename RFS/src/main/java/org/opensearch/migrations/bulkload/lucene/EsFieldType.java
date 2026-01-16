package org.opensearch.migrations.bulkload.lucene;

import java.util.Map;

/**
 * Elasticsearch/OpenSearch field types grouped by their doc_value conversion logic.
 */
public enum EsFieldType {
    NUMERIC,        // byte, short, integer, long, float, double, half_float, token_count
    UNSIGNED_LONG,  // unsigned_long
    SCALED_FLOAT,   // scaled_float
    BOOLEAN,        // boolean
    DATE,           // date
    DATE_NANOS,     // date_nanos
    IP,             // ip
    GEO_POINT,      // geo_point
    STRING,         // keyword, text, constant_keyword, version, wildcard, match_only_text, flat_object
    BINARY,         // binary
    UNSUPPORTED;    // object, nested, geo_shape, vectors, range types, etc.

    private static final Map<String, EsFieldType> MAPPING_TYPE_TO_FIELD_TYPE = Map.ofEntries(
        // NUMERIC
        Map.entry("byte", NUMERIC),
        Map.entry("short", NUMERIC),
        Map.entry("integer", NUMERIC),
        Map.entry("long", NUMERIC),
        Map.entry("float", NUMERIC),
        Map.entry("double", NUMERIC),
        Map.entry("half_float", NUMERIC),
        Map.entry("token_count", NUMERIC),
        // UNSIGNED_LONG
        Map.entry("unsigned_long", UNSIGNED_LONG),
        // SCALED_FLOAT
        Map.entry("scaled_float", SCALED_FLOAT),
        // BOOLEAN
        Map.entry("boolean", BOOLEAN),
        // DATE
        Map.entry("date", DATE),
        // DATE_NANOS
        Map.entry("date_nanos", DATE_NANOS),
        // IP
        Map.entry("ip", IP),
        // GEO_POINT
        Map.entry("geo_point", GEO_POINT),
        // STRING
        Map.entry("keyword", STRING),
        Map.entry("text", STRING),
        Map.entry("string", STRING),  // ES 1.x/2.x type
        Map.entry("constant_keyword", STRING),
        Map.entry("version", STRING),
        Map.entry("wildcard", STRING),
        Map.entry("match_only_text", STRING),
        Map.entry("flat_object", STRING),  // OpenSearch flat_object stores as keyword-like
        // BINARY
        Map.entry("binary", BINARY),
        // RANGE - stored as binary, complex format - treat as unsupported for now
        Map.entry("integer_range", UNSUPPORTED),
        Map.entry("long_range", UNSUPPORTED),
        Map.entry("float_range", UNSUPPORTED),
        Map.entry("double_range", UNSUPPORTED),
        Map.entry("date_range", UNSUPPORTED),
        Map.entry("ip_range", UNSUPPORTED)
    );

    public static EsFieldType fromMappingType(String mappingType) {
        if (mappingType == null) {
            return UNSUPPORTED;
        }
        return MAPPING_TYPE_TO_FIELD_TYPE.getOrDefault(mappingType, UNSUPPORTED);
    }
}
