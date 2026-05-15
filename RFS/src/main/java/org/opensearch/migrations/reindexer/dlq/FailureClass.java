package org.opensearch.migrations.reindexer.dlq;

/**
 * How a failed bulk item ended up in the DLQ.
 *
 * <p>Mirrors the three-bucket model used by {@code OpenSearchClient.executeBulkWithRetry}:
 * allowed exceptions are treated as success and never written here; everything else lands
 * in one of these two terminal buckets.
 */
public enum FailureClass {
    /** Error type is in {@code BulkDocErrorTypes.NON_RETRYABLE}; written after the first attempt. */
    NON_RETRYABLE,
    /** Error was retried up to the configured limit and still failed on every attempt. */
    RETRYABLE_EXHAUSTED
}