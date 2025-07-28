package org.opensearch.migrations.bulkload.version_es_6_8;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;

public class SnapshotFileFinder_ES_6_8 implements SnapshotFileFinder {

    private final Path root;

    public SnapshotFileFinder_ES_6_8(Path root) {
        this.root = root;
    }

    public Path getSnapshotRepoDataFilePath() {
        return root.resolve("index-2");
    }

    public Path getGlobalMetadataFilePath(String snapshotId) {
        return root.resolve("meta-" + snapshotId + ".dat");
    }

    public Path getSnapshotMetadataFilePath(String snapshotId) {
        return root.resolve("snap-" + snapshotId + ".dat");
    }

    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        return root.resolve("indices").resolve(indexId).resolve("meta-" + indexFileId + ".dat");
    }

    public Path getShardDirPath(String indexId, int shardId) {
        return root.resolve("indices").resolve(indexId).resolve(String.valueOf(shardId));
    }

    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return getShardDirPath(indexId, shardId).resolve("snap-" + snapshotId + ".dat");
    }

    public Path getBlobFilePath(String indexId, int shardId, String blobName) {
        return getShardDirPath(indexId, shardId).resolve(blobName);
    }
}
