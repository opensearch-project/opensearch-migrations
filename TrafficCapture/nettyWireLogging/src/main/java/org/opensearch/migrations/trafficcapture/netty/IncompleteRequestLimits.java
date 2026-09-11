package org.opensearch.migrations.trafficcapture.netty;

import java.time.Duration;

import lombok.NonNull;

public record IncompleteRequestLimits(
    @NonNull Duration maximumAssemblyDuration,
    long maximumHeaderBytes,
    long maximumTotalBytes
) {
    public static final Duration DEFAULT_MAXIMUM_ASSEMBLY_DURATION = Duration.ofMinutes(5);
    public static final long DEFAULT_MAXIMUM_HEADER_BYTES = 10L * 1024 * 1024;
    public static final long DEFAULT_MAXIMUM_TOTAL_BYTES = 250L * 1024 * 1024;
    public static final IncompleteRequestLimits DEFAULT = new IncompleteRequestLimits(
        DEFAULT_MAXIMUM_ASSEMBLY_DURATION,
        DEFAULT_MAXIMUM_HEADER_BYTES,
        DEFAULT_MAXIMUM_TOTAL_BYTES
    );

    public IncompleteRequestLimits {
        if (maximumAssemblyDuration.isNegative()) {
            throw new IllegalArgumentException("maximumAssemblyDuration must not be negative");
        }
        if (maximumHeaderBytes <= 0 || maximumHeaderBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "maximumHeaderBytes must be positive and no greater than Integer.MAX_VALUE"
            );
        }
        if (maximumTotalBytes <= 0) {
            throw new IllegalArgumentException("maximumTotalBytes must be positive");
        }
        if (maximumTotalBytes < maximumHeaderBytes) {
            throw new IllegalArgumentException("maximumTotalBytes must not be less than maximumHeaderBytes");
        }
    }
}
