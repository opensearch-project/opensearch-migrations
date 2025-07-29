package org.opensearch.migrations.bulkload.version_es_7_10;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;

public class SnapshotFileFinder_ES_7_10 implements SnapshotFileFinder {

    @Override
    public Path getSnapshotRepoDataFilePath(Path root) {
        // FileSystemRepo/S3Repo is responsible for scanning index-N files
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
    public Path getIndexMetadataFilePath(Path root, String indexUUID, String indexFileId) {
        return root.resolve("indices").resolve(indexUUID).resolve("meta-" + indexFileId + ".dat");
    }

    @Override
    public Path getShardDirPath(Path root, String indexUUID, int shardId) {
        return root.resolve("indices").resolve(indexUUID).resolve(Integer.toString(shardId));
    }

    @Override
    public Path getShardMetadataFilePath(Path root, String snapshotId, String indexUUID, int shardId) {
        return getShardDirPath(root, indexUUID, shardId).resolve("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getBlobFilePath(Path root, String indexUUID, int shardId, String blobName) {
        return getShardDirPath(root, indexUUID, shardId).resolve(blobName);
    }
}
