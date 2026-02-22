package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Converts existing {@link IndexMetadata} to the clean pipeline IR.
 * Shared by both {@link LuceneSnapshotSource} and {@link SnapshotMetadataSource}
 * to avoid duplicating the conversion logic.
 */
@Slf4j
final class IndexMetadataConverter {

    private IndexMetadataConverter() {}

    static IndexMetadataSnapshot convert(String indexName, IndexMetadata meta) {
        return new IndexMetadataSnapshot(
            indexName,
            meta.getNumberOfShards(),
            safeGetReplicas(meta),
            safeGetObjectNode(meta::getMappings, "mappings", indexName),
            safeGetObjectNode(meta::getSettings, "settings", indexName),
            safeGetObjectNode(meta::getAliases, "aliases", indexName)
        );
    }

    private static int safeGetReplicas(IndexMetadata meta) {
        try {
            var settings = meta.getSettings();
            return settings != null ? settings.path("number_of_replicas").asInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static ObjectNode safeGetObjectNode(java.util.function.Supplier<JsonNode> supplier, String field, String index) {
        try {
            var node = supplier.get();
            return node instanceof ObjectNode ? (ObjectNode) node : null;
        } catch (Exception e) {
            log.debug("Could not read {} for index {}: {}", field, index, e.getMessage());
            return null;
        }
    }
}
