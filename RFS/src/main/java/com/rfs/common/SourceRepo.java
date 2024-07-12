package com.rfs.common;

import java.nio.file.Path;

import com.rfs.models.ShardMetadata;

public interface SourceRepo {
    public Path getRepoRootDir();

    public Path getSnapshotRepoDataFilePath();

    public Path getGlobalMetadataFilePath(String snapshotId);

    public Path getSnapshotMetadataFilePath(String snapshotId);

    public Path getIndexMetadataFilePath(String indexId, String indexFileId);

    public Path getShardDirPath(String indexId, int shardId);

    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId);

    public Path getBlobFilePath(String indexId, int shardId, String blobName);

    /*
    * Performs any work necessary to facilitate access to a given shard's blob files.  Depending on the implementation,
    * may involve no work at all, bulk downloading objects from a remote source, or any other operations.
    */
    public void prepBlobFiles(ShardMetadata shardMetadata);
}
