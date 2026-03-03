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
 * Used by both {@link OpenSearchDocumentSink} and {@link OpenSearchMetadataSink}.
 */
final class OpenSearchIndexCreator {

    private static final String[] INTERNAL_SETTINGS = {
        "creation_date", "provided_name", "uuid", "version", "mapping", "mapper",
        "history.uuid", "verified_before_close",
        "resize.source.name", "resize.source.uuid",
        "routing.allocation.include._tier_preference",
        "routing.allocation.include._name", "routing.allocation.include._id",
        "routing.allocation.require._name", "routing.allocation.require._id",
        "routing.allocation.exclude._name", "routing.allocation.exclude._id",
        "blocks.write", "blocks.read_only", "blocks.read_only_allow_delete",
        "blocks.read", "blocks.metadata",
    };

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

    /** Unwrap single-type mapping wrappers from older ES versions. */
    private static void unwrapTypedMappings(ObjectNode mappings) {
        if (mappings.has("properties")) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = mappings.fields();
        if (fields.hasNext()) {
            var entry = fields.next();
            if (!fields.hasNext() && entry.getValue().isObject() && entry.getValue().has("properties")) {
                ObjectNode inner = (ObjectNode) entry.getValue();
                mappings.remove(entry.getKey());
                inner.fields().forEachRemaining(f -> mappings.set(f.getKey(), f.getValue()));
            }
        }
    }

    /** Recursively convert deprecated "string" field type to "text". */
    private static void convertDeprecatedFieldTypes(JsonNode node) {
        if (!node.isObject()) return;
        ObjectNode obj = (ObjectNode) node;
        if (obj.has("type") && "string".equals(obj.path("type").asText())) {
            obj.put("type", "text");
        }
        obj.fields().forEachRemaining(entry -> convertDeprecatedFieldTypes(entry.getValue()));
    }

    private static void stripInternalSettings(ObjectNode settings) {
        for (String setting : INTERNAL_SETTINGS) {
            settings.remove(setting);
            ObjectNodeUtils.removeFieldsByPath(settings, "index." + setting);
            settings.remove("index." + setting);
        }
        settings.remove("index.version.created");
        settings.remove("index.mapping.single_type");
        settings.remove("index.mapper.dynamic");
    }
}
