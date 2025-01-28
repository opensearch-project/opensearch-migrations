package org.opensearch.migrations.bulkload;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.Getter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ES_V6_8_23;

public class SnapshotAndRestoreES5Test extends SourceTestBase {
    @TempDir
    protected Path localDirectoryES5;
    @TempDir
    protected Path localDirectory;
    @Getter
    protected SearchClusterContainer sourceCluster;
    @Getter
    protected SearchClusterContainer targetCluster;
    @Test
    public void es5UpgradedClusterTest() throws IOException {
        var es5Repo = "es5";
        var snapshotNameEs5 = "es5-created-index";
        var originalIndexName = "test_index";
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();
        try (
            final var indexCreatedCluster = new SearchClusterContainer(SearchClusterContainer.ES_V5_6_16)
        ) {
            indexCreatedCluster.start();
            var indexCreatedOperations = new ClusterOperations(indexCreatedCluster);
            // Create index and add documents on the source cluster
            indexCreatedOperations.createIndex(originalIndexName);
            indexCreatedOperations.createDocument(originalIndexName, "1", "{\"field1\":\"My Name\"}", null, "type1");
            indexCreatedOperations.createDocument(originalIndexName, "2", "{\"field1\":\"string\", \"field2\":123}", null, "type2");
            indexCreatedOperations.createDocument(originalIndexName, "3", "{\"field3\":1.1}", null, "type3");
            indexCreatedOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, es5Repo);
            indexCreatedOperations.takeSnapshot(es5Repo, snapshotNameEs5, originalIndexName);
            indexCreatedCluster.copySnapshotData(localDirectoryES5.toString());
        }
        try (
            final var upgradedSourceCluster = new SearchClusterContainer(ES_V6_8_23);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            this.sourceCluster = upgradedSourceCluster;
            this.targetCluster = targetCluster;
            startClusters();
            upgradedSourceCluster.putSnapshotData(localDirectoryES5.toString());
            var upgradedSourceOperations = new ClusterOperations(upgradedSourceCluster);
            // Register snapshot repository and restore snapshot in ES 6 cluster
            upgradedSourceOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, es5Repo);
            upgradedSourceOperations.restoreSnapshot(es5Repo, snapshotNameEs5);
            // Create index and add document on es6
            var es6IndexName = "es6-created-index";
            upgradedSourceOperations.createIndex(es6IndexName);
            upgradedSourceOperations.createDocument(es6IndexName, "1", "{\"field1\":\"My Name\"}");
            upgradedSourceOperations.put("/_refresh", null);
            // === ACTION: Take a snapshot ===
            var snapshotName = "my_snap";
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl())
                .insecure(true)
                .build()
                .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var snapshotCreator = new FileSystemSnapshotCreator(
                snapshotName,
                sourceClient,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            sourceCluster.copySnapshotData(localDirectory.toString());
            var sourceRepo = new FileSystemRepo(localDirectory);
            // === ACTION: Migrate the documents ===
            var runCounter = new AtomicInteger();
            final var clockJitter = new Random(1);
            // ExpectedMigrationWorkTerminationException is thrown on completion.
            var expectedTerminationException = Assertions.assertTimeout(
                Duration.ofSeconds(30),
                () -> Assertions.assertThrows(
                    ExpectedMigrationWorkTerminationException.class,
                    () -> migrateDocumentsSequentially(
                        sourceRepo,
                        snapshotName,
                        List.of(),
                        targetCluster.getUrl(),
                        runCounter,
                        clockJitter,
                        testDocMigrationContext,
                        sourceCluster.getContainerVersion().getVersion(),
                        false
                    )
                )
            );
            var totalNumberOfShards = 10;
            Assertions.assertEquals(totalNumberOfShards + 1, expectedTerminationException.numRuns);
            // Check that the docs were migrated
            checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);
        } finally {
            deleteTree(localDirectory);
        }
    }
    /**
     * Starts the source and target clusters.
     */
    protected void startClusters() {
        CompletableFuture.allOf(
            CompletableFuture.runAsync(sourceCluster::start),
            CompletableFuture.runAsync(targetCluster::start)
        ).join();
    }
}
