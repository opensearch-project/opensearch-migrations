package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

public interface SnapshotFileFinder {
    Pattern getSnapshotRepoDataIndexPattern();

    Path getSnapshotRepoDataFilePath(Path root, List<String> fileNames) throws BaseSnapshotFileFinder.CannotFindRepoIndexFile;

    Path getGlobalMetadataFilePath(Path root, String snapshotId);

    Path getSnapshotMetadataFilePath(Path root, String snapshotId);

    Path getIndexMetadataFilePath(Path root, String indexUUID, String indexFileId);

    Path getShardDirPath(Path root, String indexUUID, int shardId);

    Path getShardMetadataFilePath(Path root, String snapshotId, String indexUUID, int shardId);

    Path getBlobFilePath(Path root, String indexUUID, int shardId, String blobName);
}
