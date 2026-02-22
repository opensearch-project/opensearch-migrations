package org.opensearch.migrations.bulkload.pipeline;

/**
 * Base exception for pipeline errors. Provides a consistent exception hierarchy
 * for distinguishing source, sink, and pipeline-level failures.
 */
public class PipelineException extends RuntimeException {

    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Thrown when a source fails to read data. */
    public static class SourceException extends PipelineException {
        public SourceException(String message, Throwable cause) {
            super("Source error: " + message, cause);
        }
    }

    /** Thrown when a sink fails to write data. */
    public static class SinkException extends PipelineException {
        public SinkException(String message, Throwable cause) {
            super("Sink error: " + message, cause);
        }
    }
}
