package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * Base implementation of SnapshotFileFInder with default logic
 * based on snapshot structure supported by ES 5x to OS 2x :
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
@Slf4j
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
        Pattern indexPattern = getSnapshotRepoDataIndexPattern();
        log.atInfo().setMessage("BaseSnapshotFileFinder: Look for files to match index pattern {}: {}")
            .addArgument(indexPattern)
            .addArgument(fileNames)
            .log();

        List<String> matchingFiles = fileNames.stream()
            .filter(name -> indexPattern.matcher(name).matches())
            .toList();
        if (!matchingFiles.isEmpty()) {
            log.atInfo().setMessage("BaseSnapshotFileFinder: Matching index files: {}")
                .addArgument(matchingFiles)
                .log();
        }

        return matchingFiles.stream()
            .max(Comparator.comparingInt(this::extractIndexVersion))
            .map(name -> {
                log.atInfo().setMessage("BaseSnapshotFileFinder: Selected snapshot repo index file = {}")
                    .addArgument(name)
                    .log();
                return root.resolve(name);
            })
            .orElseThrow(() -> {
                log.atError().setMessage("BaseSnapshotFileFinder: No matching index-N file found. Pattern: {}, All files: {}, Matching candidates: {}")
                    .addArgument(indexPattern)
                    .addArgument(fileNames)
                    .addArgument(matchingFiles)
                    .log();
                return new CannotFindRepoIndexFile("No matching index-N file found in repo. Matching candidates: " + matchingFiles);
            });
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
        Matcher matcher = getSnapshotRepoDataIndexPattern().matcher(fileName);
        if (matcher.find()) {
            try {
                int version = Integer.parseInt(matcher.group(1));
                log.atDebug().setMessage("Parsed index version {} from file: {}")
                        .addArgument(version)
                        .addArgument(fileName)
                        .log();
                return version;
            } catch (NumberFormatException e) {
                log.atWarn().setMessage("Failed to parse numeric suffix from file: {}").addArgument(fileName).log();
                throw e;
            }
        }
        log.atWarn().setMessage("File {} did not match expected pattern: {}")
            .addArgument(fileName)
            .addArgument(getSnapshotRepoDataIndexPattern())
            .log();
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
