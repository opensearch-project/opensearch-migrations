package org.opensearch.migrations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.IncompatibleReplicaCountException;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterWithZoneAwarenessContainer;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.CreationResult;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

@Nested
@Tag("isolatedTest")
class ReplicaCountWithAZsTest extends BaseMigrationTest{
    private static final SearchClusterContainer.ContainerVersion SOURCE_VERSION = SearchClusterContainer.ES_V7_10_2;

    private static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of(1),
            Arguments.of(2),
            Arguments.of(3));
    }

    @Test
    void testMigrateWithIncompatibleReplicaCountFails() {
        var availabilityZones = 3;
        var replicaCount = 4;
        try (
            final var sourceCluster = new SearchClusterContainer(SOURCE_VERSION);
            final var targetCluster = new SearchClusterWithZoneAwarenessContainer(availabilityZones)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            startClusters();

            // Create a single index on the source cluster with 1 primary and n replicas.
            var indexName = "index_1_" + replicaCount;
            var body =
                "{\"settings\": {\"index\": {\"number_of_replicas\": " + replicaCount + ", \"number_of_shards\": " + 1
                    + "}}}";
            sourceOperations.createIndex(indexName, body);

            MigrateOrEvaluateArgs arguments = new MigrateOrEvaluateArgs();
            arguments.sourceArgs.host = sourceCluster.getUrl();
            arguments.targetArgs.host = targetCluster.getUrl();

            // Run Migrate and check that the index failed with the expected error and index is not present on the target
            // cluster.
            MigrationItemResult migrateResult = executeMigration(arguments, MetadataCommands.MIGRATE);
            Assertions.assertFalse(migrateResult.getItems().getIndexes().get(0).wasSuccessful());
            Assertions.assertTrue(migrateResult.getItems().getIndexes().get(0).wasFatal());
            Assertions.assertEquals(
                migrateResult.getItems().getIndexes().get(0).getException().getClass(),
                IncompatibleReplicaCountException.class
            );
            var indexExistsResult = targetOperations.get("/" + indexName);
            assertThat(indexExistsResult.getValue(), indexExistsResult.getKey(), equalTo(404));
        }
    }

    @ParameterizedTest(name = "Replica count test with {0} AZs")
    @MethodSource(value = "scenarios")
    void testReplicaCounts(int availabilityZoneCount) {
        try (
                final var sourceCluster = new SearchClusterContainer(SOURCE_VERSION);
                final var targetCluster = new SearchClusterWithZoneAwarenessContainer(availabilityZoneCount)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            startClusters();

            // Create indices on source cluster with specified shard and replica counts
            // Shard count is 1 or 5, replica count is 0 through 3
            AtomicInteger createdIndexCount = new AtomicInteger();
            var indexNames = new ArrayList<String>();
            List.of(1, 5).forEach(
                    shardCount -> {
                        List.of(0, 1, 2, 3).forEach(replicaCount -> {
                            var name = "index_" + shardCount + "_" + replicaCount;
                            var body = "{\"settings\": {\"index\": {\"number_of_replicas\": "+ replicaCount +", \"number_of_shards\": "+ shardCount + "}}}";
                            sourceOperations.createIndex(name, body);
                            indexNames.add(name);
                            createdIndexCount.getAndIncrement();
                        });
                    }
            );

            MigrateOrEvaluateArgs arguments = new MigrateOrEvaluateArgs();
            arguments.sourceArgs.host = sourceCluster.getUrl();
            arguments.targetArgs.host = targetCluster.getUrl();

            // Evaluate first and check that errors were thrown appropriately
            MigrationItemResult initialEvaluateResult = executeMigration(arguments, MetadataCommands.EVALUATE);
            if (availabilityZoneCount == 1) {
                Assertions.assertTrue(initialEvaluateResult.getItems().getIndexes().stream().allMatch(CreationResult::wasSuccessful));
            } else {
                Assertions.assertFalse(initialEvaluateResult.getItems().getIndexes().stream().allMatch(CreationResult::wasSuccessful));
                // We expect _some_ of the replica counts (those that are compatible) to succeed, but all of the failures
                // should be due to IncompatibleReplicaCountException
                Assertions.assertTrue(initialEvaluateResult.getItems().getIndexes().stream().allMatch(result ->
                    !result.wasFatal() || result.getException() instanceof IncompatibleReplicaCountException
                ));
            }

            // Set argument for correct number of availability zones
            arguments.clusterAwarenessAttributes = availabilityZoneCount;
            // Run Evaluate again and check that no errors were thrown
            MigrationItemResult modifiedEvaluateResult = executeMigration(arguments, MetadataCommands.EVALUATE);
            Assertions.assertTrue(modifiedEvaluateResult.getItems().getIndexes().stream().allMatch(CreationResult::wasSuccessful));

            // Run Migrate and check that no errors were thrown & indices are present on target cluster
            MigrationItemResult migrateResult = executeMigration(arguments, MetadataCommands.MIGRATE);
            Assertions.assertTrue(migrateResult.getItems().getIndexes().stream().allMatch(CreationResult::wasSuccessful));
            verifyIndexesExistOnTargetCluster(indexNames);
        }
    }

    @Test
    void testMigrateWithMultipleAwarenessAttributeTypes() {
        // The behavior with multiple attribute types is that replica balancing is enforced for the largest value.
        var multipleAttributesValues = List.of(2, 5, 3); var initalReplicas = 3;
        try (
            final var sourceCluster = new SearchClusterContainer(SOURCE_VERSION);
            final var targetCluster = new SearchClusterWithZoneAwarenessContainer(multipleAttributesValues)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            startClusters();

            // Create a single index on the source cluster with 1 primary and n replicas.
            var indexName = "index_1_" + initalReplicas;
            var body =
                "{\"settings\": {\"index\": {\"number_of_replicas\": " + initalReplicas + ", \"number_of_shards\": " + 1
                    + "}}}";
            sourceOperations.createIndex(indexName, body);

            MigrateOrEvaluateArgs arguments = new MigrateOrEvaluateArgs();
            arguments.sourceArgs.host = sourceCluster.getUrl();
            arguments.targetArgs.host = targetCluster.getUrl();

            // Run Migrate and check that the index failed with the expected error and index is not present on the target
            // cluster.
            MigrationItemResult migrateResult = executeMigration(arguments, MetadataCommands.EVALUATE);
            Assertions.assertFalse(migrateResult.getItems().getIndexes().get(0).wasSuccessful());
            Assertions.assertTrue(migrateResult.getItems().getIndexes().get(0).wasFatal());
            Assertions.assertEquals(
                migrateResult.getItems().getIndexes().get(0).getException().getClass(),
                IncompatibleReplicaCountException.class
            );
            arguments.clusterAwarenessAttributes = multipleAttributesValues.stream().max(Integer::compareTo).get();
            MigrationItemResult updatedMigrateResult = executeMigration(arguments, MetadataCommands.MIGRATE);
            Assertions.assertTrue(updatedMigrateResult.getItems().getIndexes().get(0).wasSuccessful());
            verifyIndexesExistOnTargetCluster(List.of(indexName));
        }
    }

    void verifyIndexesExistOnTargetCluster(List<String> indexNames) {
        for (String indexName : indexNames) {
            var res = targetOperations.get("/" + indexName);
            assertThat(res.getValue(), res.getKey(), equalTo(200));
        }
    }
}
