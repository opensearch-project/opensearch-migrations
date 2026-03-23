package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.pipeline.model.Partition;

import lombok.Getter;

/**
 * Thrown when a shard exceeds the configured maximum size, preventing disk overflow
 * during document migration.
 */
@Getter
public class ShardTooLargeException extends RuntimeException {
    private final Partition partition;
    private final long actualBytes;
    private final long maxBytes;

    public ShardTooLargeException(Partition partition, long actualBytes, long maxBytes) {
        super(String.format(
            "Partition %s is %,d bytes which exceeds the maximum of %,d bytes. " +
            "Increase --max-shard-size-bytes or skip this partition.",
            partition, actualBytes, maxBytes));
        this.partition = partition;
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }
}
