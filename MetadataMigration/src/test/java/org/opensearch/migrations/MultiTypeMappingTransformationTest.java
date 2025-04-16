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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test class to verify custom transformations during metadata migrations.
 */
@Tag("isolatedTest")
@Slf4j
class MultiTypeMappingTransformationTest extends BaseMigrationTest {

    @TempDir
    private File legacySnapshotDirectory;
    @TempDir
    private File sourceSnapshotDirectory;

    private static Stream<Arguments> scenarios() {
        var scenarios = Stream.<Arguments>builder();
        scenarios.add(Arguments.of(SearchClusterContainer.ES_V2_4_6, SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_LATEST));
        scenarios.add(Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_LATEST));
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

            createDocumentsWithManyTypes(testData.indexName, legacyClusterOperations);

            legacyClusterOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, testData.legacySnapshotRepo);
            legacyClusterOperations.takeSnapshot(testData.legacySnapshotRepo, testData.legacySnapshotName, testData.indexName);
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

            var checkIndexUpgraded = sourceOperations.get("/" + testData.indexName);
            assertThat(checkIndexUpgraded.getKey(), equalTo(200));
            assertThat(checkIndexUpgraded.getValue(), containsString(testData.indexName));

            var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, testData.snapshotName, testSnapshotContext);
            sourceCluster.copySnapshotData(sourceSnapshotDirectory.toString());

            var arguments = prepareSnapshotMigrationArgs(testData.snapshotName, sourceSnapshotDirectory.toString());
            configureDataFilters(testData.indexName, arguments);
            arguments.metadataCustomTransformationParams = useTransformationResource("es2-transforms.json");

            var result = executeMigration(arguments, MetadataCommands.MIGRATE);
            checkResult(result, testData.indexName);
        }
    }

    private void createDocumentsWithManyTypes(String originalIndexName, ClusterOperations indexCreatedOperations) {
        indexCreatedOperations.createIndex(originalIndexName);
        indexCreatedOperations.createDocument(originalIndexName, "1", "{\"field1\":\"My Name\"}", null, "type1");
        indexCreatedOperations.createDocument(originalIndexName, "2", "{\"field1\":\"string\", \"field2\":123}", null, "type2");
        indexCreatedOperations.createDocument(originalIndexName, "3", "{\"field3\":1.1}", null, "type3");
    }

    @SneakyThrows
    private void checkResult(MigrationItemResult result, String indexName) {
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var actualCreationResult = result.getItems().getIndexes().stream().filter(i -> indexName.equals(i.getName())).findFirst().get();
        assertThat(actualCreationResult.getException(), equalTo(null));
        assertThat(actualCreationResult.getName(), equalTo(indexName));
        assertThat(actualCreationResult.getFailureType(), equalTo(null));
        assertThat(actualCreationResult.getException(), equalTo(null));
        assertThat(actualCreationResult.getName(), equalTo(indexName));
        assertThat(actualCreationResult.getFailureType(), equalTo(null));

        var res = targetOperations.get("/" + indexName);
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString(indexName));

        var mappingResponse = targetOperations.get("/" + indexName + "/_mapping");
        assertThat(mappingResponse.getKey(), equalTo(200));

        var mapper = new ObjectMapper();
        var mappingJson = mapper.readTree(mappingResponse.getValue());

        var properties = mappingJson.path(indexName).path("mappings").path("properties");

        assertThat(properties.get("field1").get("type").asText(), equalTo("text"));
        assertThat(properties.get("field2").get("type").asText(), equalTo("long"));
        // In ES2 the default mapping type for floating point numbers was double, later it was changed to float
        // https://www.elastic.co/guide/en/elasticsearch/reference/5.6/breaking_50_mapping_changes.html#_floating_points_use_literal_float_literal_instead_of_literal_double_literal
        assertThat(properties.get("field3").get("type").asText(), anyOf(equalTo("float"), equalTo("double")));
    }

    private void configureDataFilters(String originalIndexName, MigrateOrEvaluateArgs arguments) {
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexTemplateAllowlist = List.of("");
        dataFilterArgs.indexAllowlist = List.of(originalIndexName);
        arguments.dataFilterArgs = dataFilterArgs;

        arguments.metadataTransformationParams.multiTypeResolutionBehavior = IndexMappingTypeRemoval.MultiTypeResolutionBehavior.UNION;
    }

    @Test
    public void es5_doesNotAllow_multiTypeConflicts() {
        try (
            final var es5 = new SearchClusterContainer(SearchClusterContainer.ES_V5_6_16)
        ) {
            es5.start();

            var clusterOperations = new ClusterOperations(es5);

            var originalIndexName = "test_index";
            String body = "{" +
                "  \"settings\": {" +
                "    \"index\": {" +
                "      \"number_of_shards\": 5," +
                "      \"number_of_replicas\": 0" +
                "    }" +
                "  }," +
                "  \"mappings\": {" +
                "    \"type1\": {" +
                "      \"properties\": {" +
                "        \"field1\": { \"type\": \"float\" }" +
                "      }" +
                "    }," +
                "    \"type2\": {" +
                "      \"properties\": {" +
                "        \"field1\": { \"type\": \"long\" }" +
                "      }" +
                "    }" +
                "  }" +
                "}";
            var res = clusterOperations.put("/" + originalIndexName, body);
            assertThat(res.getKey(), equalTo(400));
            assertThat(res.getValue(), containsString("mapper [field1] cannot be changed from type [long] to [float]"));
        }
    }

    private static class TestData {
        final String legacySnapshotRepo = "legacy_repo";
        final String legacySnapshotName = "legacy_snapshot";
        final String snapshotName = "union_snapshot";
        final String indexName = "test_index";
    }
}
