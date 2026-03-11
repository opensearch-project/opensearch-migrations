package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ES-specific metadata snapshot for a single index.
 *
 * <p>This is an adapter-layer type, not part of the core IR. The core pipeline uses
 * {@link org.opensearch.migrations.bulkload.pipeline.ir.CollectionMetadata} instead.
 * ES source adapters produce this, and ES sink adapters consume it via
 * {@link CollectionMetadata#sourceConfig()}.
 *
 * @param indexName       the index name, must not be null
 * @param numberOfShards  the number of primary shards, must be positive
 * @param numberOfReplicas the number of replicas, must be non-negative
 * @param mappings        the index mappings as JSON, nullable
 * @param settings        the index settings as JSON, nullable
 * @param aliases         the index aliases as JSON, nullable
 */
public record IndexMetadataSnapshot(
    String indexName,
    int numberOfShards,
    int numberOfReplicas,
    ObjectNode mappings,
    ObjectNode settings,
    ObjectNode aliases
) {
    public IndexMetadataSnapshot {
        Objects.requireNonNull(indexName, "indexName must not be null");
        if (numberOfShards < 1) {
            throw new IllegalArgumentException("numberOfShards must be >= 1, got " + numberOfShards);
        }
        if (numberOfReplicas < 0) {
            throw new IllegalArgumentException("numberOfReplicas must be >= 0, got " + numberOfReplicas);
        }
    }
}
