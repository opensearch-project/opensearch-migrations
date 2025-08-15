package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;

/**
 * Test class for delta snapshot restore functionality.
 * This test sets up a scenario where we have two snapshots and want to
 * restore only the changes between them.
 */
@Slf4j
@Tag("isolatedTest")
public class DeltaSnapshotRestoreTest extends SourceTestBase {
    @TempDir
    private File localDirectory;

    private static Stream<Arguments> scenarios() {
        var target = SearchClusterContainer.OS_LATEST;
        return SupportedClusters.supportedSources(true).stream()
                .flatMap(source -> Stream.of(Arguments.of(source, target)));
    }

    @ParameterizedTest(name = "Source {0} to Target {1}")
    @MethodSource("scenarios")
    public void testDeltaSnapshotRestore(
            final SearchClusterContainer.ContainerVersion sourceVersion,
            final SearchClusterContainer.ContainerVersion targetVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            performDeltaSnapshotRestoreTest(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void performDeltaSnapshotRestoreTest(
        final SearchClusterContainer sourceCluster,
        final SearchClusterContainer targetCluster
    ) {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();

        try {
            // === ACTION: Set up the source/target clusters ===
            Startables.deepStart(sourceCluster, targetCluster).join();

            var indexName = "test_index";
            var numberOfShards = 1; // Using single shard for simplicity
            var sourceClusterOperations = new ClusterOperations(sourceCluster);
            var targetClusterOperations = new ClusterOperations(targetCluster);

            // Create index with single shard on both source and target
            String indexSettings = String.format(
                "{" +
                "  \"settings\": {" +
                "    \"number_of_shards\": %d," +
                "    \"number_of_replicas\": 0," +
                "    \"refresh_interval\": -1," +
                    // TODO: Define behavior on ES 6 when soft_deletes is disabled
                    ((VersionMatchers.isES_6_X.test(sourceCluster.getContainerVersion().getVersion())) ?
                    "    \"index.soft_deletes.enabled\": true," : "") +
                    // Disable segment merges to ensure consistent test execution
                "    \"merge.policy.floor_segment\": \"1gb\"," +
                "    \"merge.policy.max_merged_segment\": \"1gb\"" +
                "  }" +
                "}",
                numberOfShards
            );
            sourceClusterOperations.createIndex(indexName, indexSettings);
            targetClusterOperations.createIndex(indexName, indexSettings);

            String doc = "{\"content\": \"document\"}";

            var docIdDeletedOnSecond = "docDeletedOnSecond";
            sourceClusterOperations.createDocument(indexName, docIdDeletedOnSecond, doc);

            var docIdOnBoth = "docOnBoth";
            sourceClusterOperations.createDocument(indexName, docIdOnBoth, doc);

            // Refresh to ensure documents are searchable
            sourceClusterOperations.post("/_refresh", null);
            targetClusterOperations.post("/_refresh", null);

            // === ACTION: Take first snapshot ===
            var snapshot1Name = "snapshot1";
            var snapshotRepoName = "test_repo";
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(sourceCluster.getUrl())
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            
            var snapshotCreator1 = new FileSystemSnapshotCreator(
                snapshot1Name,
                snapshotRepoName,
                sourceClient,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator1);

            // === ACTION: Delete first document and create second document ===
            sourceClusterOperations.deleteDocument(indexName, docIdDeletedOnSecond, null, null);
            var docIdOnlyOnSecond = "docOnlyOnSecond";
            sourceClusterOperations.createDocument(indexName, docIdOnlyOnSecond, doc);
            sourceClusterOperations.post("/_refresh", null);

            // === ACTION: Take second snapshot ===
            var snapshot2Name = "snapshot2";
            var snapshotCreator2 = new FileSystemSnapshotCreator(
                snapshot2Name,
                snapshotRepoName,
                sourceClient,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator2);
            
            // Copy snapshot data to local directory
            sourceCluster.copySnapshotData(localDirectory.toString());
            var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(
                    sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);

            // === ACTION: Migrate state change between snapshot1 and snapshot2
            // Create docIdDeletedOnSecond to verify it's deleted when going from snapshot1 -> snapshot2
            targetClusterOperations.createDocument(indexName, docIdDeletedOnSecond, doc);
            {
                var runCounter = new AtomicInteger();
                var clockJitter = new Random(1);

                var expectedTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
                    sourceRepo,
                    snapshot1Name,
                    snapshot2Name,
                    List.of(),
                    targetCluster,
                    runCounter,
                    clockJitter,
                    testDocMigrationContext,
                    sourceCluster.getContainerVersion().getVersion(),
                    targetCluster.getContainerVersion().getVersion(),
                    null
                ));

                Assertions.assertEquals(numberOfShards + 1, expectedTerminationException.numRuns);

                // === VERIFICATION: Check the results ===
                targetClusterOperations.post("/_refresh", null);
                {
                    var response = targetClusterOperations.get("/" + indexName + "/_source/" + docIdDeletedOnSecond);
                    // docIdDeletedOnSecond should be deleted once deletions are supported
                    // Assertions.assertEquals(404, doc2Response.getKey(), "doc2 should be deleted on target");
                    Assertions.assertEquals(200, response.getKey(), docIdDeletedOnSecond + " should not be found on target");
                }
                {
                    var response = targetClusterOperations.get("/" + indexName + "/_source/" + docIdOnlyOnSecond);
                    Assertions.assertEquals(200, response.getKey(), docIdOnlyOnSecond + " should be created");
                }
                {
                    var response = targetClusterOperations.get("/" + indexName + "/_source/" + docIdOnBoth);
                    Assertions.assertEquals(404, response.getKey(), docIdOnBoth + " should not be created");
                }
            }

            // Run second time reversing base and current snapshot
            targetClusterOperations.delete("/.migrations_working_state");
            // Simulate delete that should have occurred in prior RFS but is currently not supported
            {
                var response = targetClusterOperations.delete("/" + indexName + "/_doc/" + docIdDeletedOnSecond);
                Assertions.assertEquals(200, response.getKey(), docIdDeletedOnSecond + " should be manually deleted");
            }
            {
                var runCounter = new AtomicInteger();
                var clockJitter = new Random(1);

                var expectedTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
                    sourceRepo,
                    snapshot2Name,
                    snapshot1Name,
                    List.of(),
                    targetCluster,
                    runCounter,
                    clockJitter,
                    testDocMigrationContext,
                    sourceCluster.getContainerVersion().getVersion(),
                    targetCluster.getContainerVersion().getVersion(),
                    null
                ));

                Assertions.assertEquals(numberOfShards + 1, expectedTerminationException.numRuns);

                // === VERIFICATION: Check the results ===
                targetClusterOperations.post("/_refresh", null);
                {
                    var response = targetClusterOperations.get("/" + indexName + "/_source/" + docIdDeletedOnSecond);
                    Assertions.assertEquals(200, response.getKey(), docIdDeletedOnSecond + " should created when restoring first snapshot");
                }
                {
                    var response = targetClusterOperations.get("/" + indexName + "/_source/" + docIdOnlyOnSecond);
                    // docIdDeletedOnSecond should be deleted once deletions are supported
                    // Assertions.assertEquals(404, doc2Response.getKey(), "doc2 should not exist");
                    Assertions.assertEquals(200, response.getKey(), docIdOnlyOnSecond + " is created because deletes are not supported");
                }
                {
                    var response = targetClusterOperations.get("/" + indexName + "/_source/" + docIdOnBoth);
                    Assertions.assertEquals(404, response.getKey(), docIdOnlyOnSecond + " should not be created");
                }
            }
        } finally {
            deleteTree(localDirectory.toPath());
        }
    }
}
