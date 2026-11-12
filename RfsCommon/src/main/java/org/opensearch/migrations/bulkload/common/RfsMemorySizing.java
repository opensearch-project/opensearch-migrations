package org.opensearch.migrations.bulkload.common;

/**
 * Shared constants and arithmetic for bounding RFS heap use from serialized document sizes.
 *
 * <p>The expansion factor is deliberately pessimistic. The large-document splitter allocation
 * profile asserts that the input plus all allocations made while splitting remain below this
 * multiplier for the supported high-key, deeply nested benchmark shapes.
 */
public final class RfsMemorySizing {
    public static final int SERIALIZED_BYTES_HEAP_EXPANSION_FACTOR = 8;
    public static final long BASE_HEAP_BYTES = 5_600L * 1024 * 1024;

    private RfsMemorySizing() {}

    public static int readerParallelism(int maxConnections, int availableProcessors) {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be >= 1, got " + maxConnections);
        }
        return Math.min(maxConnections, Math.max(1, availableProcessors));
    }

    public static int transformParallelism(int maxConnections, int availableProcessors) {
        return readerParallelism(maxConnections, availableProcessors);
    }

    /**
     * Serialized bytes that one bulk request can attain under both its byte and document-count limits.
     */
    public static long attainableBulkBytes(
        long maxSourceDocumentSizeBytes,
        int maxDocsPerBatch,
        long maxBytesPerBatch
    ) {
        validateNonNegative(maxSourceDocumentSizeBytes, "maxSourceDocumentSizeBytes");
        if (maxDocsPerBatch < 1) {
            throw new IllegalArgumentException("maxDocsPerBatch must be >= 1, got " + maxDocsPerBatch);
        }
        if (maxBytesPerBatch < 1) {
            throw new IllegalArgumentException("maxBytesPerBatch must be >= 1, got " + maxBytesPerBatch);
        }
        if (maxSourceDocumentSizeBytes == 0) {
            return 0;
        }
        if (maxSourceDocumentSizeBytes > maxBytesPerBatch / maxDocsPerBatch) {
            return maxBytesPerBatch;
        }
        return Math.min(
            maxBytesPerBatch,
            Math.multiplyExact(maxSourceDocumentSizeBytes, (long) maxDocsPerBatch)
        );
    }

    /**
     * One maximum reservation for every active reader plus enough retained documents to
     * fill every target connection to the attainable bulk size.
     */
    public static long sourcePermitBytes(
        long maxSourceDocumentSizeBytes,
        int maxDocsPerBatch,
        long maxBytesPerBatch,
        int maxConnections,
        int availableProcessors
    ) {
        long readerBytes = multiplyNonNegative(
            maxSourceDocumentSizeBytes,
            readerParallelism(maxConnections, availableProcessors),
            "maxSourceDocumentSizeBytes"
        );
        long requestBytes = multiplyNonNegative(
            attainableBulkBytes(maxSourceDocumentSizeBytes, maxDocsPerBatch, maxBytesPerBatch),
            maxConnections,
            "attainableBulkBytes"
        );
        return Math.addExact(readerBytes, requestBytes);
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
        int maxDocsPerBatch,
        long maxBytesPerBatch,
        int maxConnections,
        int availableProcessors
    ) {
        long serializedBytes = Math.addExact(
            sourcePermitBytes(
                maxSourceDocumentSizeBytes,
                maxDocsPerBatch,
                maxBytesPerBatch,
                maxConnections,
                availableProcessors
            ),
            transformPermitBytes(maxTransformOutputBytesPerInvocation, maxConnections, availableProcessors)
        );
        return Math.addExact(
            BASE_HEAP_BYTES,
            Math.multiplyExact(serializedBytes, SERIALIZED_BYTES_HEAP_EXPANSION_FACTOR)
        );
    }

    private static long multiplyNonNegative(long bytes, int multiplier, String name) {
        validateNonNegative(bytes, name);
        return Math.multiplyExact(bytes, multiplier);
    }

    private static void validateNonNegative(long bytes, String name) {
        if (bytes < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + bytes);
        }
    }
}
