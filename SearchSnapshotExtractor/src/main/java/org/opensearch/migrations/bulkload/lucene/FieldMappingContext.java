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
    private static final String PROPERTIES = "properties";
    private final Map<String, FieldMappingInfo> fieldMappings = new HashMap<>();

    public FieldMappingContext(JsonNode mappingsNode) {
        if (mappingsNode == null) {
            log.debug("Mappings node is null, no field mappings available");
            return;
        }
        
        log.debug("Parsing mappings node of type: {}", mappingsNode.getNodeType());
        JsonNode properties = extractProperties(mappingsNode);
        
        if (properties != null && !properties.isMissingNode()) {
            log.debug("Found {} top-level properties", properties.size());
            parseProperties(properties, "");
        } else {
            log.debug("No properties found in mappings");
        }
        
        log.debug("Parsed {} field mappings total", fieldMappings.size());
    }

    private JsonNode extractProperties(JsonNode mappingsNode) {
        if (mappingsNode.isArray()) {
            return extractPropertiesFromArray(mappingsNode);
        }
        if (mappingsNode.isObject()) {
            return extractPropertiesFromObject(mappingsNode);
        }
        return null;
    }

    private JsonNode extractPropertiesFromArray(JsonNode mappingsNode) {
        // Multi-type mappings (ES 1.x-5.x) - take first type
        if (mappingsNode.size() == 0) {
            return null;
        }
        JsonNode firstType = mappingsNode.get(0);
        if (!firstType.isObject()) {
            return null;
        }
        var fields = firstType.fields();
        if (!fields.hasNext()) {
            return null;
        }
        var typeEntry = fields.next();
        log.debug("Using first mapping type: {}", typeEntry.getKey());
        return typeEntry.getValue().path(PROPERTIES);
    }

    private JsonNode extractPropertiesFromObject(JsonNode mappingsNode) {
        // Check for direct properties
        JsonNode properties = mappingsNode.path(PROPERTIES);
        if (!properties.isMissingNode()) {
            return properties;
        }
        // Check for typed mappings (ES 6.x style: {"_doc": {"properties": {...}}})
        var fields = mappingsNode.fields();
        if (!fields.hasNext()) {
            return null;
        }
        var typeEntry = fields.next();
        String typeName = typeEntry.getKey();
        if (PROPERTIES.equals(typeName)) {
            return null;
        }
        log.debug("Using mapping type: {}", typeName);
        return typeEntry.getValue().path(PROPERTIES);
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
            JsonNode nestedProps = fieldDef.path(PROPERTIES);
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
