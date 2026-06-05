package org.opensearch.migrations;

import java.io.File;
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
 * End-to-end regression test for the ES 7.x (AWS k-NN plugin) → OpenSearch 3.x path.
 *
 * The source stores k-NN build params as index-level settings (index.knn.algo_param.*,
 * index.knn.space_type) on a method-less knn_vector field. OpenSearch 3.x rejects those
 * settings on create-index, which previously sent IndexCreator into an infinite retry
 * loop. This verifies the migration completes and the params land as field-level method
 * config on the target. Uses Open Distro 1.13.3 (Apache-2.0, ES 7.10.2 + k-NN plugin)
 * so no licensed Elasticsearch image is required.
 */
@Tag("isolatedTest")
@Slf4j
class EsKnnIndexLevelSettingsMigrationTest extends BaseMigrationTest {

    private static final String INDEX_NAME = "es7-knn-index-level";
    private static final int DIMENSION = 16;

    @TempDir
    protected File localDirectory;

    private static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.ODFE_V1_13_3, SearchClusterContainer.OS_LATEST)
        );
    }

    @ParameterizedTest(name = "ES7 index-level knn settings migration from {0} to {1}")
    @MethodSource("scenarios")
    void esKnnIndexLevelSettingsMigrationTest(ContainerVersion sourceVersion, ContainerVersion targetVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performTest();
        }
    }

    @SneakyThrows
    private void performTest() {
        startClusters();

        sourceOperations.createIndex(INDEX_NAME, sourceIndexBody());
        sourceOperations.createDocument(INDEX_NAME, "1", vectorDocument());

        var snapshotName = "es7_knn_index_level_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());
        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());

        MigrationItemResult evaluateResult = executeMigration(arguments, MetadataCommands.EVALUATE);
        log.info("EVALUATE result:\n{}", evaluateResult.asCliOutput());
        assertThat(evaluateResult.getExitCode(), equalTo(0));

        // Before the fix this call would hang in IndexCreator's create-index retry loop.
        MigrationItemResult migrateResult = executeMigration(arguments, MetadataCommands.MIGRATE);
        log.info("MIGRATE result:\n{}", migrateResult.asCliOutput());
        assertThat(migrateResult.getExitCode(), equalTo(0));

        verifyTargetIndex();
    }

    /**
     * ES 7.x k-NN plugin shape: knn_vector field carries only type+dimension, while the
     * HNSW build params live in index settings. ef_search is a runtime setting and must
     * survive untouched; the build params (ef_construction, m, space_type) must move to
     * the field method.
     */
    private String sourceIndexBody() {
        return String.format(
            "{" +
            "  \"settings\": {" +
            "    \"index\": {" +
            "      \"knn\": true," +
            "      \"knn.algo_param.ef_construction\": 128," +
            "      \"knn.algo_param.m\": 24," +
            "      \"knn.algo_param.ef_search\": 100," +
            "      \"knn.space_type\": \"l2\"," +
            "      \"number_of_shards\": 1," +
            "      \"number_of_replicas\": 0" +
            "    }" +
            "  }," +
            "  \"mappings\": {" +
            "    \"properties\": {" +
            "      \"my_vector\": {" +
            "        \"type\": \"knn_vector\"," +
            "        \"dimension\": %d" +
            "      }" +
            "    }" +
            "  }" +
            "}", DIMENSION);
    }

    private String vectorDocument() {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < DIMENSION; i++) {
            vector.append(i == 0 ? "" : ",").append("0.1");
        }
        vector.append("]");
        return String.format("{\"my_vector\": %s}", vector);
    }

    @SneakyThrows
    private void verifyTargetIndex() {
        ObjectMapper mapper = new ObjectMapper();

        // flat_settings keeps settings as literal dotted keys (index.knn.*), matching
        // how the migration emits them and avoiding nested/flat ambiguity in assertions.
        var response = targetOperations.get("/" + INDEX_NAME + "/_settings?flat_settings=true");
        assertThat("Settings should be readable on target", response.getKey(), equalTo(200));
        JsonNode settings = mapper.readTree(response.getValue()).path(INDEX_NAME).path("settings");

        // Build-time params were stripped from settings (otherwise create-index would have failed).
        assertTrue(settings.path("index.knn.algo_param.ef_construction").isMissingNode(),
            "index-level ef_construction should be gone from target settings");
        assertTrue(settings.path("index.knn.algo_param.m").isMissingNode(),
            "index-level m should be gone from target settings");
        assertTrue(settings.path("index.knn.space_type").isMissingNode(),
            "index-level space_type should be gone from target settings");

        // The enable flag and the runtime ef_search setting remain valid on OS.
        assertThat("index.knn enable flag preserved",
            settings.path("index.knn").asText(), equalTo("true"));
        assertThat("runtime ef_search preserved",
            settings.path("index.knn.algo_param.ef_search").asText(), equalTo("100"));

        var mappingResponse = targetOperations.get("/" + INDEX_NAME + "/_mapping");
        assertThat("Mappings should be readable on target", mappingResponse.getKey(), equalTo(200));
        JsonNode method = mapper.readTree(mappingResponse.getValue()).path(INDEX_NAME)
            .path("mappings").path("properties").path("my_vector").path("method");

        // Build-time params were lifted onto the field method.
        assertTrue(method.has("engine"), "my_vector should have a field-level method block");
        assertThat(method.path("engine").asText(), equalTo("faiss"));
        assertThat(method.path("name").asText(), equalTo("hnsw"));
        assertThat(method.path("space_type").asText(), equalTo("l2"));
        assertThat(method.path("parameters").path("m").asInt(), equalTo(24));
        assertThat(method.path("parameters").path("ef_construction").asInt(), equalTo(128));
    }
}
