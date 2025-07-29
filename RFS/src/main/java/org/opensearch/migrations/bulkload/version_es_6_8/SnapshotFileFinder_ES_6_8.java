package org.opensearch.migrations.bulkload.version_es_6_8;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;

public class SnapshotFileFinder_ES_6_8 implements SnapshotFileFinder {

    @Override
    public Path getSnapshotRepoDataFilePath(Path root) {
        // Let FileSystemRepo or S3Repo determine the correct index-N file
        return null;
    }

    @Override
    public Path getGlobalMetadataFilePath(Path root, String snapshotId) {
        return root.resolve("meta-" + snapshotId + ".dat");
    }

    @Override
    public Path getSnapshotMetadataFilePath(Path root, String snapshotId) {
        return root.resolve("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getIndexMetadataFilePath(Path root, String indexId, String indexFileId) {
        return root.resolve("indices").resolve(indexId).resolve("meta-" + indexFileId + ".dat");
    }

    @Override
    public Path getShardDirPath(Path root, String indexId, int shardId) {
        return root.resolve("indices").resolve(indexId).resolve(String.valueOf(shardId));
    }

    @Override
    public Path getShardMetadataFilePath(Path root, String snapshotId, String indexId, int shardId) {
        return getShardDirPath(root, indexId, shardId).resolve("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getBlobFilePath(Path root, String indexId, int shardId, String blobName) {
        return getShardDirPath(root, indexId, shardId).resolve(blobName);
    }
}
