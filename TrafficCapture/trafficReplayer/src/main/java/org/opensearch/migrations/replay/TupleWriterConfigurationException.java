package org.opensearch.migrations.replay;

/**
 * Thrown when the tuple writer (S3 and/or Kafka) can't be configured from the supplied
 * {@link org.opensearch.migrations.replay.TrafficReplayer.Parameters} — e.g. building the
 * Kafka producer's auth/SSL properties fails.
 */
public class TupleWriterConfigurationException extends RuntimeException {
    public TupleWriterConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
