package org.opensearch.migrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;

import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test class to verify custom transformations during metadata migrations.
 */
@Tag("isolatedTest")
@Slf4j
class MetadataUpgradeTest extends BaseMigrationTest {

    private static Stream<Arguments> scenarios() {
        var scenarios = Stream.<Arguments>builder();
        scenarios.add(Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_LATEST));
        return scenarios.build();
    }

    @ParameterizedTest(name = "Legacy {0} snapshot upgrade to {1} migrate onto target {2}")
    @MethodSource(value = "scenarios")
    public void migrateFromUpgrade(
        final SearchClusterContainer.ContainerVersion legacyVersion,
        final SearchClusterContainer.ContainerVersion sourceVersion,
        final SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        var legacySnapshotRepo = "repo";
        var legacySnapshotName = "snapshot";
        var originalIndexName = "test_index";
        try (
            final var legacyCluster = new SearchClusterContainer(legacyVersion)
        ) {
            legacyCluster.start();

            var legacyClusterOperations = new ClusterOperations(legacyCluster);

            //createDocumentsWithManyTypes(originalIndexName, legacyClusterOperations);
//            var body1 = "{\"mappings\":{\"_doc\":{" +
//                    "\"properties\":{\"title\":{\"type\":\"text\"},\"description\":{\"type\":\"text\"}}}}}";
//            legacyClusterOperations.createIndex(originalIndexName, body1);

            var body = "{\"mappings\":{\"_doc\":{\"_all\":{\"enabled\":false}," +
                    "\"properties\":{\"title\":{\"type\":\"text\"},\"description\":{\"type\":\"text\"}}}}}";
            legacyClusterOperations.createIndex(originalIndexName, body);
            legacyClusterOperations.createDocument(originalIndexName, "222", "{\"author\":\"Tobias Funke\"}");
            legacyClusterOperations.post("/" + originalIndexName + "/_refresh", null);

            legacyClusterOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, legacySnapshotRepo);
            legacyClusterOperations.takeSnapshot(legacySnapshotRepo, legacySnapshotName, originalIndexName);
            legacyCluster.copySnapshotData(localDirectory.toString());
        }

        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;

            startClusters();

            sourceCluster.putSnapshotData(localDirectory.toString());

            var upgradedSourceOperations = new ClusterOperations(sourceCluster);

            upgradedSourceOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, legacySnapshotRepo);
            upgradedSourceOperations.restoreSnapshot(legacySnapshotRepo, legacySnapshotName);


            var checkIndexUpgraded = upgradedSourceOperations.get("/" + originalIndexName);
            assertThat(checkIndexUpgraded.getKey(), equalTo(200));
            assertThat(checkIndexUpgraded.getValue(), containsString(originalIndexName));

            var updatedSnapshotName = createSnapshot("union-snapshot");
            var repo = "migration_assistant_repo";
            upgradedSourceOperations.get("/_snapshot/" + repo + "/union-snapshot?pretty");
            var arguments = prepareSnapshotMigrationArgs(updatedSnapshotName);

            configureDataFilters(originalIndexName, arguments);
            //arguments.metadataCustomTransformationParams = useTransformationResource("es2-transforms.json");

            var result = executeMigration(arguments, MetadataCommands.MIGRATE);
            checkResult(result, originalIndexName);
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

//        var actualCreationResult = result.getItems().getIndexes().stream().filter(i -> indexName.equals(i.getName())).findFirst().get();
//        assertThat(actualCreationResult.getException(), equalTo(null));
//        assertThat(actualCreationResult.getName(), equalTo(indexName));
//        assertThat(actualCreationResult.getFailureType(), equalTo(null));
//        assertThat(actualCreationResult.getException(), equalTo(null));
//        assertThat(actualCreationResult.getName(), equalTo(indexName));
//        assertThat(actualCreationResult.getFailureType(), equalTo(null));
//
//        var res = targetOperations.get("/" + indexName);
//        assertThat(res.getKey(), equalTo(200));
//        assertThat(res.getValue(), containsString(indexName));
//
//        var mappingResponse = targetOperations.get("/" + indexName + "/_mapping");
//        assertThat(mappingResponse.getKey(), equalTo(200));
//
//        var mapper = new ObjectMapper();
//        var mappingJson = mapper.readTree(mappingResponse.getValue());
//
//        var properties = mappingJson.path(indexName).path("mappings").path("properties");
//
//        assertThat(properties.get("field1").get("type").asText(), equalTo("text"));
//        assertThat(properties.get("field2").get("type").asText(), equalTo("long"));
//        // In ES2 the default mapping type for floating point numbers was double, later it was changed to float
//        // https://www.elastic.co/guide/en/elasticsearch/reference/5.6/breaking_50_mapping_changes.html#_floating_points_use_literal_float_literal_instead_of_literal_double_literal
//        assertThat(properties.get("field3").get("type").asText(), anyOf(equalTo("float"), equalTo("double")));
    }

    private void configureDataFilters(String originalIndexName, MigrateOrEvaluateArgs arguments) {
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexTemplateAllowlist = List.of("");
        dataFilterArgs.indexAllowlist = List.of(originalIndexName);
        arguments.dataFilterArgs = dataFilterArgs;

        arguments.metadataTransformationParams.multiTypeResolutionBehavior = IndexMappingTypeRemoval.MultiTypeResolutionBehavior.UNION;
    }
}
