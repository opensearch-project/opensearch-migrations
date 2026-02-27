package org.opensearch.migrations.bulkload.pipeline.ir;

import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lucene-agnostic metadata snapshot for a single index. Clean IR boundary.
 *
 * <p>Unlike the existing {@code IndexMetadata} interface, this is a simple data carrier
 * with no factory methods, repo-access logic, or version-specific behavior.
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
