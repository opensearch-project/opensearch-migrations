package org.opensearch.migrations.transform.shim.netty;

/**
 * Thrown when a request/response transform or serialization operation fails
 * within the shim proxy pipeline.
 */
public class TransformException extends RuntimeException {
    public TransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
