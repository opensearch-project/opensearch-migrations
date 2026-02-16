package org.opensearch.migrations.bulkload.models;

import java.util.Iterator;

import org.opensearch.migrations.bulkload.transformers.TransformFunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Version-agnostic, normalized index metadata. Every source version normalizes into this
 * canonical form so downstream consumers (transformers, writers) never need to know the
 * source version.
 *
 * <p>Normalization guarantees:
 * <ul>
 *   <li>Settings are always in tree form ({@code {"index":{"number_of_shards":"2"}}})
 *   <li>Mappings have no type wrapper and no array — just the mapping body
 *   <li>Aliases are always an ObjectNode (empty if absent)
 * </ul>
 */
public record CanonicalIndexMetadata(
    String name,
    String id,
    int numberOfShards,
    int numberOfReplicas,
    ObjectNode settings,
    ObjectNode mappings,
    ObjectNode aliases
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Normalize any version-specific IndexMetadata into canonical form.
     */
    public static CanonicalIndexMetadata fromIndexMetadata(IndexMetadata source) {
        ObjectNode settings = normalizeSettings(source.getSettings());
        // Access mappings from raw JSON to avoid ClassCastException in some version-specific implementations
        ObjectNode mappings = normalizeMappings(source.getRawJson().get("mappings"));
        ObjectNode aliases = normalizeAliases(source.getAliases());

        int shards = extractShardCount(settings, source);
        int replicas = extractReplicaCount(settings);

        return new CanonicalIndexMetadata(
            source.getName(),
            source.getId(),
            shards,
            replicas,
            settings,
            mappings,
            aliases
        );
    }

    private static ObjectNode normalizeSettings(JsonNode settingsNode) {
        if (settingsNode == null || !settingsNode.isObject()) {
            return MAPPER.createObjectNode();
        }
        ObjectNode settings = (ObjectNode) settingsNode.deepCopy();

        // Detect flat settings by checking if any key contains a dot
        if (hasFlatKeys(settings)) {
            return TransformFunctions.convertFlatSettingsToTree(settings);
        }
        return settings;
    }

    private static boolean hasFlatKeys(ObjectNode node) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (names.next().contains(".")) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode normalizeMappings(JsonNode mappingsNode) {
        if (mappingsNode == null) {
            return MAPPER.createObjectNode();
        }
        // Handle array form (ES 1.7)
        if (mappingsNode.isArray()) {
            if (mappingsNode.isEmpty()) {
                return MAPPER.createObjectNode();
            }
            mappingsNode = mappingsNode.get(0);
        }
        if (!mappingsNode.isObject()) {
            return MAPPER.createObjectNode();
        }
        // Strip type wrapper (e.g., {"_doc": {"properties": ...}} → {"properties": ...})
        return TransformFunctions.getMappingsFromBeneathIntermediate((ObjectNode) mappingsNode.deepCopy());
    }

    private static ObjectNode normalizeAliases(JsonNode aliasesNode) {
        if (aliasesNode instanceof ObjectNode on) {
            return on.deepCopy();
        }
        return MAPPER.createObjectNode();
    }

    private static int extractShardCount(ObjectNode treeSettings, IndexMetadata source) {
        // Try tree form first: settings.index.number_of_shards
        JsonNode indexNode = treeSettings.path("index");
        if (indexNode.has("number_of_shards")) {
            return indexNode.get("number_of_shards").asInt();
        }
        // Fallback to source's own method
        return source.getNumberOfShards();
    }

    private static int extractReplicaCount(ObjectNode treeSettings) {
        JsonNode indexNode = treeSettings.path("index");
        if (indexNode.has("number_of_replicas")) {
            return indexNode.get("number_of_replicas").asInt();
        }
        return 0;
    }
}
