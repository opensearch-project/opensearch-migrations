package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.pipeline.ir.Partition;

/**
 * Thrown when a shard exceeds the configured maximum size, preventing disk overflow
 * during document migration.
 */
public class ShardTooLargeException extends RuntimeException {

    public ShardTooLargeException(Partition partition, long actualBytes, long maxBytes) {
        super(String.format(
            "Partition %s is %,d bytes which exceeds the maximum of %,d bytes. " +
            "Increase --max-shard-size-bytes or skip this partition.",
            partition, actualBytes, maxBytes));
    }
}
