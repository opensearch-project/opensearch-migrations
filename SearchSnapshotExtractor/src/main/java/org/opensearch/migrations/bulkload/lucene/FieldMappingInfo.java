package org.opensearch.migrations.bulkload.lucene;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Holds conversion parameters for a field extracted from index mappings.
 */
public record FieldMappingInfo(
    EsFieldType type,
    String mappingType,      // Original mapping type (e.g., "float", "double", "integer")
    String format,           // For DATE, DATE_NANOS
    Double scalingFactor     // For SCALED_FLOAT
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
            
        return new FieldMappingInfo(type, typeStr, format, scalingFactor);
    }
}
