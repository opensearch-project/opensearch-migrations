package org.opensearch.migrations.bulkload.lucene;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses index mappings once and provides field type info for doc_value conversion.
 */
@Slf4j
public class FieldMappingContext {
    private final Map<String, FieldMappingInfo> fieldMappings = new HashMap<>();

    public FieldMappingContext(JsonNode mappingsNode) {
        if (mappingsNode == null) {
            log.debug("Mappings node is null, no field mappings available");
            return;
        }
        
        log.debug("Parsing mappings node of type: {}", mappingsNode.getNodeType());
        
        // Handle different mapping structures:
        // ES 7+: {"properties": {...}}
        // ES 6 with type: {"_doc": {"properties": {...}}}
        // ES 1-5 multi-type: [{"type1": {"properties": {...}}}, ...]
        
        JsonNode properties = null;
        
        if (mappingsNode.isArray()) {
            // Multi-type mappings (ES 1.x-5.x) - take first type
            if (mappingsNode.size() > 0) {
                JsonNode firstType = mappingsNode.get(0);
                if (firstType.isObject()) {
                    var fields = firstType.fields();
                    if (fields.hasNext()) {
                        var typeEntry = fields.next();
                        log.debug("Using first mapping type: {}", typeEntry.getKey());
                        properties = typeEntry.getValue().path("properties");
                    }
                }
            }
        } else if (mappingsNode.isObject()) {
            // Check for direct properties
            properties = mappingsNode.path("properties");
            if (properties.isMissingNode()) {
                // Check for typed mappings (ES 6.x style: {"_doc": {"properties": {...}}})
                var fields = mappingsNode.fields();
                if (fields.hasNext()) {
                    var typeEntry = fields.next();
                    String typeName = typeEntry.getKey();
                    if (!"properties".equals(typeName)) {
                        log.debug("Using mapping type: {}", typeName);
                        properties = typeEntry.getValue().path("properties");
                    }
                }
            }
        }
        
        if (properties != null && !properties.isMissingNode()) {
            log.debug("Found {} top-level properties", properties.size());
            parseProperties(properties, "");
        } else {
            log.debug("No properties found in mappings");
        }
        
        log.debug("Parsed {} field mappings total", fieldMappings.size());
    }

    private void parseProperties(JsonNode properties, String prefix) {
        properties.fieldNames().forEachRemaining(fieldName -> {
            JsonNode fieldDef = properties.get(fieldName);
            String fullPath = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;
            
            if (fieldDef.has("type")) {
                String type = fieldDef.get("type").asText();
                FieldMappingInfo info = FieldMappingInfo.from(fieldDef);
                fieldMappings.put(fullPath, info);
                log.debug("Field '{}' -> type={}, esType={}", fullPath, type, info.type());
            }
            
            // Handle nested properties
            JsonNode nestedProps = fieldDef.path("properties");
            if (!nestedProps.isMissingNode()) {
                parseProperties(nestedProps, fullPath);
            }
        });
    }

    public FieldMappingInfo getFieldInfo(String fieldName) {
        return fieldMappings.get(fieldName);
    }

    /**
     * Returns all field names in the mapping.
     */
    public java.util.Set<String> getFieldNames() {
        return fieldMappings.keySet();
    }
}
