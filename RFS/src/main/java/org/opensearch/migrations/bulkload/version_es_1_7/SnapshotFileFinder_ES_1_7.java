package org.opensearch.migrations.bulkload.version_es_1_7;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;

public class SnapshotFileFinder_ES_1_7 implements SnapshotFileFinder {

    @Override
    public Path getSnapshotRepoDataFilePath(Path root) {
        // top-level "index" file — contains array of snapshot names
        return root.resolve("index");
    }

    @Override
    public Path getGlobalMetadataFilePath(Path root, String snapshotId) {
        // top-level metadata-<snapshotId>
        return root.resolve("metadata-" + snapshotId);
    }

    @Override
    public Path getSnapshotMetadataFilePath(Path root, String snapshotId) {
        // top-level snapshot-<snapshotId>
        return root.resolve("snapshot-" + snapshotId);
    }

    @Override
    public Path getIndexMetadataFilePath(Path root, String indexUUID, String indexFileId) {
        // /indices/<indexName>/metadata-<snapshotId>
        // treating indexUUID as the index name
        return root.resolve("indices").resolve(indexUUID).resolve("metadata-" + indexFileId);
    }

    @Override
    public Path getShardDirPath(Path root, String indexUUID, int shardId) {
        // /indices/<indexName>/<shardId>/
        // treating indexUUID as the index name
        return root.resolve("indices").resolve(indexUUID).resolve(Integer.toString(shardId));
    }

    @Override
    public Path getShardMetadataFilePath(Path root, String snapshotId, String indexUUID, int shardId) {
        // /indices/<indexName>/<shardId>/snapshot-<snapshotId>
        // treating indexUUID as the index name
        return getShardDirPath(root, indexUUID, shardId).resolve("snapshot-" + snapshotId);
    }

    @Override
    public Path getBlobFilePath(Path root, String indexUUID, int shardId, String blobName) {
        // /indices/<indexName>/<shardId>/__X
        // treating indexUUID as the index name
        return getShardDirPath(root, indexUUID, shardId).resolve(blobName);
    }
}
