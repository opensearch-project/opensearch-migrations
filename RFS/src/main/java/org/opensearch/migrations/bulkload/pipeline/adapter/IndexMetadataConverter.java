package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.Set;

import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.transformers.TransformFunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Converts existing {@link IndexMetadata} to the clean pipeline IR.
 * Applies structural normalization: strips type wrappers, normalizes settings.
 */
@Slf4j
final class IndexMetadataConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SETTINGS_KEY = "settings";

    private static final Set<String> MAPPING_KEYWORDS = Set.of(
        "properties", "_source", "_routing", "_meta", "dynamic", "enabled",
        "date_detection", "dynamic_date_formats", "dynamic_templates", "numeric_detection",
        "_all", "_field_names", "_size"
    );

    private IndexMetadataConverter() {}

    static IndexMetadataSnapshot convert(String indexName, IndexMetadata meta) {
        ObjectNode mappings = safeGetObjectNode(meta::getMappings, "mappings", indexName);
        ObjectNode settings = safeGetObjectNode(meta::getSettings, SETTINGS_KEY, indexName);

        if (mappings != null) {
            mappings = stripTypeMappings(mappings.deepCopy());
        }
        if (settings != null) {
            settings = normalizeSettings(settings.deepCopy());
        }

        return new IndexMetadataSnapshot(
            indexName,
            meta.getNumberOfShards(),
            safeGetReplicas(meta),
            mappings,
            settings,
            safeGetObjectNode(meta::getAliases, "aliases", indexName)
        );
    }

    static ObjectNode stripTypeMappings(ObjectNode mappings) {
        if (mappings.size() != 1) return mappings;
        var fieldName = mappings.fieldNames().next();
        if (MAPPING_KEYWORDS.contains(fieldName)) return mappings;
        var inner = mappings.get(fieldName);
        if (inner != null && inner.isObject()) return (ObjectNode) inner;
        return mappings;
    }

    private static ObjectNode normalizeSettings(ObjectNode settings) {
        settings = TransformFunctions.convertFlatSettingsToTree(settings);
        ObjectNode tempRoot = MAPPER.createObjectNode();
        tempRoot.set(SETTINGS_KEY, settings);
        TransformFunctions.removeIntermediateIndexSettingsLevel(tempRoot);
        return (ObjectNode) tempRoot.get(SETTINGS_KEY);
    }

    private static int safeGetReplicas(IndexMetadata meta) {
        try {
            var settings = meta.getSettings();
            return settings != null ? settings.path("number_of_replicas").asInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static ObjectNode safeGetObjectNode(
        java.util.function.Supplier<JsonNode> supplier, String field, String index
    ) {
        try {
            var node = supplier.get();
            return node instanceof ObjectNode ? (ObjectNode) node : null;
        } catch (Exception e) {
            log.debug("Could not read {} for index {}: {}", field, index, e.getMessage());
            return null;
        }
    }
}
