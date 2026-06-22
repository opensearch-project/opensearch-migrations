package org.opensearch.migrations.bulkload.common;

/**
 * Marker interface for exceptions that represent a failure to read a snapshot (its repository
 * metadata, index/shard metadata, or shard blob data) as opposed to a failure writing to the
 * target cluster or a work-coordination problem.
 *
 * <p>These failures are non-retriable from the migration worker's perspective: by the time one
 * surfaces it has already escaped the underlying client's transient-retry handling (e.g. the S3
 * client's built-in retries), so the snapshot is effectively unreadable for this attempt. The
 * worker's top-level handler uses this marker to emit a clearly-labeled ERROR log with the
 * snapshot path and context before the process exits, so the reason is visible in workflow logs
 * (Argo / Step Functions) and CloudWatch even if the pod is terminated immediately afterward.
 *
 * <p>Implemented by snapshot-read exception types across the repo/snapshot modules. It is a pure
 * marker (no members) so existing exceptions can adopt it without behavior changes.
 */
public interface SnapshotReadFailure {
}
