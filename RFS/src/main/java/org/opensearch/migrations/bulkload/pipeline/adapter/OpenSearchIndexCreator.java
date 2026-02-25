package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.Iterator;
import java.util.Map;

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

    private static void stripInternalSettings(ObjectNode settings) {
        // After IndexMetadataConverter normalization, the "index." prefix is removed.
        // Handle both normalized (no prefix) and non-normalized (with prefix) settings.
        settings.remove("creation_date");
        settings.remove("provided_name");
        settings.remove("uuid");
        settings.remove("version");
        settings.remove("mapping");
        settings.remove("mapper");

        // Non-normalized: nested under "index" sub-object
        ObjectNodeUtils.removeFieldsByPath(settings, "index.creation_date");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.provided_name");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.uuid");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.version");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.mapping");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.mapper");

        // Flat dotted keys (ES 1.7/2.4)
        settings.remove("index.creation_date");
        settings.remove("index.provided_name");
        settings.remove("index.uuid");
        settings.remove("index.version.created");
        settings.remove("index.mapping.single_type");
        settings.remove("index.mapper.dynamic");
    }
}
