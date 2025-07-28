package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;

public class DummySnapshotFileFinder implements SnapshotFileFinder {
    @Override
    public Path getSnapshotRepoDataFilePath() {return null;};

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {return null;};

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {return null;};

    @Override
    public Path getIndexMetadataFilePath(String indexUUID, String indexFileId) {return null;};

    @Override
    public Path getShardDirPath(String indexUUID, int shardId) {return null;};

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexUUID, int shardId) {return null;};

    @Override
    public Path getBlobFilePath(String indexUUID, int shardId, String blobName) {return null;};
}
