package org.opensearch.migrations.bulkload.common;

/**
 * Determines which {@link DocumentReaderEngine} to use for each shard.
 * Enables per-shard delta determination — some shards may use delta reading
 * while others use regular reading.
 */
@FunctionalInterface
public interface ShardReadStrategy {
    DocumentReaderEngine getEngine(String indexName, int shardNumber);
}
