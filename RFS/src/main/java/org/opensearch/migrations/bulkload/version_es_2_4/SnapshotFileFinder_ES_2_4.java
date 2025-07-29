package org.opensearch.migrations.bulkload.version_es_2_4;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.opensearch.migrations.bulkload.common.BaseSnapshotFileFinder;

public class SnapshotFileFinder_ES_2_4 extends BaseSnapshotFileFinder {

    // ES 2.x uses a static "index" file (not index-N)
    private static final Pattern STATIC_INDEX_PATTERN = Pattern.compile("^index$");

    @Override
    public Pattern getSnapshotRepoDataIndexPattern() {
        return STATIC_INDEX_PATTERN;
    }

    /**
     * In ES 2.x, the snapshot repo metadata file is always called "index"
     * and appears exactly once.
     */
    @Override
    public Path getSnapshotRepoDataFilePath(Path root, List<String> fileNames) throws CannotFindRepoIndexFile {
        if (fileNames.contains("index")) {
            return root.resolve("index");
        }
        throw new CannotFindRepoIndexFile();
    }

    /**
     * In ES 2.x, index directories are named by index name (not UUID).
     */
    @Override
    public Path getIndexMetadataFilePath(Path root, String indexName, String indexFileId) {
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
