/**
 * Type definitions for the OpenSearch Migrations frontend components
 */

/**
 * Represents a single index in a snapshot
 */
export interface SnapshotIndex {
  /**
   * The name of the index
   */
  name: string;
  
  /**
   * The number of documents in the index
   */
  document_count: number;
  
  /**
   * The size of the index in bytes
   */
  size_bytes: number;
}
