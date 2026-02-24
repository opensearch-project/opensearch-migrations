package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.framework.SnapshotFixtureCache;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.MetadataMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.MigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchMetadataSink;
import org.opensearch.migrations.bulkload.pipeline.adapter.SnapshotMetadataSource;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Full pipeline e2e tests: real snapshot → {@link MigrationPipeline} → real cluster.
 *
 * <p>Uses representative source→target pairs from {@link SupportedClusters#smokePairs()}
 * to validate the complete pipeline wiring end-to-end.
 */
@Slf4j
@Tag("isolatedTest")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
public class PipelineEndToEndTest {

    private static final String SNAPSHOT_NAME = "test_snapshot";
    private static final String REPO_NAME = "test_repo";
    private static final String INDEX_NAME = "pipeline_e2e";
    private static final String COMPLEX_INDEX = "pipeline_complex";

    @TempDir private File localDirectory;

    private static final SnapshotFixtureCache fixtureCache = new SnapshotFixtureCache();

    static Stream<Arguments> smokePairs() {
        return SupportedClusters.smokePairs().stream()
            .map(pair -> Arguments.of(pair.source(), pair.target()));
    }

    // --- Full pipeline tests ---

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("smokePairs")
    void fullPipelineMigration(ContainerVersion sourceVersion, ContainerVersion targetVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        Path workDir = Files.createTempDirectory("pipeline_e2e_full");

        try (var targetCluster = new SearchClusterContainer(targetVersion)) {
            targetCluster.start();
            var targetClient = createClient(targetCluster);

            var source = new LuceneSnapshotSource(extractor, SNAPSHOT_NAME, workDir);
            var sink = new OpenSearchDocumentSink(targetClient);
            var pipeline = new MigrationPipeline(source, sink, 1000, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have progress cursors", cursors.size(), greaterThan(0));
            for (var cursor : cursors) {
                assertThat(cursor.docsInBatch(), greaterThan(0L));
            }

            verifyDocCount(targetCluster, INDEX_NAME, 5);
            log.info("Full pipeline migration {} → {} complete: 5 docs", sourceVersion, targetVersion);
        } finally {
            deleteDir(workDir);
        }
    }

    @ParameterizedTest(name = "batching: {0} → {1}")
    @MethodSource("smokePairs")
    void pipelineWithSmallBatches(ContainerVersion sourceVersion, ContainerVersion targetVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        Path workDir = Files.createTempDirectory("pipeline_e2e_batch");

        try (var targetCluster = new SearchClusterContainer(targetVersion)) {
            targetCluster.start();
            var targetClient = createClient(targetCluster);

            var source = new LuceneSnapshotSource(extractor, SNAPSHOT_NAME, workDir);
            var sink = new OpenSearchDocumentSink(targetClient);
            // Batch size of 2 → should produce 3 batches for 5 docs
            var pipeline = new MigrationPipeline(source, sink, 2, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have multiple batches", cursors.size(), greaterThan(1));
            verifyDocCount(targetCluster, INDEX_NAME, 5);
            log.info("Batched pipeline {} → {}: {} batches for 5 docs", sourceVersion, targetVersion, cursors.size());
        } finally {
            deleteDir(workDir);
        }
    }

    @ParameterizedTest(name = "metadata: {0} → {1}")
    @MethodSource("smokePairs")
    void metadataPipelineMigration(ContainerVersion sourceVersion, ContainerVersion targetVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);

        try (var targetCluster = new SearchClusterContainer(targetVersion)) {
            targetCluster.start();
            var targetClient = createClient(targetCluster);

            var source = new SnapshotMetadataSource(extractor, SNAPSHOT_NAME);
            var sink = new OpenSearchMetadataSink(targetClient);
            var pipeline = new MetadataMigrationPipeline(source, sink);

            var migratedIndices = pipeline.migrateAll().collectList().block();

            assertThat("Should migrate our index", migratedIndices.contains(INDEX_NAME), equalTo(true));

            // Verify index was created on target
            var restClient = createRestClient(targetCluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var resp = restClient.get(INDEX_NAME, context.createUnboundRequestContext());
            assertThat("Index should exist on target", resp.statusCode, equalTo(200));

            log.info("Metadata pipeline {} → {} complete: migrated {}", sourceVersion, targetVersion, migratedIndices);
        }
    }

    // --- Complex scenario tests ---

    @ParameterizedTest(name = "complex: {0} → {1}")
    @MethodSource("smokePairs")
    void pipelineWithComplexData(ContainerVersion sourceVersion, ContainerVersion targetVersion) throws Exception {
        var extractor = createComplexSnapshot(sourceVersion);
        Path workDir = Files.createTempDirectory("pipeline_e2e_complex");

        try (var targetCluster = new SearchClusterContainer(targetVersion)) {
            targetCluster.start();
            var targetClient = createClient(targetCluster);

            var source = new LuceneSnapshotSource(extractor, SNAPSHOT_NAME, workDir);
            var sink = new OpenSearchDocumentSink(targetClient);
            var pipeline = new MigrationPipeline(source, sink, 1000, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();
            assertThat("Should have progress cursors", cursors.size(), greaterThan(0));

            Version srcVersion = sourceVersion.getVersion();
            boolean supportsSoftDeletes = VersionMatchers.equalOrGreaterThanES_6_5.test(srcVersion);
            boolean supportsCompletion = !UnboundVersionMatchers.isBelowES_2_X.test(srcVersion);
            boolean isEs5SingleType = VersionMatchers.isES_5_X.test(srcVersion);

            // Verify main index: 2 large + 4 regular + 1 remaining - 1 deleted (if soft deletes)
            int expectedDocs = supportsSoftDeletes ? 7 : 8;
            verifyDocCount(targetCluster, COMPLEX_INDEX, expectedDocs);

            // Verify routing
            var restClient = createRestClient(targetCluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", context.createUnboundRequestContext());
            var requests = new SearchClusterRequests(context);
            var hits = requests.searchIndexByQueryString(restClient, COMPLEX_INDEX, "active:true", "1");
            assertThat("Routing search should find docs", hits.size(), greaterThan(0));

            // Verify completion index
            if (supportsCompletion) {
                verifyDocCount(targetCluster, "completion_pipeline", 1);
            }

            // Verify ES5 single-type index
            if (isEs5SingleType) {
                verifyDocCount(targetCluster, "es5_single_type_pipeline", 2);
            }

            log.info("Complex pipeline {} → {} complete", sourceVersion, targetVersion);
        } finally {
            deleteDir(workDir);
        }
    }

    // --- Helpers ---

    private SnapshotExtractor createSnapshot(ContainerVersion sourceVersion) throws Exception {
        String cacheKey = sourceVersion.getVersion() + "-pipeline-e2e";
        Path snapshotDir = localDirectory.toPath();

        if (fixtureCache.restoreIfCached(cacheKey, snapshotDir)) {
            return SnapshotExtractor.forLocalSnapshot(snapshotDir, sourceVersion.getVersion());
        }

        try (var cluster = new SearchClusterContainer(sourceVersion)) {
            cluster.start();
            var ops = new ClusterOperations(cluster);

            ops.createIndex(INDEX_NAME, "{"
                + "\"settings\": {"
                + "  \"number_of_shards\": 1,"
                + "  \"number_of_replicas\": 0"
                + "}"
                + "}");
            for (int i = 1; i <= 5; i++) {
                ops.createDocument(INDEX_NAME, "doc" + i,
                    String.format("{\"title\": \"Doc %d\", \"value\": %d}", i, i));
            }
            ops.post("/" + INDEX_NAME + "/_refresh", null);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(cluster.getUrl()).insecure(true).build().toConnectionContext());
            var snapshotCreator = new FileSystemSnapshotCreator(
                SNAPSHOT_NAME, REPO_NAME, clientFactory.determineVersionAndCreate(),
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            cluster.copySnapshotData(localDirectory.toString());
            fixtureCache.store(cacheKey, snapshotDir);
        }

        return SnapshotExtractor.forLocalSnapshot(snapshotDir, sourceVersion.getVersion());
    }

    private static org.opensearch.migrations.bulkload.common.OpenSearchClient createClient(
        SearchClusterContainer cluster
    ) {
        var connectionContext = ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext();
        return new OpenSearchClientFactory(connectionContext).determineVersionAndCreate();
    }

    private static RestClient createRestClient(SearchClusterContainer cluster) {
        return new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext());
    }

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = createRestClient(cluster);
        restClient.get("_refresh", context.createUnboundRequestContext());
        var requests = new SearchClusterRequests(context);
        var counts = requests.getMapOfIndexAndDocCount(restClient);
        assertEquals(expected, counts.getOrDefault(indexName, 0),
            "Expected " + expected + " docs in " + indexName);
    }

    private SnapshotExtractor createComplexSnapshot(ContainerVersion sourceVersion) throws Exception {
        String cacheKey = sourceVersion.getVersion() + "-pipeline-e2e-complex";
        Path snapshotDir = localDirectory.toPath();

        if (fixtureCache.restoreIfCached(cacheKey, snapshotDir)) {
            return SnapshotExtractor.forLocalSnapshot(snapshotDir, sourceVersion.getVersion());
        }

        try (var cluster = new SearchClusterContainer(sourceVersion)) {
            cluster.start();
            var ops = new ClusterOperations(cluster);
            Version srcVersion = sourceVersion.getVersion();
            boolean supportsSoftDeletes = VersionMatchers.equalOrGreaterThanES_6_5.test(srcVersion);
            boolean supportsCompletion = !UnboundVersionMatchers.isBelowES_2_X.test(srcVersion);
            boolean isEs5SingleType = VersionMatchers.isES_5_X.test(srcVersion);

            // Main index with large docs, routing, and deletes
            String indexBody = String.format(
                "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0%s}}",
                supportsSoftDeletes ? ",\"index.soft_deletes.enabled\":true" : ""
            );
            ops.createIndex(COMPLEX_INDEX, indexBody);

            // Large documents (2MB+)
            String largeDoc = generateLargeDocJson(2);
            ops.createDocument(COMPLEX_INDEX, "large1", largeDoc);
            ops.createDocument(COMPLEX_INDEX, "large2", largeDoc);

            // Regular docs with routing
            ops.createDocument(COMPLEX_INDEX, "r1", "{\"score\":42,\"active\":true}", "1", null);
            ops.createDocument(COMPLEX_INDEX, "r2", "{\"score\":55,\"active\":true}", "1", null);
            ops.createDocument(COMPLEX_INDEX, "r3", "{\"score\":60,\"active\":false}", "2", null);
            ops.createDocument(COMPLEX_INDEX, "r4", "{\"score\":77}", null, null);

            // Delete scenario
            ops.createDocument(COMPLEX_INDEX, "toDelete", "{\"score\":99,\"active\":true}", "1", null);
            ops.createDocument(COMPLEX_INDEX, "remaining", "{\"score\":88}", null, null);
            ops.post("/" + COMPLEX_INDEX + "/_refresh", null);
            ops.deleteDocument(COMPLEX_INDEX, "toDelete", "1", null);
            ops.post("/" + COMPLEX_INDEX + "/_refresh", null);

            // Completion field index
            if (supportsCompletion) {
                ops.createIndexWithCompletionField("completion_pipeline", 1);
                String docType = ops.defaultDocType();
                ops.createDocument("completion_pipeline", "1", "{\"completion\":\"bananas\"}", null, docType);
                ops.post("/completion_pipeline/_refresh", null);
            }

            // ES5 single-type index
            if (isEs5SingleType) {
                ops.createEs5SingleTypeIndexWithDocs("es5_single_type_pipeline");
            }

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(cluster.getUrl()).insecure(true).build().toConnectionContext());
            var snapshotCreator = new FileSystemSnapshotCreator(
                SNAPSHOT_NAME, REPO_NAME, clientFactory.determineVersionAndCreate(),
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            cluster.copySnapshotData(localDirectory.toString());
            fixtureCache.store(cacheKey, snapshotDir);
        }

        return SnapshotExtractor.forLocalSnapshot(snapshotDir, sourceVersion.getVersion());
    }

    private static String generateLargeDocJson(int sizeInMB) {
        int targetBytes = sizeInMB * 1024 * 1024;
        int bytesPerEntry = 8; // 7 digits + comma
        int numEntries = targetBytes / bytesPerEntry;
        StringBuilder sb = new StringBuilder(targetBytes + 100);
        sb.append("{\"numbers\":[");
        for (int i = 0; i < numEntries; i++) {
            sb.append("1000000");
            if (i < numEntries - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void deleteDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clean up: {}", dir, e);
        }
    }
}
