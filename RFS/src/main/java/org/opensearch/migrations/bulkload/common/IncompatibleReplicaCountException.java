package org.opensearch.migrations.bulkload.common;

public class IncompatibleReplicaCountException extends Exception {
    public IncompatibleReplicaCountException(String message, Throwable cause) {
        super(message, cause);
    }
}
