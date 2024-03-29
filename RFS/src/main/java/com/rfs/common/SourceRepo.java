package com.rfs.common;

import java.io.IOException;
import java.nio.file.Path;

public interface SourceRepo {
    public Path getRepoRootDir();
    public Path getSnapshotRepoDataFilePath() throws IOException;
    public Path getGlobalMetadataFilePath(String snapshotId) throws IOException;
    public Path getSnapshotMetadataFilePath(String snapshotId) throws IOException;
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) throws IOException;
    public Path getShardDirPath(String indexId, int shardId) throws IOException;
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) throws IOException;
    public Path getBlobFilePath(String indexId, int shardId, String blobName) throws IOException;
}
