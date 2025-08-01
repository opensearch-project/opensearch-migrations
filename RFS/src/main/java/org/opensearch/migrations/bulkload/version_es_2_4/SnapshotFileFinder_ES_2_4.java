package org.opensearch.migrations.bulkload.version_es_2_4;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.opensearch.migrations.bulkload.common.BaseSnapshotFileFinder;

import static org.opensearch.migrations.bulkload.version_es_2_4.ElasticsearchConstants_ES_2_4.INDICES_DIR_NAME;

/**
 * SnapshotFileFInder based on snapshot structure of ES 2x
 *
 * <pre>
 * /repo/
 *   ├── index                                  ----[repo metadata]
 *   ├── meta-<snapshotName>.dat                ----[cluster metadata]
 *   ├── snap-<snapshotName>.dat                ----[snapshot metadata]
 *   └── indices/                               ----[index directory]
 *       └── <indexName>/                       ----[unique directory per each index]
 *           └── meta-<snapshotName>.dat        ----[index metadata]
 *           └── <shardId>/                     ----[shard directory]
 *               ├── __<segmentIds>             ----[lucene blob file]
 *               ├── snap-<snapshotName>.dat    ----[shard metadata]
 *               └── ...
 * </pre>
 */
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
        // Example: /repo/index
        if (fileNames.contains("index")) {
            return root.resolve("index");
        }
        throw new CannotFindRepoIndexFile("ES 2.x repo is expected to contain a single 'index' file but none was found");
    }

    /** ES 2.x never has “index-N” */
    @Override
    protected int extractIndexVersion(String fileName) {
        throw new UnsupportedOperationException("ES 2.x uses a static 'index' file with no version");
    }

    @Override
    public Path getIndexMetadataFilePath(Path root, String indexName, String snapshotId) {
        // Example: /repo/indices/<indexName>/meta-<snapshotName>.dat
        return root.resolve(INDICES_DIR_NAME).resolve(indexName).resolve("meta-" + snapshotId + ".dat");
    }
}
