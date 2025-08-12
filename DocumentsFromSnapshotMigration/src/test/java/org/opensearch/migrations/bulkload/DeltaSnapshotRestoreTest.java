package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.lifecycle.Startables;

/**
 * Test class for delta snapshot restore functionality.
 * This test sets up a scenario where we have two snapshots and want to
 * restore only the changes between them.
 * 
 * Initial implementation: Just restores from the second snapshot.
 * Future implementation: Will calculate and apply the delta between snapshots.
 */
@Slf4j
@Tag("isolatedTest")
public class DeltaSnapshotRestoreTest extends SourceTestBase {
    @TempDir
    private File localDirectory;

    @Test
    public void testDeltaSnapshotRestore() {
        // Using a recent version that supports all features we need
        final var sourceVersion = SearchClusterContainer.ES_V7_10_2;
        final var targetVersion = SearchClusterContainer.OS_V2_19_1;
        
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
                "    \"index.soft_deletes.enabled\": true," +
                "    \"refresh_interval\": -1" +
                "  }" +
                "}",
                numberOfShards
            );
            sourceClusterOperations.createIndex(indexName, indexSettings);
            targetClusterOperations.createIndex(indexName, indexSettings);

            // === ACTION: Create first document on source ===
            String doc = "{\"content\": \"document\"}";
            sourceClusterOperations.createDocument(indexName, "doc1", doc);
            
            // Also create the same document on target to ensure it is removed to maintain consistent state
            targetClusterOperations.createDocument(indexName, "doc1", doc);

            // === ACTION: Create document that will exist on snap1 and snap2 and should not be migrated ===
            sourceClusterOperations.createDocument(indexName, "snap1Doc", doc);

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
            sourceClusterOperations.deleteDocument(indexName, "doc1", null, null);
            sourceClusterOperations.createDocument(indexName, "doc2", doc);
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

            // === ACTION: Migrate documents from snapshot2 ===
            // TODO: In the future, this will accept both snapshot1Name and snapshot2Name
            // and calculate the delta between them
            var runCounter = new AtomicInteger();
            var clockJitter = new Random(1);

            // Currently just migrating from snapshot2
            // Future: Will calculate delta between snapshot1 and snapshot2
            var expectedTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
                    sourceRepo,
                    snapshot2Name,  // Currently using snapshot2; future will use both snapshots
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

            // Check that doc2 exists
            var doc2Response = targetClusterOperations.get("/" + indexName + "/_source/doc2");
            Assertions.assertEquals(200, doc2Response.getKey(), "doc2 should exist on target");

            // Check that doc2 exists
            var doc1Response = targetClusterOperations.get("/" + indexName + "/_source/doc1");
            // TODO: When delta feature is implemented, this should fail (doc1 should be deleted)
            // Assertions.assertEquals(404, doc1Response.statusCode, "doc1 should be deleted on target");
            Assertions.assertEquals(200, doc1Response.getKey(), "doc1 should exist on target");

            // Check if snap1Doc exists
            var snap1DocResponse = targetClusterOperations.get("/" + indexName + "/_source/snap1Doc");
            // TODO: When delta feature is implemented, this should fail (snap1Doc should have never been on target)
            // Assertions.assertEquals(404, snap1DocResponse.statusCode, "snap1Doc should not be found on target");
            Assertions.assertEquals(200, snap1DocResponse.getKey(), "snapDoc1 should exist on target");
        } finally {
            deleteTree(localDirectory.toPath());
        }
    }
}
