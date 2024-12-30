package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ES_V6_8_23;

/**
 * Test class to verify custom transformations during metadata migrations.
 */
@Tag("isolatedTest")
@Slf4j
class MultiTypeMappingTransformationTest extends BaseMigrationTest {

    @SneakyThrows
    @Test
    public void multiTypeTransformationTest_union() {
        var es5Repo = "es5";
        var snapshotName = "es5-created-index";
        var originalIndexName = "test_index";

        try (
            final var indexCreatedCluster = new SearchClusterContainer(SearchClusterContainer.ES_V5_6_16)
        ) {
            indexCreatedCluster.start();

            var indexCreatedOperations = new ClusterOperations(indexCreatedCluster.getUrl());

            // Create index and add documents on the source cluster
            indexCreatedOperations.createIndex(originalIndexName);
            indexCreatedOperations.createDocument(originalIndexName, "1", "{\"field1\":\"My Name\"}", null, "type1");
            indexCreatedOperations.createDocument(originalIndexName, "2", "{\"field1\":\"string\", \"field2\":123}", null, "type2");
            indexCreatedOperations.createDocument(originalIndexName, "3", "{\"field3\":1.1}", null, "type3");

            indexCreatedOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, es5Repo);
            indexCreatedOperations.takeSnapshot(es5Repo, snapshotName, originalIndexName);
            indexCreatedCluster.copySnapshotData(localDirectory.toString());
        }

        try (
            final var upgradedSourceCluster = new SearchClusterContainer(ES_V6_8_23);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            this.sourceCluster = upgradedSourceCluster;
            this.targetCluster = targetCluster;

            startClusters();

            upgradedSourceCluster.putSnapshotData(localDirectory.toString());

            var upgradedSourceOperations = new ClusterOperations(upgradedSourceCluster.getUrl());

            // Register snapshot repository and restore snapshot in ES 6 cluster
            upgradedSourceOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, es5Repo);
            upgradedSourceOperations.restoreSnapshot(es5Repo, snapshotName);

            // Verify index exists on upgraded cluster
            var checkIndexUpgraded = upgradedSourceOperations.get("/" + originalIndexName);
            assertThat(checkIndexUpgraded.getKey(), equalTo(200));
            assertThat(checkIndexUpgraded.getValue(), containsString(originalIndexName));

            var updatedSnapshotName = createSnapshot("union-snapshot");
            var arguments = prepareSnapshotMigrationArgs(updatedSnapshotName);

            // Set up data filters
            var dataFilterArgs = new DataFilterArgs();
            dataFilterArgs.indexAllowlist = List.of(originalIndexName);
            arguments.dataFilterArgs = dataFilterArgs;

            // Use union method for multi-type mappings
            arguments.metadataTransformationParams.multiTypeResolutionBehavior = IndexMappingTypeRemoval.MultiTypeResolutionBehavior.UNION;

            // Execute migration
            MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);

            // Verify the migration result
            log.info(result.asCliOutput());
            assertThat(result.getExitCode(), equalTo(0));

            assertThat(result.getItems().getIndexes().size(), equalTo(1));
            var actualCreationResult = result.getItems().getIndexes().get(0);
            assertThat(actualCreationResult.getException(), equalTo(null));
            assertThat(actualCreationResult.getName(), equalTo(originalIndexName));
            assertThat(actualCreationResult.getFailureType(), equalTo(null));

            // Verify that the transformed index exists on the target cluster
            var res = targetOperations.get("/" + originalIndexName);
            assertThat(res.getKey(), equalTo(200));
            assertThat(res.getValue(), containsString(originalIndexName));

            // Fetch the index mapping from the target cluster
            var mappingResponse = targetOperations.get("/" + originalIndexName + "/_mapping");
            assertThat(mappingResponse.getKey(), equalTo(200));

            // Parse the mapping response
            var mapper = new ObjectMapper();
            var mappingJson = mapper.readTree(mappingResponse.getValue());

            // Navigate to the properties of the index mapping
            JsonNode properties = mappingJson.path(originalIndexName).path("mappings").path("properties");

            // Assert that both field1 and field2 are present
            assertThat(properties.get("field1").get("type").asText(), equalTo("text"));
            assertThat(properties.get("field2").get("type").asText(), equalTo("long"));
            assertThat(properties.get("field3").get("type").asText(), equalTo("float"));
        }
    }

    @Test
    public void es5_doesNotAllow_multiTypeConflicts() {
        try (
            final var es5 = new SearchClusterContainer(SearchClusterContainer.ES_V5_6_16)
        ) {
            es5.start();

            var clusterOperations = new ClusterOperations(es5.getUrl());

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
