package org.opensearch.migrations.replay.datahandlers.http;

public class TransformationException extends RuntimeException {
    public TransformationException(Throwable cause) {
        super(cause);
    }

    public TransformationException(String message, Throwable cause) {
        super(message, cause);
    }
}
