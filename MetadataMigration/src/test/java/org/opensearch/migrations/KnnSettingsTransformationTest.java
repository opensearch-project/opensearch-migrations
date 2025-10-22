package org.opensearch.migrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Test to reproduce the KNN settings uniqueness when migrating from OpenSearch 1.3 to OpenSearch 2.x.
 */
@Tag("isolatedTest")
@Slf4j
class KnnSettingsTransformationTest extends BaseMigrationTest {
    @TempDir
    protected File localDirectory;

    private static Stream<Arguments> scenarios() {
        var source = SearchClusterContainer.OS_V1_3_16;
        var target = SearchClusterContainer.OS_V2_19_1;
        return Stream.of(Arguments.of(source, target));
    }

    @ParameterizedTest(name = "KNN Settings Transformation From {0} to {1}")
    @MethodSource(value = "scenarios")
    void knnSettingsTransformationTest(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion) {
        try (
                final var sourceCluster = new SearchClusterContainer(sourceVersion);
                final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performKnnSettingsTest();
        }
    }

    @SneakyThrows
    private void performKnnSettingsTest() {
        startClusters();

        String flatDottedFormat = "{\n" +
                "  \"settings\": {\n" +
                "    \"index\": {\n" +
                "      \"number_of_shards\": \"1\",\n" +
                "      \"knn.algo_param.ef_search\": 100,\n" +
                "      \"knn\": \"true\",\n" +
                "      \"number_of_replicas\": \"1\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        var indexName = "test-knn";
        sourceOperations.createIndex(indexName, flatDottedFormat);

        var snapshotName = "knn_settings_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());
        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());

        // First, execute EVALUATE command to verify transformation logic
        log.info("Executing EVALUATE command...");
        MigrationItemResult evaluateResult = executeMigration(arguments, MetadataCommands.EVALUATE);
        log.info("EVALUATE result:\n{}", evaluateResult.asCliOutput());
        assertEquals(0,
                evaluateResult.getExitCode(),
                "Expected EVALUATE to succeed");
        // Then, execute MIGRATE command to actually perform the migration
        log.info("Executing MIGRATE command...");
        MigrationItemResult migrateResult = executeMigration(arguments, MetadataCommands.MIGRATE);
        log.info("MIGRATE result:\n{}", migrateResult.asCliOutput());
        assertEquals(0,
                migrateResult.getExitCode(),
                "Expected MIGRATE to succeed");

        ObjectMapper mapper = new ObjectMapper();

        for (var operations : List.of(sourceOperations, targetOperations)) {
            log.atInfo()
                    .setMessage("Executing validation operations against {}")
                    .addArgument(sourceOperations.equals(operations) ? "source" : "target")
                    .log();
            var response = operations.get("/" + indexName + "/_settings?flat_settings=true");
            JsonNode json = mapper.readTree(response.getValue());
            JsonNode settings = json.get(indexName).get("settings");

            assertTrue(settings.has("index.knn"), "Expected knn");
            assertTrue(settings.get("index.knn").isTextual(), "Expected knn=true");
            assertEquals("true", settings.get("index.knn").textValue(), "Expected knn=true");
            assertTrue(settings.has("index.knn.algo_param.ef_search"), "Expected knn.algo_param.ef_search");
            assertTrue(settings.get("index.knn.algo_param.ef_search").isTextual(), "Expected knn.algo_param.ef_search=100");
            assertEquals("100", settings.get("index.knn.algo_param.ef_search").textValue(), "Expected knn.algo_param.ef_search=100");
        }
    }
}
