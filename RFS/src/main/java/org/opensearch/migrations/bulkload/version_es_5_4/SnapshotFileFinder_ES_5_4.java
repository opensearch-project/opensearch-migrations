package org.opensearch.migrations.bulkload.version_es_5_4;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;

public class SnapshotFileFinder_ES_5_4 implements SnapshotFileFinder {

    @Override
    public Path getSnapshotRepoDataFilePath(Path root) {
        // FileSystemRepo/S3Repo will pick the highest "index-N" file
        return null;
    }

    @Override
    public Path getGlobalMetadataFilePath(Path root, String snapshotId) {
        // /tmp/snapshots/meta-<snapshotId>.dat
        return root.resolve("meta-" + snapshotId + ".dat");
    }

    @Override
    public Path getSnapshotMetadataFilePath(Path root, String snapshotId) {
        // /tmp/snapshots/snap-<snapshotId>.dat
        return root.resolve("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getIndexMetadataFilePath(Path root, String indexUUID, String indexFileId) {
        // /tmp/snapshots/indices/<indexUUID>/meta-<indexFileId>.dat
        return root.resolve("indices").resolve(indexUUID).resolve("meta-" + indexFileId + ".dat");
    }

    @Override
    public Path getShardDirPath(Path root, String indexUUID, int shardId) {
        // /tmp/snapshots/indices/<indexUUID>/<shardId>
        return root.resolve("indices").resolve(indexUUID).resolve(Integer.toString(shardId));
    }

    @Override
    public Path getShardMetadataFilePath(Path root, String snapshotId, String indexUUID, int shardId) {
        // /tmp/snapshots/indices/<indexUUID>/<shardId>/snap-<snapshotId>.dat
        return getShardDirPath(root, indexUUID, shardId).resolve("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getBlobFilePath(Path root, String indexUUID, int shardId, String blobName) {
        // /tmp/snapshots/indices/<indexUUID>/<shardId>/<blobName>
        return getShardDirPath(root, indexUUID, shardId).resolve(blobName);
    }
}
