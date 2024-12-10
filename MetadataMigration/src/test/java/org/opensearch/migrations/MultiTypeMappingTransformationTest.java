package org.opensearch.migrations;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.transformers.IndexTransformationException;
import org.opensearch.migrations.bulkload.transformers.MetadataTransformerParams;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;

import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ES_V6_8_23;

/**
 * Test class to verify custom transformations during metadata migrations.
 */
@Tag("isolatedTest")
@Slf4j
class MultiTypeMappingTransformationTest {

    @TempDir
    private File localDirectory;

    @SneakyThrows
    @Test
    public void multiTypeTransformationTest_union() {
        try (
                final SearchClusterContainer indexCreatedCluster = new SearchClusterContainer(SearchClusterContainer.ES_V5_6_13);
                final SearchClusterContainer upgradedSourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23)
                        .withFileSystemBind(localDirectory.getAbsolutePath(), SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, BindMode.READ_WRITE);
                final SearchClusterContainer targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(indexCreatedCluster::start),
                    CompletableFuture.runAsync(upgradedSourceCluster::start),
                    CompletableFuture.runAsync(targetCluster::start)
            ).join();

            var indexCreatedOperations = new ClusterOperations(indexCreatedCluster.getUrl());
            var upgradedSourceOperations = new ClusterOperations(upgradedSourceCluster.getUrl());
            var targetOperations = new ClusterOperations(targetCluster.getUrl());

            // Test data
            var originalIndexName = "test_index";

            // Create index and add a document on the source cluster
            indexCreatedOperations.createIndex(originalIndexName);
            indexCreatedOperations.createDocument(originalIndexName, "1", "{\"field1\":\"My Name\"}", null, "type1");
            indexCreatedOperations.createDocument(originalIndexName, "2", "{\"field1\":\"string\", \"field2\":123}", null, "type2");
            indexCreatedOperations.createDocument(originalIndexName, "3", "{\"field3\":1.1}", null, "type3");

            var arguments = new MigrateOrEvaluateArgs();

            // Use SnapshotImage as the transfer medium
            var snapshotName = "initial-setup-snapshot";
            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var sourceClient = new OpenSearchClient(ConnectionContextTestParams.builder()
                    .host(indexCreatedCluster.getUrl())
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var snapshotCreator = new FileSystemSnapshotCreator(
                    snapshotName,
                    sourceClient,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                    List.of(),
                    snapshotContext.createSnapshotCreateContext()
            );

            // Get snapshot for ES 5
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            indexCreatedCluster.copySnapshotData(localDirectory.toString());

            // Snapshot is automatically visible due to container mount

            // Register snapshot repository
            upgradedSourceOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, snapshotCreator.getRepoName());

            // Restore snapshot
            upgradedSourceOperations.restoreSnapshot(snapshotCreator.getRepoName(), snapshotCreator.getSnapshotName());

            // Verify that the index exists on the upgraded cluster
            var checkIndexUpgraded = upgradedSourceOperations.get("/" + originalIndexName);
            assertThat(checkIndexUpgraded.getKey(), equalTo(200));
            assertThat(checkIndexUpgraded.getValue(), containsString(originalIndexName));


            upgradedSourceOperations.deleteAllSnapshotsAndRepository(snapshotCreator.getRepoName());

