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
            "Failed to read snapshot metadata. This may happen if the specified --source-version does not match " +
            "the actual cluster version that created the snapshot, if the snapshot was created with compression enabled, " +
            "or if the snapshot metadata is corrupted or unreadable. Please verify the source version and snapshot settings. " +
            "If your snapshot was created with compression enabled, take a new full snapshot without compression and retry.";
    
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
