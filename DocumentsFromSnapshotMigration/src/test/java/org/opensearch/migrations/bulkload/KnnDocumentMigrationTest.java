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
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
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
 * Tests KNN plugin document migration from OpenSearch 1.x/2.x to OpenSearch 3.0+.
 * 
 * <h2>Supported KNN Configurations</h2>
 * <table border="1">
 *   <tr><th>Engine</th><th>Versions</th><th>Space Types</th><th>Notes</th></tr>
 *   <tr><td>nmslib</td><td>OS 1.x+</td><td>l2, cosinesimil, innerproduct</td><td>Deprecated in 2.x</td></tr>
 *   <tr><td>faiss</td><td>OS 2.x+</td><td>l2, innerproduct</td><td>SQ encoder in 2.13+</td></tr>
 *   <tr><td>lucene</td><td>OS 2.4+</td><td>l2, cosinesimil</td><td></td></tr>
 * </table>
 * 
 * <h2>Index Codecs</h2>
 * <ul>
 *   <li>default, best_compression - Supported</li>
 *   <li>zstd, zstd_no_dict - Supported (requires zstd-jni decompression)</li>
 * </ul>
 */
@Tag("isolatedTest")
@Slf4j
public class KnnDocumentMigrationTest extends SourceTestBase {

    @TempDir
    private File localDirectory;

    /** KNN engine availability by OpenSearch version */
    enum KnnEngine {
        NMSLIB("nmslib", v -> true),                                    // All versions with KNN
        FAISS("faiss", VersionMatchers.isOS_2_X::test),                 // OS 2.x+
        LUCENE("lucene", v -> VersionMatchers.isOS_2_X.test(v) && isAtLeast(v, 2, 4));  // OS 2.4+

        final String name;
        final java.util.function.Predicate<Version> isAvailable;
        KnnEngine(String name, java.util.function.Predicate<Version> isAvailable) {
            this.name = name;
            this.isAvailable = isAvailable;
        }
    }

    /** Index codec availability - zstd only works in OS 2.9 (custom-codecs plugin conflicts with KNN in 2.10+) */
    enum IndexCodec {
        DEFAULT(null, v -> true),
        BEST_COMPRESSION("best_compression", v -> true),
        // zstd codecs only available in OS 2.9 (custom-codecs plugin conflicts with KNN starting in 2.10)
        ZSTD("zstd", v -> isAtLeast(v, 2, 9) && !isAtLeast(v, 2, 10)),
        ZSTD_NO_DICT("zstd_no_dict", v -> isAtLeast(v, 2, 9) && !isAtLeast(v, 2, 10));

        final String name;
        final java.util.function.Predicate<Version> isAvailable;
        IndexCodec(String name, java.util.function.Predicate<Version> isAvailable) {
            this.name = name;
            this.isAvailable = isAvailable;
        }
    }

