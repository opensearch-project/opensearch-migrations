package com.rfs.common;

import java.io.InputStream;
import java.nio.file.Path;

import com.rfs.models.ShardMetadata;

// TODO: find a better approach to this (see https://opensearch.atlassian.net/browse/MIGRATIONS-1786)
public abstract class SourceRepoAccessor {
    private final SourceRepo repo;

    public SourceRepoAccessor(SourceRepo repo) {
        this.repo = repo;
    }

    public Path getRepoRootDir() {
        return repo.getRepoRootDir();
    }

    public InputStream getSnapshotRepoDataFile() {
        return load(repo.getSnapshotRepoDataFilePath());
    };

    public InputStream getGlobalMetadataFile(String snapshotId) {
        return load(repo.getGlobalMetadataFilePath(snapshotId));
    }

    public InputStream getSnapshotMetadataFile(String snapshotId) {
        return load(repo.getSnapshotMetadataFilePath(snapshotId));
    }

    public InputStream getIndexMetadataFile(String indexId, String indexFileId) {
        return load(repo.getIndexMetadataFilePath(indexId, indexFileId));
    }

    public InputStream getShardDir(String indexId, int shardId) {
        return load(repo.getShardDirPath(indexId, shardId));
    }

    public InputStream getShardMetadataFile(String snapshotId, String indexId, int shardId) {
        return load(repo.getShardMetadataFilePath(snapshotId, indexId, shardId));
    }

    public InputStream getBlobFile(String indexId, int shardId, String blobName) {
        return load(repo.getBlobFilePath(indexId, shardId, blobName));
    }

    public void prepBlobFiles(ShardMetadata shardMetadata) {
        repo.prepBlobFiles(shardMetadata);
    }

    protected abstract InputStream load(Path path);

    public static class CouldNotLoadRepoFile extends RuntimeException {
        public CouldNotLoadRepoFile(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
