package org.opensearch.migrations;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for KNN plugin support during metadata migration.
 * Tests various KNN index configurations from OpenSearch 1.x and 2.x sources to OpenSearch 3.x target.
 */
@Tag("isolatedTest")
@Slf4j
class KnnPluginSupportTest extends BaseMigrationTest {

    @TempDir
    protected File localDirectory;

    private static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.OS_V1_3_20, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.OS_V2_19_4, SearchClusterContainer.OS_V3_0_0)
        );
    }

    @ParameterizedTest(name = "KNN Migration From {0} to {1}")
    @MethodSource("scenarios")
    void knnPluginSupportTest(ContainerVersion sourceVersion, ContainerVersion targetVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performKnnMigrationTest();
        }
    }

    @SneakyThrows
    private void performKnnMigrationTest() {
        startClusters();

        var sourceVersion = sourceCluster.getContainerVersion().getVersion();
        var knnConfigs = createKnnIndexConfigs(sourceVersion);
        for (var config : knnConfigs) {
            sourceOperations.createIndex(config.name, config.body);
            if (config.document != null) {
                sourceOperations.createDocument(config.name, "1", config.document);
            }
        }

        var snapshotName = "knn_plugin_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());
        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());

        MigrationItemResult evaluateResult = executeMigration(arguments, MetadataCommands.EVALUATE);
        log.info("EVALUATE result:\n{}", evaluateResult.asCliOutput());
        assertThat(evaluateResult.getExitCode(), equalTo(0));

        MigrationItemResult migrateResult = executeMigration(arguments, MetadataCommands.MIGRATE);
        log.info("MIGRATE result:\n{}", migrateResult.asCliOutput());
        assertThat(migrateResult.getExitCode(), equalTo(0));

        verifyKnnIndices(knnConfigs);
    }

    private List<KnnIndexConfig> createKnnIndexConfigs(Version sourceVersion) {
        var configs = new java.util.ArrayList<KnnIndexConfig>();

        // nmslib HNSW - will be auto-transformed to faiss for OS 3.0 target
        configs.add(new KnnIndexConfig("knn-nmslib-hnsw", createKnnIndexBody(
            "nmslib", "hnsw", "l2", 128, 100, 16),
            createVectorDocument(128)));

        configs.add(new KnnIndexConfig("knn-nmslib-cosine", createKnnIndexBody(
            "nmslib", "hnsw", "cosinesimil", 32, 100, 16),
            createVectorDocument(32)));

        // OS 2.x+ also supports faiss and lucene
        if (VersionMatchers.isOS_2_X.test(sourceVersion)) {
            configs.add(new KnnIndexConfig("knn-faiss-hnsw", createKnnIndexBody(
                "faiss", "hnsw", "l2", 64, 128, 24),
                createVectorDocument(64)));

            configs.add(new KnnIndexConfig("knn-lucene-hnsw", createKnnIndexBody(
                "lucene", "hnsw", "l2", 256, 100, 16),
                createVectorDocument(256)));
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
            vector.append(i == 0 ? "" : ",").append(String.format("%.4f", Math.random()));
        }
        vector.append("]");
        return String.format("{\"my_vector\": %s}", vector);
    }

    @SneakyThrows
    private void verifyKnnIndices(List<KnnIndexConfig> configs) {
        ObjectMapper mapper = new ObjectMapper();

        for (var config : configs) {
            var response = targetOperations.get("/" + config.name);
            assertThat("Index " + config.name + " should exist on target",
                response.getKey(), equalTo(200));

            JsonNode indexJson = mapper.readTree(response.getValue());
            JsonNode settings = indexJson.path(config.name).path("settings").path("index");
            JsonNode mappings = indexJson.path(config.name).path("mappings");

            assertTrue(settings.has("knn"),
                "Index " + config.name + " should have knn setting");
            assertThat("Index " + config.name + " should have knn=true",
                settings.path("knn").asText(), equalTo("true"));

            JsonNode vectorField = mappings.path("properties").path("my_vector");
            assertTrue(vectorField.has("type"),
                "Index " + config.name + " should have my_vector field");
            assertThat("my_vector should be knn_vector type",
                vectorField.path("type").asText(), equalTo("knn_vector"));

            log.info("Verified KNN index {} on target cluster", config.name);
        }
    }

    private record KnnIndexConfig(String name, String body, String document) {}
}
