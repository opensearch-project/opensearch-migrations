package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DummySnapshotFileFinder implements SnapshotFileFinder {

    private final String indexFileName;
    public DummySnapshotFileFinder() {
        this("index-0");
    }
    public DummySnapshotFileFinder(String indexFileName) {
        this.indexFileName = indexFileName;
    }

    @Override
    public Path getSnapshotRepoDataFilePath() {
        return Paths.get(indexFileName);  // now returns test-controlled name
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        return Paths.get("meta-" + snapshotId + ".dat");
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        return Paths.get("snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        return Paths.get("indices", indexId, "meta-" + indexFileId + ".dat");
    }

    @Override
    public Path getShardDirPath(String indexId, int shardId) {
        return Paths.get("indices", indexId, Integer.toString(shardId));
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return Paths.get("indices", indexId, Integer.toString(shardId), "snap-" + snapshotId + ".dat");
    }

    @Override
    public Path getBlobFilePath(String indexId, int shardId, String blobName) {
        return Paths.get("indices", indexId, Integer.toString(shardId), blobName);
    }
}
