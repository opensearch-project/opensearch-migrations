package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;


public interface SourceRepo {
    public Path getRepoRootDir();

    public Path getSnapshotRepoDataFilePath();

    public Path getGlobalMetadataFilePath(String snapshotId);

    public Path getSnapshotMetadataFilePath(String snapshotId);

    public Path getIndexMetadataFilePath(String indexId, String indexFileId);

    public Path getShardDirPath(String indexId, int shardId);

    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId);

    public Path getBlobFilePath(String indexId, int shardId, String blobName);
}
