package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;

public interface SnapshotFileFinder {
    Path getSnapshotRepoDataFilePath(Path root);

    Path getGlobalMetadataFilePath(Path root, String snapshotId);

    Path getSnapshotMetadataFilePath(Path root, String snapshotId);

    Path getIndexMetadataFilePath(Path root, String indexUUID, String indexFileId);

    Path getShardDirPath(Path root, String indexUUID, int shardId);

    Path getShardMetadataFilePath(Path root, String snapshotId, String indexUUID, int shardId);

    Path getBlobFilePath(Path root, String indexUUID, int shardId, String blobName);
}
