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
    boolean docValues        // Whether doc_values is enabled (default true for most types)
) {
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
            
        return new FieldMappingInfo(type, typeStr, format, scalingFactor, docValues);
    }
}
