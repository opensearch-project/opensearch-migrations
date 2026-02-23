package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.Set;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.parsing.ObjectNodeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared helper for creating an index on an OpenSearch cluster from pipeline IR metadata.
 * Used by both {@link OpenSearchDocumentSink} and {@link OpenSearchMetadataSink}
 * to avoid duplicating the body-building logic.
 */
final class OpenSearchIndexCreator {

    // Flat dotted keys (for flat settings from ES 1.7/2.4 where keys are literal strings like "index.creation_date").
    private static final String[] FLAT_SETTINGS = {
        "index.creation_date", "index.provided_name", "index.uuid", "index.version.created",
        "index.mapping.single_type", "index.mapper.dynamic"
    };

    // Known top-level mapping keywords that are NOT type names.
    private static final Set<String> MAPPING_KEYWORDS = Set.of(
        "properties", "_source", "_routing", "_meta", "dynamic", "enabled",
        "date_detection", "dynamic_date_formats", "dynamic_templates", "numeric_detection",
        "_all", "_field_names", "_size"
    );

    private OpenSearchIndexCreator() {}

    static void createIndex(OpenSearchClient client, IndexMetadataSnapshot metadata, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        if (metadata.mappings() != null) {
            ObjectNode mappings = stripTypeMappings(metadata.mappings().deepCopy());
            upgradeDeprecatedFieldTypes(mappings);
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

    private static void stripInternalSettings(ObjectNode settings) {
        // Handle tree-structured settings (nested under "index" sub-object)
        ObjectNodeUtils.removeFieldsByPath(settings, "index.creation_date");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.provided_name");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.uuid");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.version");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.mapping.single_type");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.mapper.dynamic");

        // Handle flat dotted keys (e.g. ES 1.7/2.4 where the key is literally "index.creation_date")
        for (var key : FLAT_SETTINGS) {
            settings.remove(key);
        }
    }

    /**
     * Unwraps single-type mapping wrappers from older ES versions.
     * E.g. {@code {"doc": {"properties": {...}}}} → {@code {"properties": {...}}}
     */
    static ObjectNode stripTypeMappings(ObjectNode mappings) {
        if (mappings.size() != 1) {
            return mappings;
        }
        var fieldName = mappings.fieldNames().next();
        if (MAPPING_KEYWORDS.contains(fieldName)) {
            return mappings;
        }
        var inner = mappings.get(fieldName);
        if (inner != null && inner.isObject()) {
            return (ObjectNode) inner;
        }
        return mappings;
    }

    /**
     * Converts deprecated ES field types to their modern equivalents.
     * E.g. {@code "type": "string"} → {@code "type": "text"} (removed in ES 5.0).
     */
    private static void upgradeDeprecatedFieldTypes(ObjectNode node) {
        for (var entry : node.properties()) {
            var value = entry.getValue();
            if (value.isObject()) {
                var obj = (ObjectNode) value;
                var typeNode = obj.get("type");
                if (typeNode != null && "string".equals(typeNode.asText())) {
                    obj.put("type", "text");
                }
                upgradeDeprecatedFieldTypes(obj);
            }
        }
    }
}
