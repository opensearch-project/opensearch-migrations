package org.opensearch.migrations.bulkload.common.bulk.operations;

/**
 * Sealed interface for operation metadata.
 * This interface defines the contract for all bulk operation metadata types.
 */
public sealed interface BaseOperationMeta 
    permits IndexOperationMeta, DeleteOperationMeta {
    // Marker interface for all operation metadata types
}
