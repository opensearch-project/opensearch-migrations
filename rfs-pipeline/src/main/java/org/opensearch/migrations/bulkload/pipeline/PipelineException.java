package org.opensearch.migrations.bulkload.pipeline;

/**
 * Exception for pipeline errors — wraps unexpected failures from sources and sinks
 * with context about which shard or index was being processed.
 */
public class PipelineException extends RuntimeException {

    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
