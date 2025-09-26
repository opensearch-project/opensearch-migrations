package org.opensearch.migrations.bulkload.common;

/**
 * Exception thrown when snapshot format is invalid or incompatible with the specified source version.
 * This typically occurs when:
 * 1. The source version doesn't match the actual snapshot format
 * 2. The snapshot was created with compression enabled
 * 3. The snapshot metadata is corrupted or unreadable
 */
public class InvalidSnapshotFormatException extends RuntimeException {
    private static final String DEFAULT_MESSAGE = 
        "Cannot read snapshot metadata: snapshot format may not match the specified source version. " +
        "Verify --source-version matches your source cluster version and ensure snapshot was created without compression.";
    
    public InvalidSnapshotFormatException() {
        super(DEFAULT_MESSAGE);
    }
    
    public InvalidSnapshotFormatException(String additionalContext) {
        super(DEFAULT_MESSAGE + " " + additionalContext);
    }
    
    public InvalidSnapshotFormatException(String additionalContext, Throwable cause) {
        super(DEFAULT_MESSAGE + " " + additionalContext, cause);
    }
}
