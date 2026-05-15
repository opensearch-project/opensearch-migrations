package org.opensearch.migrations.bulkload.solr;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Converts Solr schema definitions to OpenSearch-compatible mappings.
 * Handles explicit fields, dynamic fields, copyFields, fieldType class resolution,
 * and date format mapping.
 */
@Slf4j
public final class SolrSchemaConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OS_INTEGER = "integer";
    private static final String OS_LONG = "long";
    private static final String OS_FLOAT = "float";
    private static final String OS_DOUBLE = "double";
    private static final String OS_DATE = "date";
    private static final String OS_BOOLEAN = "boolean";
    private static final String OS_KEYWORD = "keyword";
    private static final String OS_TEXT = "text";
    private static final String OS_BINARY = "binary";
    private static final String OS_FORMAT_FIELD = "format";

    /** Maps Solr field type names to OpenSearch types. */
    private static final Map<String, String> SOLR_TO_OS_TYPE = Map.ofEntries(
        Map.entry("string", OS_KEYWORD),
        Map.entry("strings", OS_KEYWORD),
        Map.entry("text_general", OS_TEXT),
        Map.entry("text_en", OS_TEXT),
        Map.entry("text_ws", OS_TEXT),
        Map.entry("text", OS_TEXT),
        Map.entry("pint", OS_INTEGER),
        Map.entry("pints", OS_INTEGER),
        Map.entry("int", OS_INTEGER),
        Map.entry("tint", OS_INTEGER),
        Map.entry("plong", OS_LONG),
        Map.entry("plongs", OS_LONG),
        Map.entry("long", OS_LONG),
        Map.entry("tlong", OS_LONG),
        Map.entry("pfloat", OS_FLOAT),
        Map.entry("pfloats", OS_FLOAT),
        Map.entry(OS_FLOAT, OS_FLOAT),
        Map.entry("tfloat", OS_FLOAT),
        Map.entry("pdouble", OS_DOUBLE),
        Map.entry("pdoubles", OS_DOUBLE),
        Map.entry(OS_DOUBLE, OS_DOUBLE),
        Map.entry("tdouble", OS_DOUBLE),
        Map.entry("pdate", OS_DATE),
        Map.entry("pdates", OS_DATE),
        Map.entry("date", OS_DATE),
        Map.entry("tdate", OS_DATE),
        Map.entry(OS_BOOLEAN, OS_BOOLEAN),
        Map.entry("booleans", OS_BOOLEAN),
        Map.entry(OS_BINARY, OS_BINARY)
    );

    /** Maps Solr fieldType Java class names to OpenSearch types (fallback when type name isn't in SOLR_TO_OS_TYPE). */
    private static final Map<String, String> SOLR_CLASS_TO_OS_TYPE = Map.ofEntries(
        Map.entry("solr.StrField", OS_KEYWORD),
        Map.entry("solr.TextField", OS_TEXT),
        Map.entry("solr.BoolField", OS_BOOLEAN),
        Map.entry("solr.IntPointField", OS_INTEGER),
        Map.entry("solr.LongPointField", OS_LONG),
        Map.entry("solr.FloatPointField", OS_FLOAT),
        Map.entry("solr.DoublePointField", OS_DOUBLE),
        Map.entry("solr.DatePointField", OS_DATE),
        Map.entry("solr.TrieIntField", OS_INTEGER),
        Map.entry("solr.TrieLongField", OS_LONG),
        Map.entry("solr.TrieFloatField", OS_FLOAT),
        Map.entry("solr.TrieDoubleField", OS_DOUBLE),
        Map.entry("solr.TrieDateField", OS_DATE),
        Map.entry("solr.BinaryField", OS_BINARY),
        Map.entry("solr.UUIDField", OS_KEYWORD)
    );

    /** Date format for OpenSearch — accepts both ISO 8601 and epoch millis. */
    static final String OS_DATE_FORMAT = "strict_date_optional_time||epoch_millis";

    private SolrSchemaConverter() {}

    /**
     * Convert Solr schema fields array to OpenSearch mappings ObjectNode.
     * Simple overload for backward compatibility — no dynamic fields or fieldTypes.
     */
    public static ObjectNode convertToOpenSearchMappings(JsonNode solrFields) {
        return convertToOpenSearchMappings(solrFields, null, null, null);
    }

    /**
     * Convert a full Solr schema to OpenSearch mappings, including dynamic fields,
     * copyFields, and fieldType class resolution.
     *
     * @param solrFields      the "fields" array from the Solr schema
     * @param dynamicFields   the "dynamicFields" array (nullable)
     * @param copyFields      the "copyFields" array (nullable)
     * @param fieldTypes      the "fieldTypes" array (nullable) — used for class-based type resolution
     */
    public static ObjectNode convertToOpenSearchMappings(
        JsonNode solrFields, JsonNode dynamicFields, JsonNode copyFields, JsonNode fieldTypes
    ) {
        var typeClassMap = buildFieldTypeClassMap(fieldTypes);

        ObjectNode mappings = MAPPER.createObjectNode();
        ObjectNode properties = MAPPER.createObjectNode();

        processExplicitFields(solrFields, typeClassMap, properties);
        var dynamicTemplates = processDynamicFields(dynamicFields, typeClassMap);
        processCopyFields(copyFields, dynamicFields, typeClassMap, properties);

        mappings.set("properties", properties);
        if (!dynamicTemplates.isEmpty()) {
            mappings.set("dynamic_templates", dynamicTemplates);
        }
        return mappings;
    }

    private static void processExplicitFields(JsonNode solrFields, Map<String, String> typeClassMap, ObjectNode properties) {
        if (solrFields == null || !solrFields.isArray()) {
            return;
        }
        for (var field : solrFields) {
            var name = field.path("name").asText();
            var type = field.path("type").asText();
            if (isInternalField(name)) {
                continue;
            }
            properties.set(name, resolveFieldMapping(type, typeClassMap));
        }
    }

    private static ArrayNode processDynamicFields(JsonNode dynamicFields, Map<String, String> typeClassMap) {
        var dynamicTemplates = MAPPER.createArrayNode();
        if (dynamicFields == null || !dynamicFields.isArray()) {
            return dynamicTemplates;
        }
        for (var dynField : dynamicFields) {
            var pattern = dynField.path("name").asText();
            var type = dynField.path("type").asText();
            if (pattern.isEmpty() || type.isEmpty()) {
                continue;
            }
            var osType = resolveOsType(type, typeClassMap);
            var template = buildDynamicTemplate(pattern, osType);
            if (template != null) {
                dynamicTemplates.add(template);
            }
        }
        return dynamicTemplates;
    }

    private static void processCopyFields(
        JsonNode copyFields, JsonNode dynamicFields, Map<String, String> typeClassMap, ObjectNode properties
    ) {
        if (copyFields == null || !copyFields.isArray()) {
            return;
        }
        for (var cf : copyFields) {
            var dest = cf.path("dest").asText();
            if (!dest.isEmpty() && !properties.has(dest) && !isInternalField(dest)) {
                // Try to resolve the dest field's type from dynamic field patterns
                var osType = resolveDynamicFieldType(dest, dynamicFields, typeClassMap);
                if (osType == null) {
                    osType = OS_TEXT;
                }
                var fieldMapping = MAPPER.createObjectNode();
                fieldMapping.put("type", osType);
                if (OS_DATE.equals(osType)) {
                    fieldMapping.put(OS_FORMAT_FIELD, OS_DATE_FORMAT);
                }
                properties.set(dest, fieldMapping);
            }
        }
    }

    /**
     * Resolve the OpenSearch type for a field name by matching it against dynamic field patterns.
     * Returns null if no dynamic field pattern matches.
     */
    private static String resolveDynamicFieldType(String fieldName, JsonNode dynamicFields, Map<String, String> typeClassMap) {
        if (dynamicFields == null || !dynamicFields.isArray()) {
            return null;
        }
        for (var dynField : dynamicFields) {
            var pattern = dynField.path("name").asText();
            var type = dynField.path("type").asText();
            if (pattern.isEmpty() || type.isEmpty()) {
                continue;
            }
            if (matchesDynamicPattern(fieldName, pattern)) {
                return resolveOsType(type, typeClassMap);
            }
        }
        return null;
    }

    /**
     * Check if a field name matches a Solr dynamic field pattern.
     * Patterns are either prefix (e.g., "attr_*") or suffix (e.g., "*_s").
     */
    private static boolean matchesDynamicPattern(String fieldName, String pattern) {
        if (pattern.startsWith("*") && pattern.length() > 1) {
            return fieldName.endsWith(pattern.substring(1));
        } else if (pattern.endsWith("*") && pattern.length() > 1) {
            return fieldName.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return false;
    }

    private static ObjectNode resolveFieldMapping(String solrType, Map<String, String> typeClassMap) {
        var osType = resolveOsType(solrType, typeClassMap);
        ObjectNode fieldMapping = MAPPER.createObjectNode();
        fieldMapping.put("type", osType);
        if (OS_DATE.equals(osType)) {
            fieldMapping.put(OS_FORMAT_FIELD, OS_DATE_FORMAT);
        }
        return fieldMapping;
    }

    static String resolveOsType(String solrType, Map<String, String> typeClassMap) {
        // First: direct type name lookup
        var osType = SOLR_TO_OS_TYPE.get(solrType);
        if (osType != null) {
            return osType;
        }
        // Second: resolve via fieldType class
        if (typeClassMap != null) {
            var className = typeClassMap.get(solrType);
            if (className != null) {
                osType = SOLR_CLASS_TO_OS_TYPE.get(className);
                if (osType != null) {
                    return osType;
                }
                // Check if class name contains known patterns
                for (var entry : SOLR_CLASS_TO_OS_TYPE.entrySet()) {
                    if (className.endsWith(entry.getKey().substring(entry.getKey().lastIndexOf('.')))) {
                        return entry.getValue();
                    }
                }
            }
        }
        // Fallback
        return OS_TEXT;
    }

    private static Map<String, String> buildFieldTypeClassMap(JsonNode fieldTypes) {
        if (fieldTypes == null || !fieldTypes.isArray()) {
            return Map.of();
        }
        var map = new LinkedHashMap<String, String>();
        for (var ft : fieldTypes) {
            var name = ft.path("name").asText();
            var className = ft.path("class").asText();
            if (!name.isEmpty() && !className.isEmpty()) {
                map.put(name, className);
            }
        }
        return map;
    }

    /**
     * Build an OpenSearch dynamic_template from a Solr dynamic field pattern
     * ({@code "*_s"} suffix or {@code "attr_*"} prefix).
     *
     * <p>Uses {@code path_match} + {@code match_mapping_type} (not plain {@code match}).
     * When a Solr doc field has dots in its name (e.g. {@code attr_field.withdot})
     * OpenSearch auto-expands it into a nested object on write. With plain {@code match},
     * the {@code attr_*} template would also fire on the synthesized parent
     * ({@code attr_field}), type it as the target type, and then reject the child key
     * with {@code mapper_parsing_exception}. {@code match_mapping_type} gates on the
     * JSON value shape, which intermediate object containers never carry.
     */
    static ObjectNode buildDynamicTemplate(String solrPattern, String osType) {
        String matchPattern;
        String templateName;

        if (solrPattern.startsWith("*")) {
            // Suffix pattern: *_s → match anything ending with _s
            matchPattern = "*" + solrPattern.substring(1);
            templateName = "solr_dyn_" + solrPattern.substring(1).replaceAll("[^a-zA-Z0-9]", "_");
        } else if (solrPattern.endsWith("*")) {
            // Prefix pattern: attr_* → match anything starting with attr_
            matchPattern = solrPattern;
            templateName = "solr_dyn_" + solrPattern.substring(0, solrPattern.length() - 1).replaceAll("[^a-zA-Z0-9]", "_");
        } else {
            return null; // Not a valid dynamic field pattern
        }

        var template = MAPPER.createObjectNode();
        var inner = MAPPER.createObjectNode();
        inner.put("path_match", matchPattern);
        var jsonShape = osTypeToMatchMappingType(osType);
        if (jsonShape != null) {
            inner.put("match_mapping_type", jsonShape);
        }
        var mapping = MAPPER.createObjectNode();
        mapping.put("type", osType);
        if (OS_DATE.equals(osType)) {
            mapping.put(OS_FORMAT_FIELD, OS_DATE_FORMAT);
        }
        inner.set("mapping", mapping);
        template.set(templateName, inner);
        return template;
    }

    /**
     * Map an OpenSearch field type to its {@code match_mapping_type} JSON-shape token,
     * or {@code null} when no shape can be confidently asserted. Solr-extracted dates
     * arrive as epoch-millis longs, so date templates gate on {@code long}.
     *
     * <p>{@code osType} is always non-null in practice ({@link #resolveOsType} falls
     * back to {@code OS_TEXT}), so no defensive null guard is needed.
     */
    private static String osTypeToMatchMappingType(String osType) {
        switch (osType) {
            case OS_INTEGER:
            case OS_LONG:
            case OS_DATE:
                return OS_LONG;
            case OS_FLOAT:
            case OS_DOUBLE:
                return OS_DOUBLE;
            case OS_BOOLEAN:
                return OS_BOOLEAN;
            case OS_KEYWORD:
            case OS_TEXT:
                return "string";
            default:
                return null;
        }
    }

    private static boolean isInternalField(String name) {
        return name.startsWith("_") && !"id".equals(name);
    }
}
