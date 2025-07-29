package org.opensearch.migrations.bulkload.version_es_2_4;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;

public class SnapshotFileFinder_ES_2_4 implements SnapshotFileFinder {

    @Override
    public Path getSnapshotRepoDataFilePath(Path root) {
        // ES 2.4 uses a plain "index" file (no -N suffix)
        return root.resolve("index");
    }

    @Override
    public Path getGlobalMetadataFilePath(Path root, String snapshotId) {
        // top-level global metadata
        return root.resolve("meta-" + snapshotId + ".dat");
    }

    @Override
    public Path getSnapshotMetadataFilePath(Path root, String snapshotId) {
        // top-level snapshot metadata
        return root.resolve("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getIndexMetadataFilePath(Path root, String indexName, String indexFileId) {
        // /indices/<indexName>/meta-<snapshotName>.dat
        return root.resolve("indices").resolve(indexName).resolve("meta-" + indexFileId + ".dat");
    }

    @Override
    public Path getShardDirPath(Path root, String indexName, int shardId) {
        return root.resolve("indices").resolve(indexName).resolve(Integer.toString(shardId));
    }

    @Override
    public Path getShardMetadataFilePath(Path root, String snapshotId, String indexName, int shardId) {
        return getShardDirPath(root, indexName, shardId).resolve("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getBlobFilePath(Path root, String indexName, int shardId, String blobName) {
        return getShardDirPath(root, indexName, shardId).resolve(blobName);
    }
}