    /** Standard scenarios - latest patch of each major version */
    static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.ODFE_V1_13_3, SearchClusterContainer.OS_V3_0_0), // ES 7.10.2 (latest ODFE)
            Arguments.of(SearchClusterContainer.OS_V1_3_20, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.OS_V2_19_4, SearchClusterContainer.OS_V3_0_0)
        );
    }

    /** Extended scenarios - all other ODFE/OS versions with KNN support */
    static Stream<Arguments> extendedScenarios() {
        return Stream.of(
            // ODFE versions (ES 7.4 - 7.9)
            Arguments.of(SearchClusterContainer.ODFE_V1_4_0, SearchClusterContainer.OS_V3_0_0),   // ES 7.4.2
            Arguments.of(SearchClusterContainer.ODFE_V1_7_0, SearchClusterContainer.OS_V3_0_0),   // ES 7.6.1
            Arguments.of(SearchClusterContainer.ODFE_V1_8_0, SearchClusterContainer.OS_V3_0_0),   // ES 7.7.0
            Arguments.of(SearchClusterContainer.ODFE_V1_9_0, SearchClusterContainer.OS_V3_0_0),   // ES 7.8.0
            Arguments.of(SearchClusterContainer.ODFE_V1_11_0, SearchClusterContainer.OS_V3_0_0), // ES 7.9.1
            // OS 2.9 for zstd codec testing
            Arguments.of(SearchClusterContainer.OS_V2_9_0, SearchClusterContainer.OS_V3_0_0)
        );
    }

    @ParameterizedTest(name = "KNN: {0} -> {1}")
    @MethodSource("scenarios")
    void knnDocumentMigration(ContainerVersion sourceVersion, ContainerVersion targetVersion) {
        try (
            var sourceCluster = new SearchClusterContainer(sourceVersion);
            var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            migrateKnnDocuments(sourceCluster, targetCluster);
        }
    }

    @ParameterizedTest(name = "KNN Extended: {0} -> {1}")
    @MethodSource("extendedScenarios")
    void knnDocumentMigrationExtended(ContainerVersion sourceVersion, ContainerVersion targetVersion) {
        try (
            var sourceCluster = new SearchClusterContainer(sourceVersion);
            var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            migrateKnnDocuments(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void migrateKnnDocuments(SearchClusterContainer sourceCluster, SearchClusterContainer targetCluster) {
        Startables.deepStart(sourceCluster, targetCluster).join();

        var sourceOps = new ClusterOperations(sourceCluster);
        var sourceVersion = sourceCluster.getContainerVersion().getVersion();

        // Create KNN indices
        var configs = generateConfigs(sourceVersion);
        for (var cfg : configs) {
            log.info("Creating KNN index: {}", cfg.name);
            sourceOps.createIndex(cfg.name, cfg.body);
            sourceOps.createDocument(cfg.name, "1", cfg.doc);
        }
        sourceOps.post("/_refresh", null);

        // Snapshot and migrate
        var snapshotName = "knn_snap";
        createSnapshot(sourceCluster, snapshotName, SnapshotTestContext.factory().noOtelTracking());
        sourceCluster.copySnapshotData(localDirectory.toString());

        var sourceRepo = new FileSystemRepo(localDirectory.toPath(),
            SnapshotReaderRegistry.getSnapshotFileFinder(sourceVersion, true));
        var docCtx = DocumentMigrationTestContext.factory().noOtelTracking();

        var result = waitForRfsCompletion(() -> migrateDocumentsSequentially(
            sourceRepo, snapshotName, List.of(), targetCluster,
            new AtomicInteger(), new Random(1), docCtx,
            sourceVersion, targetCluster.getContainerVersion().getVersion(), null
        ));

        assertNotNull(result, "Migration should complete");
        checkClusterMigrationOnFinished(sourceCluster, targetCluster, docCtx);
    }

    private List<KnnConfig> generateConfigs(Version v) {
        var configs = new ArrayList<KnnConfig>();
        int dim = 4;

        // ODFE/ES 7.10 uses simpler KNN format without method parameter
        boolean isOdfeOrEs7 = VersionMatchers.isES_7_X.test(v);

        // Test each available engine with its supported space types
        if (KnnEngine.NMSLIB.isAvailable.test(v)) {
            if (isOdfeOrEs7) {
                // ODFE uses simpler format
                configs.add(knnConfigSimple("knn-nmslib-l2", dim));
            } else {
                for (var space : List.of("l2", "cosinesimil", "innerproduct")) {
                    configs.add(knnConfig("knn-nmslib-" + space, "nmslib", space, dim, null));
                }
            }
        }

        if (KnnEngine.FAISS.isAvailable.test(v)) {
            for (var space : List.of("l2", "innerproduct")) {
                configs.add(knnConfig("knn-faiss-" + space, "faiss", space, dim, null));
            }
        }

        if (KnnEngine.LUCENE.isAvailable.test(v)) {
            for (var space : List.of("l2", "cosinesimil")) {
                configs.add(knnConfig("knn-lucene-" + space, "lucene", space, dim, null));
            }
        }

        // Test available codecs (not for ODFE/ES7)
        if (!isOdfeOrEs7) {
            if (IndexCodec.BEST_COMPRESSION.isAvailable.test(v)) {
                configs.add(knnConfig("knn-best-compression", "nmslib", "l2", dim, "best_compression"));
            }
            if (IndexCodec.ZSTD.isAvailable.test(v)) {
                configs.add(knnConfig("knn-zstd", "faiss", "l2", dim, "zstd"));
            }
            if (IndexCodec.ZSTD_NO_DICT.isAvailable.test(v)) {
                configs.add(knnConfig("knn-zstd-nodict", "faiss", "l2", dim, "zstd_no_dict"));
            }
        }

        return configs;
    }

    private static boolean isAtLeast(Version v, int major, int minor) {
        return v.getMajor() > major || (v.getMajor() == major && v.getMinor() >= minor);
    }

    /** Simple KNN config for ODFE/ES 7.x (no method parameter) */
    private KnnConfig knnConfigSimple(String name, int dim) {
        String body = String.format(
            "{\"settings\":{\"index\":{\"knn\":true,\"number_of_shards\":1,\"number_of_replicas\":0}}," +
            "\"mappings\":{\"properties\":{\"my_vector\":{\"type\":\"knn_vector\",\"dimension\":%d}}}}",
            dim);
        String doc = "{\"my_vector\":[0.1,0.2,0.3,0.4],\"title\":\"test\"}";
        return new KnnConfig(name, body, doc);
    }

    private KnnConfig knnConfig(String name, String engine, String spaceType, int dim, String codec) {
        String settings = codec != null
            ? String.format("\"knn\":true,\"number_of_shards\":1,\"number_of_replicas\":0,\"codec\":\"%s\"", codec)
            : "\"knn\":true,\"number_of_shards\":1,\"number_of_replicas\":0";

        String body = String.format(
            "{\"settings\":{\"index\":{%s}}," +
            "\"mappings\":{\"properties\":{\"my_vector\":{\"type\":\"knn_vector\",\"dimension\":%d," +
            "\"method\":{\"name\":\"hnsw\",\"space_type\":\"%s\",\"engine\":\"%s\"," +
            "\"parameters\":{\"ef_construction\":100,\"m\":16}}}}}}",
            settings, dim, spaceType, engine);

        String doc = String.format("{\"my_vector\":[0.1,0.2,0.3,0.4],\"title\":\"test\"}");
        return new KnnConfig(name, body, doc);
    }

    private record KnnConfig(String name, String body, String doc) {}
}
