package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Converts existing {@link IndexMetadata} to the clean pipeline IR.
 * Shared by both {@link LuceneSnapshotSource} and {@link SnapshotMetadataSource}
 * to avoid duplicating the conversion logic.
 */
final class IndexMetadataConverter {

    private IndexMetadataConverter() {}

    static IndexMetadataSnapshot convert(String indexName, IndexMetadata meta) {
        return new IndexMetadataSnapshot(
            indexName,
            meta.getNumberOfShards(),
            meta.getSettings().path("number_of_replicas").asInt(0),
            (ObjectNode) meta.getMappings(),
            (ObjectNode) meta.getSettings(),
            (ObjectNode) meta.getAliases()
        );
    }
}
