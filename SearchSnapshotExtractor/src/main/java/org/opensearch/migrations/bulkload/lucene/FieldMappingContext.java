package org.opensearch.migrations.bulkload.lucene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses index mappings once and provides field type info for doc_value conversion.
 * Also tracks {@code copy_to} destinations and {@code _source} include/exclude globs so
 * the reconstructor can suppress fields that never existed in the origin {@code _source}.
 */
@Slf4j
public class FieldMappingContext {
    private static final String PROPERTIES = "properties";
    private static final String SOURCE = "_source";
    private final Map<String, FieldMappingInfo> fieldMappings = new HashMap<>();
    // Targets of some other field's copy_to. ES never writes copy_to output into _source,
    // so reconstruction must treat these paths as absent.
    private final Set<String> copyToDestinations = new HashSet<>();
    // _source.includes / _source.excludes glob patterns. This implements a pragmatic subset
    // of ES source-filter glob semantics sufficient for the common cases:
    //   "*"              -> matches every path
    //   "<prefix>.*"     -> matches any path starting with "<prefix>."
    //   "<prefix>.**"    -> same as "<prefix>.*" for our purposes
    //   "<literal.path>" -> exact match
    // Mid-string '*' / '?' fall back to literal match with a debug log so they do not
    // silently drop matching paths.
    private final List<String> sourceIncludes = new ArrayList<>();
    private final List<String> sourceExcludes = new ArrayList<>();

    public FieldMappingContext(JsonNode mappingsNode) {
        if (mappingsNode == null) {
            log.debug("Mappings node is null, no field mappings available");
            return;
        }

        log.debug("Parsing mappings node of type: {}", mappingsNode.getNodeType());
        JsonNode container = extractMappingContainer(mappingsNode);
        if (container == null || !container.isObject()) {
            log.debug("No mapping container found");
            return;
        }

        parseSourceFilter(container.path(SOURCE));

        JsonNode properties = container.path(PROPERTIES);
        if (!properties.isMissingNode() && properties.isObject()) {
            log.debug("Found {} top-level properties", properties.size());
            parseProperties(properties, "");
        } else {
            log.debug("No properties found in mappings");
        }

        log.debug("Parsed {} field mappings, {} copy_to destinations, {} include/exclude rules",
            fieldMappings.size(), copyToDestinations.size(),
            sourceIncludes.size() + sourceExcludes.size());
    }

    private JsonNode extractMappingContainer(JsonNode mappingsNode) {
        if (mappingsNode.isArray()) {
            return extractContainerFromArray(mappingsNode);
        }
        if (mappingsNode.isObject()) {
            return extractContainerFromObject(mappingsNode);
        }
        return null;
    }

    private JsonNode extractContainerFromArray(JsonNode mappingsNode) {
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
        return typeEntry.getValue();
    }

    private JsonNode extractContainerFromObject(JsonNode mappingsNode) {
        // Direct style (ES 7+): root has "properties" as a direct child.
        if (!mappingsNode.path(PROPERTIES).isMissingNode()) {
            return mappingsNode;
        }
        // Typed mappings (ES 6.x style: {"_doc": {"properties": {...}}})
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
        return typeEntry.getValue();
    }

    private void parseSourceFilter(JsonNode sourceNode) {
        if (sourceNode.isMissingNode() || !sourceNode.isObject()) {
            return;
        }
        collectStrings(sourceNode.path("includes"), sourceIncludes::add);
        collectStrings(sourceNode.path("excludes"), sourceExcludes::add);
    }

    private static void collectStrings(JsonNode node, Consumer<String> sink) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            sink.accept(node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> {
                if (n.isTextual()) {
                    sink.accept(n.asText());
                }
            });
        }
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

            collectStrings(fieldDef.path("copy_to"), copyToDestinations::add);

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

    /**
     * Returns {@code true} if the given path should be suppressed from a reconstructed
     * {@code _source}, because it is either (a) a {@code copy_to} destination — ES never
     * writes copy_to output back into {@code _source}, (b) matches a {@code _source.excludes}
     * glob, or (c) {@code _source.includes} is set and the path matches no include glob.
     * Excludes take precedence over includes (ES semantics). Cheap no-op when the mapping
     * declared neither {@code copy_to} nor a {@code _source} filter.
     */
    public boolean isSourceExcluded(String fieldPath) {
        if (copyToDestinations.isEmpty() && sourceIncludes.isEmpty() && sourceExcludes.isEmpty()) {
            return false;
        }
        if (copyToDestinations.contains(fieldPath)) {
            return true;
        }
        for (String glob : sourceExcludes) {
            if (matchesGlob(glob, fieldPath)) {
                return true;
            }
        }
        if (!sourceIncludes.isEmpty()) {
            for (String glob : sourceIncludes) {
                if (matchesGlob(glob, fieldPath)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean matchesGlob(String glob, String path) {
        if ("*".equals(glob)) {
            return true;
        }
        if (glob.endsWith(".**")) {
            return path.startsWith(glob.substring(0, glob.length() - 2));
        }
        if (glob.endsWith(".*")) {
            return path.startsWith(glob.substring(0, glob.length() - 1));
        }
        if (glob.indexOf('*') >= 0 || glob.indexOf('?') >= 0) {
            log.debug("Unsupported source-filter glob '{}', treating as literal", glob);
        }
        return path.equals(glob);
    }
}
