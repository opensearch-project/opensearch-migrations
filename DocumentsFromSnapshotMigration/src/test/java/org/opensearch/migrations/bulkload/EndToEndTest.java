package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;


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
            var numberOfShards = 3;
            var sourceClusterOperations = new ClusterOperations(sourceCluster.getUrl());
            var targetClusterOperations = new ClusterOperations(targetCluster.getUrl());

            // Number of default shards is different across different versions on ES/OS.
            // So we explicitly set it.
            String body = String.format(
                "{" +
                "  \"settings\": {" +
                "    \"index\": {" +
                "      \"number_of_shards\": %d," +
                "      \"number_of_replicas\": 0" +
                "    }" +
                "  }" +
                "}",
                numberOfShards
            );
            sourceClusterOperations.createIndex(indexName, body);
            targetClusterOperations.createIndex(indexName, body);


            sourceClusterOperations.createDocument(indexName, "222", "{\"author\":\"Tobias Funke\"}");
            sourceClusterOperations.createDocument(indexName, "223", "{\"author\":\"Tobias Funke\", \"category\": \"cooking\"}", "1", null);
            sourceClusterOperations.createDocument(indexName, "224", "{\"author\":\"Tobias Funke\", \"category\": \"cooking\"}", "1", null);
            sourceClusterOperations.createDocument(indexName, "225", "{\"author\":\"Tobias Funke\", \"category\": \"tech\"}", "2", null);

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
            var sourceRepo = new FileSystemRepo(localDirectory.toPath());

            // === ACTION: Migrate the documents ===
            var runCounter = new AtomicInteger();
            final var clockJitter = new Random(1);

            // ExpectedMigrationWorkTerminationException is thrown on completion.
            var expectedTerminationException = Assertions.assertThrows(
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
            );

            Assertions.assertEquals(numberOfShards + 1, expectedTerminationException.numRuns);

            // Check that the docs were migrated
            checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);

            // Check that that docs were migrated with routing
            checkDocsWithRouting(sourceCluster, testDocMigrationContext);
            checkDocsWithRouting(targetCluster, testDocMigrationContext);
        } finally {
            deleteTree(localDirectory.toPath());
        }
    }

    private void checkDocsWithRouting(
        SearchClusterContainer clusterContainer,
        DocumentMigrationTestContext context) {
        var clusterClient = new RestClient(ConnectionContextTestParams.builder()
            .host(clusterContainer.getUrl())
            .build()
            .toConnectionContext()
        );

        // Check that search by routing works as expected.
        var requests = new SearchClusterRequests(context);
        var hits = requests.searchIndexByQueryString(clusterClient, "blog_2023", "category:cooking", "1");

        Assertions.assertTrue(hits.isArray() && hits.size() == 2);

        for (JsonNode hit : hits) {
            String routing = hit.path("_routing").asText();
            Assertions.assertEquals("1", routing);
        }
    }

}
