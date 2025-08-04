package org.opensearch.migrations.bulkload.version_es_1_7;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.opensearch.migrations.bulkload.common.BaseSnapshotFileFinder;

import static org.opensearch.migrations.bulkload.version_es_1_7.ElasticsearchConstants_ES_1_7.INDICES_DIR_NAME;
import static org.opensearch.migrations.bulkload.version_es_1_7.ElasticsearchConstants_ES_1_7.METADATA_PREFIX;
import static org.opensearch.migrations.bulkload.version_es_1_7.ElasticsearchConstants_ES_1_7.SNAPSHOT_PREFIX;

/**
 * SnapshotFileFInder based on snapshot structure of ES 1x
 *
 * <pre>
 * /repo/
 *   ├── index                                  ----[repo metadata]
 *   ├── metadata-<snapshotName>                ----[cluster metadata]
 *   ├── snapshot-<snapshotName>                ----[snapshot metadata]
 *   └── indices/                               ----[index directory]
 *       └── <indexName>/                       ----[unique directory per each index]
 *           └── snapshot-<snapshotName>        ----[index metadata]
 *           └── <shardId>/                     ----[shard directory]
 *               ├── __<segmentIds>             ----[lucene blob file]
 *               ├── snapshot-<snapshotName>    ----[shard metadata]
 *               └── ...
 * </pre>
 */
public class SnapshotFileFinder_ES_1_7 extends BaseSnapshotFileFinder {

    // ES 1.7 uses a static "index" file (no version suffix)
    private static final Pattern INDEX_PATTERN = Pattern.compile("^index$");

    @Override
    public Pattern getSnapshotRepoDataIndexPattern() {
        return INDEX_PATTERN;
    }

    @Override
    public Path getSnapshotRepoDataFilePath(Path root, List<String> fileNames) throws CannotFindRepoIndexFile {
        if (fileNames.contains("index")) {
            return root.resolve("index");
        }
        throw new CannotFindRepoIndexFile("ES 1.x repo is expected to contain a single 'index' file but none was found");
    }

    /** ES 1.x never has “index-N” */
    @Override
    protected int extractIndexVersion(String fileName) {
        throw new UnsupportedOperationException("ES 1.x does not use index-N files.");
    }

    @Override
    public Path getGlobalMetadataFilePath(Path root, String snapshotId) {
        // /repo/metadata-<snapshotName>
        return root.resolve(METADATA_PREFIX + snapshotId);
    }

    @Override
    public Path getSnapshotMetadataFilePath(Path root, String snapshotId) {
        // /repo/snapshot-<snapshotName>
        return root.resolve(SNAPSHOT_PREFIX + snapshotId);
    }

    @Override
    public Path getIndexMetadataFilePath(Path root, String indexName, String snapshotId) {
        // /repo/indices/<indexName>/snap-<snapshotName>
        return root.resolve(INDICES_DIR_NAME).resolve(indexName).resolve(SNAPSHOT_PREFIX + snapshotId);
    }

    @Override
    public Path getShardMetadataFilePath(Path root, String snapshotId, String indexName, int shardId) {
        // /repo/indices/<indexName>/<shardId>/snapshot-<snapshotName>
        return getShardDirPath(root, indexName, shardId).resolve(SNAPSHOT_PREFIX + snapshotId);
    }
}
