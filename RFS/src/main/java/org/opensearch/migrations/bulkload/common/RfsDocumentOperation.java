package org.opensearch.migrations.bulkload.common;


/**
 * Internal enum representing the operation type for an RFS document.
 */
public enum RfsDocumentOperation {
    /**
     * Document should be indexed
     */
    INDEX,

    /**
     * Document represents a deletion operation
     */
    DELETE;
}
