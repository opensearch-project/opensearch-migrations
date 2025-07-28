package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.models.ShardMetadata;

import lombok.ToString;

@ToString
public class FileSystemRepo implements SourceRepo {
    private final Path repoRootDir;
    private final SnapshotFileFinder fileFinder;

    public FileSystemRepo(Path repoRootDir, SnapshotFileFinder fileFinder) {
        this.repoRootDir = repoRootDir;
        this.fileFinder = fileFinder;
    }

    @Override
    public Path getRepoRootDir() {
        return repoRootDir;
    }

    @Override
    public Path getSnapshotRepoDataFilePath() {
        return fileFinder.getSnapshotRepoDataFilePath();
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        return fileFinder.getGlobalMetadataFilePath(snapshotId);
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        return fileFinder.getSnapshotMetadataFilePath(snapshotId);
    }

    @Override
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        return fileFinder.getIndexMetadataFilePath(indexId, indexFileId);
    }

    @Override
    public Path getShardDirPath(String indexId, int shardId) {
        return fileFinder.getShardDirPath(indexId, shardId);
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return fileFinder.getShardMetadataFilePath(snapshotId, indexId, shardId);
    }

    @Override
    public Path getBlobFilePath(String indexId, int shardId, String blobName) {
        return fileFinder.getBlobFilePath(indexId, shardId, blobName);
    }

    @Override
    public void prepBlobFiles(ShardMetadata shardMetadata) {
        // No work necessary for local filesystem
    }
}
