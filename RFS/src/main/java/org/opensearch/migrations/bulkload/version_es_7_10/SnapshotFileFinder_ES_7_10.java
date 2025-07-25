package org.opensearch.migrations.bulkload.version_es_7_10;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;

public class SnapshotFileFinder_ES_7_10 implements SnapshotFileFinder {

    @Override
    public Path getSnapshotRepoDataFilePath() {
        // This returns "index-N", but since "N" is variable,
        // the repo implementation must enumerate and choose the highest.
        // So return null — FileSystemRepo/S3Repo should handle it.
        return null;
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        return Path.of("meta-" + snapshotId + ".dat");
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        return Path.of("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getIndexMetadataFilePath(String indexUUID, String indexFileId) {
        return Path.of("indices", indexUUID, "meta-" + indexFileId + ".dat");
    }

    @Override
    public Path getShardDirPath(String indexUUID, int shardId) {
        return Path.of("indices", indexUUID, Integer.toString(shardId));
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexUUID, int shardId) {
        return getShardDirPath(indexUUID, shardId)
                .resolve("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getBlobFilePath(String indexUUID, int shardId, String blobName) {
        return getShardDirPath(indexUUID, shardId)
                .resolve(blobName);
    }
}
