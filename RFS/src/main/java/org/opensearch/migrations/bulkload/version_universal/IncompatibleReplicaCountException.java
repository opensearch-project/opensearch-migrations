package org.opensearch.migrations.bulkload.version_universal;

public class IncompatibleReplicaCountException extends Exception {
    public IncompatibleReplicaCountException(String message, Throwable cause) {
        super(message, cause);
    }
}
