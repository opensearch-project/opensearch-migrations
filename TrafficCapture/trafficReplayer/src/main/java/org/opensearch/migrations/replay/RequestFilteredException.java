package org.opensearch.migrations.replay;

/**
 * Thrown when a request is rejected by the configured request filter predicate.
 * The pipeline handles this as a SKIPPED request — no bytes sent to target, no retry.
 */
public class RequestFilteredException extends RuntimeException {
    public RequestFilteredException(String message) {
        super(message);
    }
}
