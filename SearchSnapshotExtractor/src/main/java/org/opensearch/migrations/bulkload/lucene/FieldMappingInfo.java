package org.opensearch.migrations.bulkload.lucene;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Holds conversion parameters for a field extracted from index mappings.
 */
public record FieldMappingInfo(
    EsFieldType type,
    String mappingType,      // Original mapping type (e.g., "float", "double", "integer")
    String format,           // For DATE, DATE_NANOS
    Double scalingFactor,    // For SCALED_FLOAT
    boolean docValues,       // Whether doc_values is enabled (default true for most types)
    String constantValue,    // For constant_keyword: the mapping-level "value" parameter
    boolean indexed,         // Whether the field is indexed (default true)
    int positionIncrementGap // Gap inserted between array elements for text fields (default 100)
                             // See https://docs.opensearch.org/latest/mappings/mapping-parameters/position-increment-gap/
) {
    public FieldMappingInfo(EsFieldType type, String mappingType, String format,
            Double scalingFactor, boolean docValues, String constantValue) {
        this(type, mappingType, format, scalingFactor, docValues, constantValue, true, 100);
    }

    public static FieldMappingInfo from(JsonNode fieldMapping) {
        String typeStr = fieldMapping.path("type").asText(null);
        EsFieldType type = EsFieldType.fromMappingType(typeStr);

        String format = fieldMapping.has("format")
            ? fieldMapping.get("format").asText()
            : null;

        Double scalingFactor = fieldMapping.has("scaling_factor")
            ? fieldMapping.get("scaling_factor").asDouble()
            : null;

        // doc_values defaults to true for most field types
        boolean docValues = !fieldMapping.has("doc_values") || fieldMapping.get("doc_values").asBoolean(true);

        // index defaults to true
        boolean indexed = !fieldMapping.has("index") || fieldMapping.get("index").asBoolean(true);

        // position_increment_gap defaults to 100 for text fields
        // https://docs.opensearch.org/latest/mappings/mapping-parameters/position-increment-gap/
        int positionIncrementGap = fieldMapping.has("position_increment_gap")
            ? fieldMapping.get("position_increment_gap").asInt(100)
            : 100;

        // constant_keyword stores its value in the mapping, not in the segment
        String constantValue = "constant_keyword".equals(typeStr) && fieldMapping.has("value")
            ? fieldMapping.get("value").asText()
            : null;

        return new FieldMappingInfo(type, typeStr, format, scalingFactor, docValues, constantValue, indexed, positionIncrementGap);
    }
}
