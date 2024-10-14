package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.worker.DocumentsRunner;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag("isolatedTest")
public class EndToEndTest extends SourceTestBase {
    @TempDir
    private File localDirectory;

    private static Stream<Arguments> scenarios() {
        var scenarios = Stream.<Arguments>builder();

        for (var sourceCluster : SupportedClusters.sources()) {
            for (var targetCluster : SupportedClusters.targets()) {
                scenarios.add(Arguments.of(sourceCluster, targetCluster));
            }
        }

        return scenarios.build();
    }

    @ParameterizedTest(name = "Source {0} to Target {1}")
    @MethodSource(value = "scenarios")
    public void migrationDocuments(
        final SearchClusterContainer.ContainerVersion sourceVersion,
        final SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            migrationDocumentsWithClusters(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void migrationDocumentsWithClusters(
        final SearchClusterContainer sourceCluster,
        final SearchClusterContainer targetCluster
    ) {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();

        try {

            // === ACTION: Set up the source/target clusters ===
            var bothClustersStarted = CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> sourceCluster.start()),
                CompletableFuture.runAsync(() -> targetCluster.start())
            );
            bothClustersStarted.join();

            var indexName = "blog_2023";
            var sourceClusterOperations = new ClusterOperations(sourceCluster.getUrl());
            sourceClusterOperations.createDocument(indexName, "222", "{\"author\":\"Tobias Funke\"}");

            // === ACTION: Take a snapshot ===
            var snapshotName = "my_snap";
            var sourceClient = new OpenSearchClient(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl())
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
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            sourceCluster.copySnapshotData(localDirectory.toString());
            var sourceRepo = new FileSystemRepo(localDirectory.toPath());

            // === ACTION: Migrate the documents ===
            final var clockJitter = new Random(1);
            var result = migrateDocumentsWithOneWorker(
                sourceRepo,
                snapshotName,
                List.of(),
                targetCluster.getUrl(),
                clockJitter,
                testDocMigrationContext,
                sourceCluster.getContainerVersion().getVersion(),
                false
            );
            assertThat(result, equalTo(DocumentsRunner.CompletionStatus.WORK_COMPLETED));

            // Check that the docs were migrated
            checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);
        } finally {
            deleteTree(localDirectory.toPath());
        }
    }

}
