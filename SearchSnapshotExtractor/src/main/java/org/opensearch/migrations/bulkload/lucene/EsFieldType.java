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

    /** Canonical ES/OS mapping-type names. Use these constants instead of string literals. */
    public static final class MappingTypes {
        public static final String BYTE = "byte";
        public static final String SHORT = "short";
        public static final String INTEGER = "integer";
        public static final String LONG = "long";
        public static final String FLOAT = "float";
        public static final String DOUBLE = "double";
        public static final String HALF_FLOAT = "half_float";
        public static final String TOKEN_COUNT = "token_count";
        public static final String UNSIGNED_LONG = "unsigned_long";
        public static final String SCALED_FLOAT = "scaled_float";
        public static final String BOOLEAN = "boolean";
        public static final String DATE = "date";
        public static final String DATE_NANOS = "date_nanos";
        public static final String IP = "ip";
        public static final String GEO_POINT = "geo_point";
        public static final String KEYWORD = "keyword";
        public static final String TEXT = "text";
        public static final String STRING = "string"; // ES 1.x/2.x type
        public static final String CONSTANT_KEYWORD = "constant_keyword";
        public static final String VERSION = "version";
        public static final String WILDCARD = "wildcard";
        public static final String MATCH_ONLY_TEXT = "match_only_text";
        public static final String FLAT_OBJECT = "flat_object"; // OpenSearch flat_object stores keyword-like
        public static final String BINARY = "binary";
        public static final String INTEGER_RANGE = "integer_range";
        public static final String LONG_RANGE = "long_range";
        public static final String FLOAT_RANGE = "float_range";
        public static final String DOUBLE_RANGE = "double_range";
        public static final String DATE_RANGE = "date_range";
        public static final String IP_RANGE = "ip_range";

        private MappingTypes() {}
    }

    private static final Map<String, EsFieldType> MAPPING_TYPE_TO_FIELD_TYPE = Map.ofEntries(
        Map.entry(MappingTypes.BYTE, NUMERIC),
        Map.entry(MappingTypes.SHORT, NUMERIC),
        Map.entry(MappingTypes.INTEGER, NUMERIC),
        Map.entry(MappingTypes.LONG, NUMERIC),
        Map.entry(MappingTypes.FLOAT, NUMERIC),
        Map.entry(MappingTypes.DOUBLE, NUMERIC),
        Map.entry(MappingTypes.HALF_FLOAT, NUMERIC),
        Map.entry(MappingTypes.TOKEN_COUNT, NUMERIC),
        Map.entry(MappingTypes.UNSIGNED_LONG, UNSIGNED_LONG),
        Map.entry(MappingTypes.SCALED_FLOAT, SCALED_FLOAT),
        Map.entry(MappingTypes.BOOLEAN, BOOLEAN),
        Map.entry(MappingTypes.DATE, DATE),
        Map.entry(MappingTypes.DATE_NANOS, DATE_NANOS),
        Map.entry(MappingTypes.IP, IP),
        Map.entry(MappingTypes.GEO_POINT, GEO_POINT),
        Map.entry(MappingTypes.KEYWORD, STRING),
        Map.entry(MappingTypes.TEXT, STRING),
        Map.entry(MappingTypes.STRING, STRING),
        Map.entry(MappingTypes.CONSTANT_KEYWORD, STRING),
        Map.entry(MappingTypes.VERSION, STRING),
        Map.entry(MappingTypes.WILDCARD, STRING),
        Map.entry(MappingTypes.MATCH_ONLY_TEXT, STRING),
        Map.entry(MappingTypes.FLAT_OBJECT, STRING),
        Map.entry(MappingTypes.BINARY, BINARY),
        Map.entry(MappingTypes.INTEGER_RANGE, UNSUPPORTED),
        Map.entry(MappingTypes.LONG_RANGE, UNSUPPORTED),
        Map.entry(MappingTypes.FLOAT_RANGE, UNSUPPORTED),
        Map.entry(MappingTypes.DOUBLE_RANGE, UNSUPPORTED),
        Map.entry(MappingTypes.DATE_RANGE, UNSUPPORTED),
        Map.entry(MappingTypes.IP_RANGE, UNSUPPORTED)
    );

    public static EsFieldType fromMappingType(String mappingType) {
        if (mappingType == null) {
            return UNSUPPORTED;
        }
        return MAPPING_TYPE_TO_FIELD_TYPE.getOrDefault(mappingType, UNSUPPORTED);
    }
}
