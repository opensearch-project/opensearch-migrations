package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.parsing.ObjectNodeUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared helper for creating an index on an OpenSearch cluster from pipeline IR metadata.
 * Used by both {@link OpenSearchDocumentSink} and {@link OpenSearchMetadataSink}
 * to avoid duplicating the body-building logic.
 *
 * <p>Strips internal settings, unwraps typed mapping wrappers, and converts deprecated
 * field types (e.g. ES 1.7 "string" → "text") so that snapshots from any supported
 * source version can be applied to modern OpenSearch targets.
 */
final class OpenSearchIndexCreator {

    private OpenSearchIndexCreator() {}

    static void createIndex(OpenSearchClient client, IndexMetadataSnapshot metadata, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        if (metadata.mappings() != null) {
            ObjectNode mappings = metadata.mappings().deepCopy();
            unwrapTypedMappings(mappings);
            convertDeprecatedFieldTypes(mappings);
            body.set("mappings", mappings);
        }
        if (metadata.settings() != null) {
            ObjectNode settings = metadata.settings().deepCopy();
            stripInternalSettings(settings);
            body.set("settings", settings);
        }
        if (metadata.aliases() != null) {
            body.set("aliases", metadata.aliases());
        }
        client.createIndex(metadata.indexName(), body, null);
    }

    /**
     * Unwrap single-type mapping wrappers from older ES versions.
     * e.g. {"doc": {"properties": {...}}} → {"properties": {...}}
     */
    private static void unwrapTypedMappings(ObjectNode mappings) {
        if (mappings.has("properties")) {
            return; // Already unwrapped
        }
        // Check if there's a single type wrapper
        Iterator<Map.Entry<String, JsonNode>> fields = mappings.fields();
        if (fields.hasNext()) {
            var entry = fields.next();
            if (!fields.hasNext() && entry.getValue().isObject() && entry.getValue().has("properties")) {
                // Single type wrapper — unwrap it
                ObjectNode inner = (ObjectNode) entry.getValue();
                mappings.remove(entry.getKey());
                inner.fields().forEachRemaining(f -> mappings.set(f.getKey(), f.getValue()));
            }
        }
    }

    /**
     * Recursively convert deprecated field types to modern equivalents.
     * ES 1.7/2.x "string" → "text" (OpenSearch doesn't support "string").
     */
    private static void convertDeprecatedFieldTypes(JsonNode node) {
        if (!node.isObject()) {
            return;
        }
        ObjectNode obj = (ObjectNode) node;
        if (obj.has("type") && "string".equals(obj.path("type").asText())) {
            obj.put("type", "text");
        }
        obj.fields().forEachRemaining(entry -> convertDeprecatedFieldTypes(entry.getValue()));
    }

    /**
     * Prefixes of internal settings that should be stripped by pattern.
     * Any setting starting with these prefixes (after optional "index." prefix) is removed.
     * This catches new sub-keys added in future ES/OS versions.
     */
    private static final Set<String> INTERNAL_SETTING_PREFIXES = Set.of(
        "version.",       // index.version.created, index.version.upgraded, etc.
        "blocks.",        // index.blocks.write, index.blocks.read_only, etc.
        "resize.",        // index.resize.source.name, index.resize.source.uuid
        "routing.allocation.include.",
        "routing.allocation.require.",
        "routing.allocation.exclude."
    );

    /**
     * Exact internal settings to strip (after optional "index." prefix).
     */
    private static final Set<String> INTERNAL_SETTINGS_EXACT = Set.of(
        "creation_date", "provided_name", "uuid", "version", "mapping", "mapper",
        "history.uuid", "verified_before_close"
    );

    private static void stripInternalSettings(ObjectNode settings) {
        // Strip settings that are internal to the source cluster and should not be
        // applied to the target. Uses both exact matches and prefix patterns to
        // catch new internal settings added in future ES/OS versions.

        // Collect keys to remove (flat dotted keys at top level)
        var keysToRemove = new ArrayList<String>();
        settings.fieldNames().forEachRemaining(key -> {
            String normalized = key.startsWith("index.") ? key.substring(6) : key;
            if (isInternalSetting(normalized)) {
                keysToRemove.add(key);
            }
        });
        keysToRemove.forEach(settings::remove);

        // Also strip from nested "index" sub-object (non-normalized form)
        JsonNode indexNode = settings.get("index");
        if (indexNode != null && indexNode.isObject()) {
            var nestedKeysToRemove = new ArrayList<String>();
            collectInternalKeys((ObjectNode) indexNode, "", nestedKeysToRemove);
            for (String path : nestedKeysToRemove) {
                ObjectNodeUtils.removeFieldsByPath((ObjectNode) indexNode, path);
            }
        }
    }

    private static boolean isInternalSetting(String key) {
        if (INTERNAL_SETTINGS_EXACT.contains(key)) {
            return true;
        }
        for (String prefix : INTERNAL_SETTING_PREFIXES) {
            if (key.startsWith(prefix) || key.equals(prefix.substring(0, prefix.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recursively collect paths to internal settings within a nested object.
     */
    private static void collectInternalKeys(ObjectNode node, String pathPrefix, List<String> keysToRemove) {
        node.fieldNames().forEachRemaining(key -> {
            String fullPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            if (isInternalSetting(fullPath)) {
                keysToRemove.add(fullPath);
            } else if (node.get(key).isObject()) {
                collectInternalKeys((ObjectNode) node.get(key), fullPath, keysToRemove);
            }
        });
    }
}
