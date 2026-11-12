package org.opensearch.migrations.bulkload.common;

/**
 * Shared constants and arithmetic for bounding RFS heap use from serialized document sizes.
 *
 * <p>The expansion factor is deliberately pessimistic. The large-document splitter allocation
 * profile asserts that the input plus all allocations made while splitting remain below this
 * multiplier for the supported high-key, deeply nested benchmark shapes.
 */
public final class RfsMemorySizing {
    /** Maximum concurrent stored-source document reads within one Lucene segment. */
    public static final int SOURCE_READ_PARALLELISM = 100;
    public static final int SERIALIZED_BYTES_HEAP_EXPANSION_FACTOR = 8;
    public static final long BASE_HEAP_BYTES = 5_600L * 1024 * 1024;

    private RfsMemorySizing() {}

    public static int transformParallelism(int maxConnections, int availableProcessors) {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be >= 1, got " + maxConnections);
        }
        return Math.min(maxConnections, Math.max(1, availableProcessors));
    }

    public static long sourcePermitBytes(long maxSourceDocumentSizeBytes) {
        return multiplyNonNegative(
            maxSourceDocumentSizeBytes,
            SOURCE_READ_PARALLELISM,
            "maxSourceDocumentSizeBytes"
        );
    }

    public static long transformPermitBytes(
        long maxTransformOutputBytesPerInvocation,
        int maxConnections,
        int availableProcessors
    ) {
        return multiplyNonNegative(
            maxTransformOutputBytesPerInvocation,
            transformParallelism(maxConnections, availableProcessors),
            "maxTransformOutputBytesPerInvocation"
        );
    }

    public static long requiredHeapBytes(
        long maxSourceDocumentSizeBytes,
        long maxTransformOutputBytesPerInvocation,
        int maxConnections,
        int availableProcessors
    ) {
        long serializedBytes = Math.addExact(
            sourcePermitBytes(maxSourceDocumentSizeBytes),
            transformPermitBytes(maxTransformOutputBytesPerInvocation, maxConnections, availableProcessors)
        );
        return Math.addExact(
            BASE_HEAP_BYTES,
            Math.multiplyExact(serializedBytes, SERIALIZED_BYTES_HEAP_EXPANSION_FACTOR)
        );
    }

    private static long multiplyNonNegative(long bytes, int multiplier, String name) {
        if (bytes < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + bytes);
        }
        return Math.multiplyExact(bytes, multiplier);
    }
}
