package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.SnapshotCreator;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SnapshotFixtureCache;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSourceContractTest;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the source contract against a real ES snapshot. Container-backed, so it carries the same
 * {@code isolatedTest} tag as the other snapshot end-to-end tests.
 */
@Tag("isolatedTest")
class LuceneSnapshotSourceContractTest extends DocumentSourceContractTest {

    private static final SearchClusterContainer.ContainerVersion SOURCE_VERSION =
        SearchClusterContainer.ES_V7_10_2;
    private static final String SNAPSHOT_NAME = "contract_snapshot";
    private static final String REPO_NAME = "contract_repo";
    private static final String INDEX_NAME = "contract_index";

    private static final SnapshotFixtureCache FIXTURE_CACHE = new SnapshotFixtureCache();

    @TempDir
    File snapshotDirectory;

    @TempDir
    Path workDir;

    private SnapshotExtractor extractor;
    private int sourceCount;

    @Override
    protected DocumentSource newSource() throws Exception {
        if (extractor == null) {
            extractor = createSnapshot();
        }
        // Sources that coexist get their own work directory.
        var ownWorkDir = Files.createDirectories(workDir.resolve("source-" + sourceCount++));
        return LuceneSnapshotSource.builder(extractor, SNAPSHOT_NAME, ownWorkDir).build();
    }

    @Override
    protected String collectionUnderTest() {
        return INDEX_NAME;
    }

    private SnapshotExtractor createSnapshot() throws Exception {
        var snapshotDir = snapshotDirectory.toPath();
        var cacheKey = SOURCE_VERSION.getVersion() + "-source-contract";
        if (FIXTURE_CACHE.restoreIfCached(cacheKey, snapshotDir)) {
            return SnapshotExtractor.forLocalSnapshot(snapshotDir, SOURCE_VERSION.getVersion());
        }

        try (var cluster = new SearchClusterContainer(SOURCE_VERSION)) {
            cluster.start();
            var ops = new ClusterOperations(cluster);
            // Three docs give the resume assertions something after the first.
            ops.createIndex(INDEX_NAME,
                "{\"settings\": {\"number_of_shards\": 1, \"number_of_replicas\": 0}}");
            ops.createDocument(INDEX_NAME, "doc1", "{\"title\": \"First\"}");
            ops.createDocument(INDEX_NAME, "doc2", "{\"title\": \"Second\"}");
            ops.createDocument(INDEX_NAME, "doc3", "{\"title\": \"Third\"}");
            ops.post("/" + INDEX_NAME + "/_refresh", null);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(cluster.getUrl()).insecure(true).build().toConnectionContext());
            var snapshotCreator = new SnapshotCreator(
                SNAPSHOT_NAME, REPO_NAME, clientFactory.determineVersionAndCreate(),
                RepoUri.parse(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR), List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            cluster.copySnapshotData(snapshotDirectory.toString());
            FIXTURE_CACHE.store(cacheKey, snapshotDir);
        }

        return SnapshotExtractor.forLocalSnapshot(snapshotDir, SOURCE_VERSION.getVersion());
    }
}
