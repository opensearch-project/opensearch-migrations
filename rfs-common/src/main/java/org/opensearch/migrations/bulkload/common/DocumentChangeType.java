package org.opensearch.migrations.bulkload.common;


/**
 * Internal enum representing the operation type for a document change.
 */
public enum DocumentChangeType {
    /**
     * Document should be indexed
     */
    INDEX,

    /**
     * Document represents a deletion operation
     */
    DELETE;
}
