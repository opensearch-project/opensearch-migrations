package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;

/**
 * Thrown when a shard exceeds the configured maximum size, preventing disk overflow
 * during document migration.
 */
public class ShardTooLargeException extends RuntimeException {

    public ShardTooLargeException(ShardId shardId, long actualBytes, long maxBytes) {
        super(String.format(
            "Shard %s is %,d bytes which exceeds the maximum of %,d bytes. " +
            "Increase --max-shard-size-bytes or skip this shard.",
            shardId, actualBytes, maxBytes));
    }
}
