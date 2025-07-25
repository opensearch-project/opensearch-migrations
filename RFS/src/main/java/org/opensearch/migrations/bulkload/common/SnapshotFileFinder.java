package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;

public interface SnapshotFileFinder {
    Path getSnapshotRepoDataFilePath();

    Path getGlobalMetadataFilePath(String snapshotId);

    Path getSnapshotMetadataFilePath(String snapshotId);

    Path getIndexMetadataFilePath(String indexUUID, String indexFileId);

    Path getShardDirPath(String indexUUID, int shardId);

    Path getShardMetadataFilePath(String snapshotId, String indexUUID, int shardId);

    Path getBlobFilePath(String indexUUID, int shardId, String blobName);
}
