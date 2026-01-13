package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end test for KNN plugin support during document migration.
 * Tests KNN document extraction from OpenSearch snapshots.
 * 
 * NOTE: This test is expected to fail until KNN document extraction is implemented.
 */
@Tag("isolatedTest")
@Slf4j
public class KnnDocumentMigrationTest extends SourceTestBase {

    @TempDir
    private File localDirectory;

    private static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.OS_V1_3_16, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.OS_V2_19_1, SearchClusterContainer.OS_V3_0_0)
        );
    }

    private static Stream<Arguments> extendedScenarios() {
        return Stream.of(
            SearchClusterContainer.OS_V2_0_1,
            SearchClusterContainer.OS_V2_1_0,
            SearchClusterContainer.OS_V2_2_1,
            SearchClusterContainer.OS_V2_3_0,
            SearchClusterContainer.OS_V2_4_1,
            SearchClusterContainer.OS_V2_5_0,
            SearchClusterContainer.OS_V2_6_0,
            SearchClusterContainer.OS_V2_7_0,
            SearchClusterContainer.OS_V2_8_0,
            SearchClusterContainer.OS_V2_9_0,
            SearchClusterContainer.OS_V2_10_0,
            SearchClusterContainer.OS_V2_11_1,
            SearchClusterContainer.OS_V2_12_0,
            SearchClusterContainer.OS_V2_13_0,
            SearchClusterContainer.OS_V2_14_0,
            SearchClusterContainer.OS_V2_15_0,
            SearchClusterContainer.OS_V2_16_0,
            SearchClusterContainer.OS_V2_17_1,
            SearchClusterContainer.OS_V2_18_0
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "KNN Documents From {0} to {1}")
    @MethodSource("scenarios")
    void knnDocumentMigrationTest(ContainerVersion sourceVersion, ContainerVersion targetVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            migrateKnnDocuments(sourceCluster, targetCluster);
        }
    }

    @ParameterizedTest(name = "KNN Documents From {0} to OS 3.0.0")
    @MethodSource("extendedScenarios")
    void extendedKnnDocumentMigrationTest(ContainerVersion sourceVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V3_0_0)
        ) {
            migrateKnnDocuments(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void migrateKnnDocuments(SearchClusterContainer sourceCluster, SearchClusterContainer targetCluster) {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();

        Startables.deepStart(sourceCluster, targetCluster).join();

        var sourceOperations = new ClusterOperations(sourceCluster);
        var targetOperations = new ClusterOperations(targetCluster);
        var sourceVersion = sourceCluster.getContainerVersion().getVersion();

        // Create KNN index with documents
        var knnConfigs = createKnnIndexConfigs(sourceVersion);
        for (var config : knnConfigs) {
            sourceOperations.createIndex(config.name, config.body);
            sourceOperations.createDocument(config.name, "1", config.document);
        }
        sourceOperations.post("/_refresh", null);

        // Take snapshot
        var snapshotName = "knn_doc_snap";
        createSnapshot(sourceCluster, snapshotName, snapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());

        var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(sourceVersion, true);
        var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);

        // Migrate documents
        var runCounter = new AtomicInteger();
        var clockJitter = new Random(1);

        var expectedTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
            sourceRepo,
            snapshotName,
            List.of(),
            targetCluster,
            runCounter,
            clockJitter,
            testDocMigrationContext,
            sourceVersion,
            targetCluster.getContainerVersion().getVersion(),
            null
        ));

        assertNotNull(expectedTerminationException, "Migration should complete");

        // Verify documents migrated
        checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);
    }

    private List<KnnIndexConfig> createKnnIndexConfigs(Version sourceVersion) {
        var configs = new java.util.ArrayList<KnnIndexConfig>();

        configs.add(new KnnIndexConfig("knn-test-index", createKnnIndexBody(
            "nmslib", "hnsw", "l2", 4, 100, 16),
            createVectorDocument(4)));

        if (VersionMatchers.isOS_2_X.test(sourceVersion)) {
            configs.add(new KnnIndexConfig("knn-faiss-index", createKnnIndexBody(
                "faiss", "hnsw", "l2", 4, 100, 16),
                createVectorDocument(4)));
        }

        return configs;
    }

    private String createKnnIndexBody(String engine, String method, String spaceType,
                                       int dimension, int efConstruction, int m) {
        return String.format(
            "{" +
            "  \"settings\": {" +
            "    \"index\": {" +
            "      \"knn\": true," +
            "      \"number_of_shards\": 1," +
            "      \"number_of_replicas\": 0" +
            "    }" +
            "  }," +
            "  \"mappings\": {" +
            "    \"properties\": {" +
            "      \"my_vector\": {" +
            "        \"type\": \"knn_vector\"," +
            "        \"dimension\": %d," +
            "        \"method\": {" +
            "          \"name\": \"%s\"," +
            "          \"space_type\": \"%s\"," +
            "          \"engine\": \"%s\"," +
            "          \"parameters\": {" +
            "            \"ef_construction\": %d," +
            "            \"m\": %d" +
            "          }" +
            "        }" +
            "      }" +
            "    }" +
            "  }" +
            "}", dimension, method, spaceType, engine, efConstruction, m);
    }

    private String createVectorDocument(int dimension) {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < dimension; i++) {
            vector.append(i == 0 ? "" : ",").append(String.format("%.4f", (i + 1) * 0.1));
        }
        vector.append("]");
        return String.format("{\"my_vector\": %s, \"title\": \"test doc\"}", vector);
    }

    private record KnnIndexConfig(String name, String body, String document) {}
}
