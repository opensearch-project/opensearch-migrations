package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base implementation of SnapshotFileFInder with default logic
 * based on Elasticsearch 7.10 snapshot layout:
 *
 * <pre>
 * /repo/
 *   ├── index-9
 *   ├── meta-<snapshotId>.dat
 *   ├── snap-<snapshotId>.dat
 *   └── indices/
 *       └── <indexUUID>/
 *           └── <shardId>/
 *               ├── __<blobFiles>
 *               ├── snap-<snapshotId>.dat
 *               └── ...
 * </pre>
 */
public class BaseSnapshotFileFinder implements SnapshotFileFinder {

    // Example: index-0, index-9
    private static final Pattern INDEX_PATTERN = Pattern.compile("^index-(\\d+)$");

    @Override
    public Pattern getSnapshotRepoDataIndexPattern() {
        // Example path : /repo/index-9
        return INDEX_PATTERN;
    }

    /**
     * Resolves the snapshot repository metadata file (index-N with highest N).
     */
    @Override
    public Path getSnapshotRepoDataFilePath(Path root, List<String> fileNames) {
        return fileNames.stream()
            .filter(name -> INDEX_PATTERN.matcher(name).matches())
            .map(name -> new AbstractMap.SimpleEntry<>(name, extractIndexVersion(name)))
            .max(Comparator.comparingInt(Map.Entry::getValue))
            .map(entry -> root.resolve(entry.getKey()))
            .orElseThrow(CantFindRepoIndexFile::new);
    }

    /**
     * Resolves global metadata file for the given snapshot ID.
     */
    @Override
    public Path getGlobalMetadataFilePath(Path root, String snapshotId) {
        // Example path : /repo/meta-<snapshotId>.dat
        return root.resolve("meta-" + snapshotId + ".dat");
    }

    /**
     * Resolves snapshot metadata file for the given snapshot ID.
     */
    @Override
    public Path getSnapshotMetadataFilePath(Path root, String snapshotId) {
        // Example path : /repo/snap-<snapshotId>.dat
        return root.resolve("snap-" + snapshotId + ".dat");
    }

    /**
     * Resolves index metadata file.
     */
    @Override
    public Path getIndexMetadataFilePath(Path root, String indexUUID, String indexFileId) {
        // Example path : /repo/indices/<indexUUID>/meta-<indexFileId>.dat
        return root.resolve("indices").resolve(indexUUID).resolve("meta-" + indexFileId + ".dat");
    }

    /**
     * Resolves path to the shard directory.
     */
    @Override
    public Path getShardDirPath(Path root, String indexUUID, int shardId) {
        // Example path : /repo/indices/<indexUUID>/<shardId>/
        return root.resolve("indices").resolve(indexUUID).resolve(Integer.toString(shardId));
    }

    /**
     * Resolves shard metadata file.
     */
    @Override
    public Path getShardMetadataFilePath(Path root, String snapshotId, String indexUUID, int shardId) {
        // Example path : /repo/indices/<indexUUID>/<shardId>/snap-<snapshotId>.dat
        return getShardDirPath(root, indexUUID, shardId).resolve("snap-" + snapshotId + ".dat");
    }

    /**
     * Resolves a Lucene blob file.
     */
    @Override
    public Path getBlobFilePath(Path root, String indexUUID, int shardId, String blobName) {
        // Example path : /repo/indices/<indexUUID>/<shardId>/__XYZ
        return getShardDirPath(root, indexUUID, shardId).resolve(blobName);
    }

    /**
     * Extracts the numeric N from "index-N".
     * Throws if the format is invalid.
     */
    protected int extractIndexVersion(String fileName) {
        Matcher matcher = INDEX_PATTERN.matcher(fileName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        throw new IllegalArgumentException("Invalid index file name: " + fileName);
    }

    public static class CantFindRepoIndexFile extends RfsException {
        public CantFindRepoIndexFile() {
            super("Can't find the repo index file in the repo directory");
        }
    }
}
