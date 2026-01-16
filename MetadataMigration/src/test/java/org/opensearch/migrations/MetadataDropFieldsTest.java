package org.opensearch.migrations;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class to verify unsupported fields are dropped during metadata migrations.
 */
@Tag("isolatedTest")
@Slf4j
class MetadataDropFieldsTest extends BaseMigrationTest {

    @TempDir
    private File legacySnapshotDirectory;
    @TempDir
    private File sourceSnapshotDirectory;

    private static Stream<Arguments> scenarios() {
        var scenarios = Stream.<Arguments>builder();
        scenarios.add(Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_4));
        return scenarios.build();
    }

    @ParameterizedTest(name = "Legacy {0} snapshot upgrade to {1} migrate onto target {2}")
    @MethodSource(value = "scenarios")
    public void migrateFromUpgrade(
            final SearchClusterContainer.ContainerVersion legacyVersion,
            final SearchClusterContainer.ContainerVersion sourceVersion,
            final SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        var testData = new TestData();
        try (
                final var legacyCluster = new SearchClusterContainer(legacyVersion)
        ) {
            legacyCluster.start();

            var legacyClusterOperations = new ClusterOperations(legacyCluster);

            // Create index with mapping that has _all which is deprecated in ES_6.8 and removed in ES_7.x
            var body = "{\"mappings\":{\"_doc\":{\"_all\":{\"enabled\":false}," +
                    "\"properties\":{\"title\":{\"type\":\"text\"},\"description\":{\"type\":\"text\"}}}}}";
            legacyClusterOperations.createIndex(testData.indexWithMappingThatHasAll, body);
            legacyClusterOperations.createDocument(testData.indexWithMappingThatHasAll, "222", "{\"title\":\"Tobias Funke\"}");
            legacyClusterOperations.get("/_refresh");

            legacyClusterOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, testData.legacySnapshotRepo);
            legacyClusterOperations.takeSnapshot(testData.legacySnapshotRepo, testData.legacySnapshotName, testData.indexWithMappingThatHasAll);
            legacyCluster.copySnapshotData(legacySnapshotDirectory.toString());
        }

        try (
                final var sourceCluster = new SearchClusterContainer(sourceVersion);
                final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            startClusters();

            sourceCluster.putSnapshotData(legacySnapshotDirectory.toString());
            sourceOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, testData.legacySnapshotRepo);
            sourceOperations.restoreSnapshot(testData.legacySnapshotRepo, testData.legacySnapshotName);
            sourceOperations.deleteSnapshot(testData.legacySnapshotRepo, testData.legacySnapshotName);
            sourceOperations.get("/_refresh");
            var checkIndexUpgraded = sourceOperations.get("/" + testData.indexWithMappingThatHasAll);
            assertThat(checkIndexUpgraded.getKey(), equalTo(200));

            var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, testData.snapshotName, testSnapshotContext);
            sourceCluster.copySnapshotData(sourceSnapshotDirectory.toString());

            var arguments = prepareSnapshotMigrationArgs(testData.snapshotName, sourceSnapshotDirectory.toString());
            configureDataFilters(testData, arguments);

            var result = executeMigration(arguments, MetadataCommands.MIGRATE);
            log.info(result.asCliOutput());
            assertThat(result.getExitCode(), equalTo(0));

            checkMetadataResult(result, List.of(testData.indexWithMappingThatHasAll));
            checkIndexWithMappingThatHasAll(testData);

        }
    }

    private static class TestData {
        final String legacySnapshotRepo = "legacy_repo";
        final String legacySnapshotName = "legacy_snapshot";
        final String snapshotName = "snapshot_name";
        final String indexWithMappingThatHasAll = "index_with_mapping_that_has_all";
    }

    private void configureDataFilters(TestData testData, MigrateOrEvaluateArgs arguments) {
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexTemplateAllowlist = List.of("");
        dataFilterArgs.indexAllowlist = List.of(testData.indexWithMappingThatHasAll);
        arguments.dataFilterArgs = dataFilterArgs;

        arguments.metadataTransformationParams.multiTypeResolutionBehavior = IndexMappingTypeRemoval.MultiTypeResolutionBehavior.UNION;
    }

    @SneakyThrows
    private void checkMetadataResult(MigrationItemResult result, List<String> expectedIndices) {
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        result.getItems().getIndexes().forEach(creationResult -> {
            assertThat(creationResult.getException(), equalTo(null));
            assertTrue(expectedIndices.contains(creationResult.getName()));
            assertThat(creationResult.getFailureType(), equalTo(null));
        });
    }

    @SneakyThrows
    private void checkIndexWithMappingThatHasAll(TestData testData) {
        var indexName = testData.indexWithMappingThatHasAll;
        var res = targetOperations.get("/" + testData.indexWithMappingThatHasAll);
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString(indexName));

        var mappingResponse = targetOperations.get("/" + indexName + "/_mapping");
        assertThat(mappingResponse.getKey(), equalTo(200));

        var mapper = new ObjectMapper();
        var mappingJson = mapper.readTree(mappingResponse.getValue());

        var properties = mappingJson.path(indexName).path("mappings").path("properties");
        assertThat(properties.get("title").get("type").asText(), equalTo("text"));
        assertThat(properties.get("description").get("type").asText(), equalTo("text"));

        var mappings = mappingJson.path(indexName).path("mappings");
        assertThat(mappings.get("_all"), equalTo(null));
    }
}
