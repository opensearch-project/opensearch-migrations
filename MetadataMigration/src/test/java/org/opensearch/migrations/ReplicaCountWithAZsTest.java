package org.opensearch.migrations;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterWithZoneAwarenessContainer;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.CreationResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag("slowTest")
public class ReplicaCountWithAZsTest extends BaseMigrationTest{

    private static final SearchClusterContainer.ContainerVersion SOURCE_VERSION = SearchClusterContainer.ES_V7_10_2;
    private static final SearchClusterContainer.ContainerVersion TARGET_VERSION = SearchClusterContainer.OS_LATEST;
    private static final String TARGET_DOCKER_HOSTNAME = "target";

    private static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of(1),
                Arguments.of(2),
                Arguments.of(3)
        );
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
            List.of(1, 5).forEach(
                    shardCount -> {
                        List.of(0, 1, 2, 3).forEach(replicaCount -> {
                            var body = "{\"settings\": {\"index\": {\"number_of_replicas\": "+ replicaCount +", \"number_of_shards\": "+ shardCount + "}}}";
                            sourceOperations.createIndex("index_" + shardCount + "_" + replicaCount, body);
                            createdIndexCount.getAndIncrement();
                        });
                    }
            );

            MigrateOrEvaluateArgs arguments = new MigrateOrEvaluateArgs();
            arguments.sourceArgs.host = sourceCluster.getUrl();
            arguments.targetArgs.host = targetCluster.getUrl();

            MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);
            assertThat(result.getExitCode(), equalTo(0));
            // Ensure that the same number of indices were created (successfully) on the target cluster
            assertThat(result.getItems().getIndexes().stream().filter(CreationResult::wasSuccessful).count(), equalTo((long) createdIndexCount.get()));
        }
    }
}
