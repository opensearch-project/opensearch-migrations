package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test class to verify custom transformations during metadata migrations.
 */
@Tag("isolatedTest")
@Slf4j
class MultiTypeMappingTransformationTest extends BaseMigrationTest {

    @Test
    public void multiTypeTransformationTest_union_6_8() {
        var es5Repo = "es5";
        var snapshotName = "es5-created-index";
        var originalIndexName = "test_index";

        try (
            final var indexCreatedCluster = new SearchClusterContainer(SearchClusterContainer.ES_V5_6_16)
        ) {
            indexCreatedCluster.start();

            var indexCreatedOperations = new ClusterOperations(indexCreatedCluster);

            // Create index and add documents on the source cluster
            createMultiTypeIndex(originalIndexName, indexCreatedOperations);

            indexCreatedOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, es5Repo);
            indexCreatedOperations.takeSnapshot(es5Repo, snapshotName, originalIndexName);
            indexCreatedCluster.copySnapshotData(localDirectory.toString());
        }

        try (
            final var upgradedSourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            this.sourceCluster = upgradedSourceCluster;
            this.targetCluster = targetCluster;

            startClusters();

            upgradedSourceCluster.putSnapshotData(localDirectory.toString());

            var upgradedSourceOperations = new ClusterOperations(upgradedSourceCluster);

            // Register snapshot repository and restore snapshot in ES 6 cluster
            upgradedSourceOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, es5Repo);
            upgradedSourceOperations.restoreSnapshot(es5Repo, snapshotName);

            // Verify index exists on upgraded cluster
            var checkIndexUpgraded = upgradedSourceOperations.get("/" + originalIndexName);
            assertThat(checkIndexUpgraded.getKey(), equalTo(200));
            assertThat(checkIndexUpgraded.getValue(), containsString(originalIndexName));

            var updatedSnapshotName = createSnapshot("union-snapshot");
            var arguments = prepareSnapshotMigrationArgs(updatedSnapshotName);

            configureDataFilters(originalIndexName, arguments);

            // Execute migration
            var result = executeMigration(arguments, MetadataCommands.MIGRATE);
            checkResult(result, originalIndexName);
        }
    }

    @Test
    public void multiTypeTransformationTest_union_5_6() {
        try (
            final var indexCreatedCluster = new SearchClusterContainer(SearchClusterContainer.ES_V5_6_16);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            indexCreatedCluster.start();

            this.sourceCluster = indexCreatedCluster;
            this.targetCluster = targetCluster;

            startClusters();

            var indexCreatedOperations = new ClusterOperations(indexCreatedCluster);

            var originalIndexName = "test_index";

            createMultiTypeIndex(originalIndexName, indexCreatedOperations);

            var arguments = new MigrateOrEvaluateArgs();
            arguments.sourceArgs.host = sourceCluster.getUrl();
            arguments.targetArgs.host = targetCluster.getUrl();

            configureDataFilters(originalIndexName, arguments);

            // Execute migration 
            var result = executeMigration(arguments, MetadataCommands.MIGRATE);
            checkResult(result, originalIndexName);
        }
    }
    
    private void createMultiTypeIndex(String originalIndexName, ClusterOperations indexCreatedOperations) {
        indexCreatedOperations.createIndex(originalIndexName);
        indexCreatedOperations.createDocument(originalIndexName, "1", "{\"field1\":\"My Name\"}", null, "type1");
        indexCreatedOperations.createDocument(originalIndexName, "2", "{\"field1\":\"string\", \"field2\":123}", null, "type2");
        indexCreatedOperations.createDocument(originalIndexName, "3", "{\"field3\":1.1}", null, "type3");
    }

    @SneakyThrows
    private void checkResult(MigrationItemResult result, String indexName) {
        // Verify the migration result
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var actualCreationResult = result.getItems().getIndexes().stream().filter(i -> indexName.equals(i.getName())).findFirst().get();
        assertThat(actualCreationResult.getException(), equalTo(null));
        assertThat(actualCreationResult.getName(), equalTo(indexName));
        assertThat(actualCreationResult.getFailureType(), equalTo(null));
        assertThat(actualCreationResult.getException(), equalTo(null));
        assertThat(actualCreationResult.getName(), equalTo(indexName));
        assertThat(actualCreationResult.getFailureType(), equalTo(null));

        // Verify that the transformed index exists on the target cluster
        var res = targetOperations.get("/" + indexName);
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString(indexName));

        // Fetch the index mapping from the target cluster
        var mappingResponse = targetOperations.get("/" + indexName + "/_mapping");
        assertThat(mappingResponse.getKey(), equalTo(200));

        // Parse the mapping response
        var mapper = new ObjectMapper();
        var mappingJson = mapper.readTree(mappingResponse.getValue());

        // Navigate to the properties of the index mapping
        var properties = mappingJson.path(indexName).path("mappings").path("properties");

        // Assert that both field1 and field2 are present
        assertThat(properties.get("field1").get("type").asText(), equalTo("text"));
        assertThat(properties.get("field2").get("type").asText(), equalTo("long"));
        assertThat(properties.get("field3").get("type").asText(), equalTo("float"));
    }

    private void configureDataFilters(String originalIndexName, MigrateOrEvaluateArgs arguments) {
        // Set up data filters
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexTemplateAllowlist = List.of("");
        dataFilterArgs.indexAllowlist = List.of(originalIndexName);
        arguments.dataFilterArgs = dataFilterArgs;

        // Use union method for multi-type mappings
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
}
