package org.opensearch.migrations.bulkload.models;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Version-agnostic, normalized global metadata. Templates are extracted from
 * version-specific JSON paths and returned as simple maps.
 *
 * <p>Normalization guarantees:
 * <ul>
 *   <li>Legacy templates are always a map (empty if absent)
 *   <li>Index templates are always a map (empty for ES &lt; 7.8)
 *   <li>Component templates are always a map (empty for ES &lt; 7.8)
 * </ul>
 */
public record CanonicalGlobalMetadata(
    Map<String, ObjectNode> legacyTemplates,
    Map<String, ObjectNode> indexTemplates,
    Map<String, ObjectNode> componentTemplates
) {
    /**
     * Normalize any version-specific GlobalMetadata into canonical form.
     */
    public static CanonicalGlobalMetadata fromGlobalMetadata(GlobalMetadata source) {
        return new CanonicalGlobalMetadata(
            extractTemplateMap(source.getTemplates()),
            extractTemplateMap(source.getIndexTemplates()),
            extractTemplateMap(source.getComponentTemplates())
        );
    }

    private static Map<String, ObjectNode> extractTemplateMap(ObjectNode node) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        node.fieldNames().forEachRemaining(name -> {
            var value = node.get(name);
            if (value instanceof ObjectNode on) {
                result.put(name, on);
            }
        });
        return result;
    }
}
