package org.opensearch.migrations.bulkload.version_es_1_7;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;

public class SnapshotFileFinder_ES_1_7 implements SnapshotFileFinder {

    @Override
    public Path getSnapshotRepoDataFilePath() {
        return null;
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        return null;
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        return null;
    }

    @Override
    public Path getIndexMetadataFilePath(String indexUUID, String indexFileId) {
        return null;
    }

    @Override
    public Path getShardDirPath(String indexUUID, int shardId) {
        return null;
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexUUID, int shardId) {
        return null;
    }

    @Override
    public Path getBlobFilePath(String indexUUID, int shardId, String blobName) {
        return null;
    }
}
