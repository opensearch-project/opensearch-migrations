package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * Base implementation of SnapshotFileFInder with default logic
 * based on snapshot structure belonging to ES 5/6/7/8 and OS 1/2
 *
 * <pre>
 * /repo/
 *   ├── index-9                              ----[repo metadata]
 *   ├── index.latest
 *   ├── meta-<snapshotId>.dat                ----[cluster metadata]
 *   ├── snap-<snapshotId>.dat                ----[snapshot metadata]
 *   └── indices/                             ----[index directory]
 *       └── <indexUUID>/                     ----[unique indexUUID per each index]
 *           └── meta-<snapshotId>.dat        ----[index metadata]
 *           └── <shardId>/                   ----[shard directory]
 *               ├── __<segmentIds>           ----[lucene blob file]
 *               ├── snap-<snapshotId>.dat    ----[shard metadata]
 *               └── ...
 * </pre>
 */
@Slf4j
public class BaseSnapshotFileFinder implements SnapshotFileFinder {

    private static final Pattern INDEX_PATTERN = Pattern.compile("^index-(\\d+)$");

    @Override
    public Pattern getSnapshotRepoDataIndexPattern() {
        return INDEX_PATTERN;
    }

    /**
     * Resolves the snapshot repository metadata file (index-N with highest N).
     */
    @Override
    public Path getSnapshotRepoDataFilePath(Path root, List<String> fileNames) {
        // Example path : /repo/index-9
        Pattern indexPattern = getSnapshotRepoDataIndexPattern();

        List<String> matchingFiles = fileNames.stream()
            .filter(name -> indexPattern.matcher(name).matches())
            .toList();

        return matchingFiles.stream()
            .max(Comparator.comparingInt(this::extractIndexVersion))
            .map(root::resolve)
            .orElseThrow(() -> new CannotFindRepoIndexFile(
                "No matching index-N file found in repo. Matching candidates: " + matchingFiles));
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
        // Example path : /repo/indices/<indexUUID>/meta-<snapshotId>.dat
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
    protected int extractIndexVersion(String fileName) throws NumberFormatException {
        Matcher matcher = getSnapshotRepoDataIndexPattern().matcher(fileName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        throw new IllegalArgumentException("Invalid index file name: " + fileName +
            ". Expected pattern: " + getSnapshotRepoDataIndexPattern());
    }

    public static class CannotFindRepoIndexFile extends RfsException {
        public CannotFindRepoIndexFile() {
            super("Can't find the repo index file in the repo directory");
        }
        public CannotFindRepoIndexFile(String message) {
            super(message);
        }
    }
}
