package org.opensearch.migrations.reindexer.faileddocumentstream;

/**
 * How a failed bulk item ended up in the failed document stream.
 *
 * <p>Mirrors the three-bucket model used by {@code OpenSearchClient.executeBulkWithRetry}:
 * allowed exceptions are treated as success and never written here; everything else lands
 * in one of these two terminal buckets.
 */
public enum FailureClass {
    /**
     * Error type is in {@code BulkDocErrorTypes.NON_RETRYABLE}, or the target rejected a single-operation request
     * outright (400/413, {@code failureType} {@code http_<status>}); written after the first attempt.
     */
    NON_RETRYABLE,
    /**
     * Error was retried up to the configured limit and still failed on every attempt. Request-level failures carry
     * {@code failureType} {@code http_<status>} or {@code no_response}.
     */
    RETRYABLE_EXHAUSTED
}