            // Use SnapshotImage as the transfer medium
            var updatedSnapshotName = "union-snapshot";
            var upgradedClient = new OpenSearchClient(ConnectionContextTestParams.builder()
                    .host(upgradedSourceCluster.getUrl())
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var updatedSnapshotCreator = new FileSystemSnapshotCreator(
                    updatedSnapshotName,
                    upgradedClient,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                    List.of(),
                    snapshotContext.createSnapshotCreateContext()
            );

            // Get snapshot for ES 6
            SnapshotRunner.runAndWaitForCompletion(updatedSnapshotCreator);

            arguments.fileSystemRepoPath = localDirectory.getAbsolutePath();
            arguments.snapshotName = updatedSnapshotCreator.getSnapshotName();
            arguments.sourceVersion = ES_V6_8_23.getVersion();
            arguments.targetArgs.host = targetCluster.getUrl();
            // Set up data filters to include only the test index and templates
            var dataFilterArgs = new DataFilterArgs();
            dataFilterArgs.indexAllowlist = List.of(originalIndexName);
            arguments.dataFilterArgs = dataFilterArgs;

            // Use split for multi type mappings resolution
            arguments.metadataTransformationParams = TestMetadataTransformationParams.builder()
                    .multiTypeResolutionBehavior(IndexMappingTypeRemoval.MultiTypeResolutionBehavior.UNION)
                    .build();

            // Execute the migration with the custom transformation
            var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
            var metadata = new MetadataMigration();

            MigrationItemResult result = metadata.migrate(arguments).execute(metadataContext);

            // Verify the migration result
            log.info(result.asCliOutput());
            assertThat(result.getExitCode(), equalTo(0));

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

    @SneakyThrows
    @Test
    public void multiTypeTransformationTest_union_withConflicts() {
        try (
                final SearchClusterContainer indexCreatedCluster = new SearchClusterContainer(SearchClusterContainer.ES_V5_6_13);
                final SearchClusterContainer upgradedSourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23)
                        .withFileSystemBind(localDirectory.getAbsolutePath(), SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, BindMode.READ_WRITE);
                final SearchClusterContainer targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(indexCreatedCluster::start),
                    CompletableFuture.runAsync(upgradedSourceCluster::start),
                    CompletableFuture.runAsync(targetCluster::start)
            ).join();

            var indexCreatedOperations = new ClusterOperations(indexCreatedCluster.getUrl());
            var upgradedSourceOperations = new ClusterOperations(upgradedSourceCluster.getUrl());
            var targetOperations = new ClusterOperations(targetCluster.getUrl());

            // Test data
            var originalIndexName = "test_index";

            // Create index and add a document on the source cluster
            indexCreatedOperations.createIndex(originalIndexName);
            indexCreatedOperations.createDocument(originalIndexName, "1", "{\"field1\":123}", null, "type1");
            indexCreatedOperations.createDocument(originalIndexName, "2", "{\"field1\":1.1}", null, "type2");

            var arguments = new MigrateOrEvaluateArgs();

            // Use SnapshotImage as the transfer medium
            var snapshotName = "initial-setup-snapshot";
            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var sourceClient = new OpenSearchClient(ConnectionContextTestParams.builder()
                    .host(indexCreatedCluster.getUrl())
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var snapshotCreator = new FileSystemSnapshotCreator(
                    snapshotName,
                    sourceClient,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                    List.of(),
                    snapshotContext.createSnapshotCreateContext()
            );

            // Get snapshot for ES 5
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            indexCreatedCluster.copySnapshotData(localDirectory.toString());

            // Snapshot is automatically visible due to container mount

            // Register snapshot repository
            upgradedSourceOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, snapshotCreator.getRepoName());

            // Restore snapshot
            upgradedSourceOperations.restoreSnapshot(snapshotCreator.getRepoName(), snapshotCreator.getSnapshotName());

            // Verify that the index exists on the upgraded cluster
            var checkIndexUpgraded = upgradedSourceOperations.get("/" + originalIndexName);
            assertThat(checkIndexUpgraded.getKey(), equalTo(200));
            assertThat(checkIndexUpgraded.getValue(), containsString(originalIndexName));


            upgradedSourceOperations.deleteAllSnapshotsAndRepository(snapshotCreator.getRepoName());

            // Use SnapshotImage as the transfer medium
            var updatedSnapshotName = "union-snapshot";
            var upgradedClient = new OpenSearchClient(ConnectionContextTestParams.builder()
                    .host(upgradedSourceCluster.getUrl())
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var updatedSnapshotCreator = new FileSystemSnapshotCreator(
                    updatedSnapshotName,
                    upgradedClient,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                    List.of(),
                    snapshotContext.createSnapshotCreateContext()
            );

            // Get snapshot for ES 6
            SnapshotRunner.runAndWaitForCompletion(updatedSnapshotCreator);

            arguments.fileSystemRepoPath = localDirectory.getAbsolutePath();
            arguments.snapshotName = updatedSnapshotCreator.getSnapshotName();
            arguments.sourceVersion = ES_V6_8_23.getVersion();
            arguments.targetArgs.host = targetCluster.getUrl();
            // Set up data filters to include only the test index and templates
            var dataFilterArgs = new DataFilterArgs();
            dataFilterArgs.indexAllowlist = List.of(originalIndexName);
            arguments.dataFilterArgs = dataFilterArgs;

            // Use split for multi type mappings resolution
            arguments.metadataTransformationParams = TestMetadataTransformationParams.builder()
                    .multiTypeResolutionBehavior(IndexMappingTypeRemoval.MultiTypeResolutionBehavior.UNION)
                    .build();

            // Execute the migration with the custom transformation
            var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
            var metadata = new MetadataMigration();

            MigrationItemResult result = metadata.migrate(arguments).execute(metadataContext);

            // Verify the migration result
            log.info(result.asCliOutput());
            assertThat(result.getExitCode(), equalTo(0));
            assertThat(result.getItems().getIndexes().size(), equalTo(1));
            var actualCreationResult = result.getItems().getIndexes().get(0);
            assertThat(actualCreationResult.getException(), instanceOf(IndexTransformationException.class));
            assertThat(actualCreationResult.getName(), equalTo(originalIndexName));

            // Verify that the transformed index exists on the target cluster
            var res = targetOperations.get("/" + originalIndexName);
            assertThat(res.getKey(), equalTo(404));

        }
    }

    @Data
    @Builder
    private static class TestMetadataTransformationParams implements MetadataTransformerParams {
        private IndexMappingTypeRemoval.MultiTypeResolutionBehavior multiTypeResolutionBehavior;
    }

}
