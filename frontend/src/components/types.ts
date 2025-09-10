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

  /**
   * The number of shards in the index
   */
  shard_count: number;
}

/**
 * Enum representing the possible states of a snapshot index
 */
export enum SnapshotIndexState {
  NOT_STARTED = "not_started",
  IN_PROGRESS = "in_progress",
  COMPLETED = "completed",
}

/**
 * Represents a single index in a snapshot with status information
 */
export interface SnapshotIndexStatus extends SnapshotIndex {
  /**
   * The status of the index in the snapshot
   */
  status: SnapshotIndexState;
}
