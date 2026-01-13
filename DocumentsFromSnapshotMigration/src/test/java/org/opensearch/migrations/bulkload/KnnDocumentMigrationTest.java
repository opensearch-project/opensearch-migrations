package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.ArrayList;
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
 * Tests KNN document extraction from OpenSearch snapshots with various engines, 
 * space types, encoders, and index codecs.
 */
@Tag("isolatedTest")
@Slf4j
public class KnnDocumentMigrationTest extends SourceTestBase {

    @TempDir
    private File localDirectory;

    private static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.OS_V1_3_20, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.OS_V2_19_4, SearchClusterContainer.OS_V3_0_0)
        );
    }

    private static Stream<Arguments> extendedScenarios() {
        return SupportedClusters.extendedSources().stream()
            .filter(v -> VersionMatchers.isOS_1_X.test(v.getVersion()) || VersionMatchers.isOS_2_X.test(v.getVersion()))
            .map(Arguments::of);
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
        var sourceVersion = sourceCluster.getContainerVersion().getVersion();

        // Create KNN indices with various configurations
        var knnConfigs = generateKnnIndexConfigs(sourceVersion);
        for (var config : knnConfigs) {
            log.info("Creating KNN index: {}", config.name);
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
        checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);
    }

    /**
     * Generates KNN index configurations based on source version capabilities.
     * Tests all supported combinations of engines, space types, encoders, and codecs.
     */
    private List<KnnIndexConfig> generateKnnIndexConfigs(Version sourceVersion) {
        var configs = new ArrayList<KnnIndexConfig>();
        int dim = 4;

        // === NMSLIB engine (all versions, deprecated in 2.x) ===
        // Space types: l2, innerproduct, cosinesimil
        for (var spaceType : List.of("l2", "cosinesimil", "innerproduct")) {
            configs.add(knnConfig("knn-nmslib-" + spaceType, "nmslib", "hnsw", spaceType, dim, null, null));
        }

        // === Faiss engine (OS 2.x+) ===
        if (VersionMatchers.isOS_2_X.test(sourceVersion)) {
            // Faiss HNSW with different space types
            for (var spaceType : List.of("l2", "innerproduct")) {
                configs.add(knnConfig("knn-faiss-hnsw-" + spaceType, "faiss", "hnsw", spaceType, dim, null, null));
            }

            // Faiss with SQ encoder (OS 2.13+)
            if (isVersionAtLeast(sourceVersion, 2, 13)) {
                configs.add(knnConfig("knn-faiss-sq-fp16", "faiss", "hnsw", "l2", dim, "sq", "fp16"));
            }
        }

        // === Lucene engine (OS 2.x+) ===
        if (VersionMatchers.isOS_2_X.test(sourceVersion)) {
            for (var spaceType : List.of("l2", "cosinesimil")) {
                configs.add(knnConfig("knn-lucene-" + spaceType, "lucene", "hnsw", spaceType, dim, null, null));
            }
        }

        // === Index codec compression types (OS 2.9 only - zstd/zstd_no_dict not supported for KNN in 2.10+) ===
        if (sourceVersion.getMajor() == 2 && sourceVersion.getMinor() == 9) {
            configs.add(knnConfigWithCodec("knn-zstd", "faiss", "hnsw", "l2", dim, "zstd"));
            configs.add(knnConfigWithCodec("knn-zstd-nodict", "faiss", "hnsw", "l2", dim, "zstd_no_dict"));
        }

        // === Best compression codec (all versions) ===
        configs.add(knnConfigWithCodec("knn-best-compression", "nmslib", "hnsw", "l2", dim, "best_compression"));

        return configs;
    }

    private boolean isVersionAtLeast(Version version, int major, int minor) {
        return version.getMajor() > major || (version.getMajor() == major && version.getMinor() >= minor);
    }

    private KnnIndexConfig knnConfig(String name, String engine, String method, String spaceType, 
                                      int dim, String encoder, String encoderType) {
        String body = encoder != null 
            ? createKnnIndexBodyWithEncoder(engine, method, spaceType, dim, encoder, encoderType)
            : createKnnIndexBody(engine, method, spaceType, dim);
        return new KnnIndexConfig(name, body, createVectorDocument(dim));
    }

    private KnnIndexConfig knnConfigWithCodec(String name, String engine, String method, String spaceType,
                                               int dim, String codec) {
        return new KnnIndexConfig(name, createKnnIndexBodyWithCodec(engine, method, spaceType, dim, codec),
            createVectorDocument(dim));
    }

    private String createKnnIndexBody(String engine, String method, String spaceType, int dimension) {
        return String.format(
            "{\"settings\":{\"index\":{\"knn\":true,\"number_of_shards\":1,\"number_of_replicas\":0}}," +
            "\"mappings\":{\"properties\":{\"my_vector\":{\"type\":\"knn_vector\",\"dimension\":%d," +
            "\"method\":{\"name\":\"%s\",\"space_type\":\"%s\",\"engine\":\"%s\"," +
            "\"parameters\":{\"ef_construction\":100,\"m\":16}}}}}}",
            dimension, method, spaceType, engine);
    }

    private String createKnnIndexBodyWithEncoder(String engine, String method, String spaceType,
                                                  int dimension, String encoderName, String encoderType) {
        return String.format(
            "{\"settings\":{\"index\":{\"knn\":true,\"number_of_shards\":1,\"number_of_replicas\":0}}," +
            "\"mappings\":{\"properties\":{\"my_vector\":{\"type\":\"knn_vector\",\"dimension\":%d," +
            "\"method\":{\"name\":\"%s\",\"space_type\":\"%s\",\"engine\":\"%s\"," +
            "\"parameters\":{\"ef_construction\":100,\"m\":16," +
            "\"encoder\":{\"name\":\"%s\",\"parameters\":{\"type\":\"%s\"}}}}}}}}",
            dimension, method, spaceType, engine, encoderName, encoderType);
    }

    private String createKnnIndexBodyWithCodec(String engine, String method, String spaceType,
                                                int dimension, String codec) {
        return String.format(
            "{\"settings\":{\"index\":{\"knn\":true,\"number_of_shards\":1,\"number_of_replicas\":0,\"codec\":\"%s\"}}," +
            "\"mappings\":{\"properties\":{\"my_vector\":{\"type\":\"knn_vector\",\"dimension\":%d," +
            "\"method\":{\"name\":\"%s\",\"space_type\":\"%s\",\"engine\":\"%s\"," +
            "\"parameters\":{\"ef_construction\":100,\"m\":16}}}}}}",
            codec, dimension, method, spaceType, engine);
    }

    private String createVectorDocument(int dimension) {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < dimension; i++) {
            vector.append(i == 0 ? "" : ",").append(String.format("%.4f", (i + 1) * 0.1));
        }
        vector.append("]");
        return String.format("{\"my_vector\":%s,\"title\":\"test doc\"}", vector);
    }

    private record KnnIndexConfig(String name, String body, String document) {}
}
