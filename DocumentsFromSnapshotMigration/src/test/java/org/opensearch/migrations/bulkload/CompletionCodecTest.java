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
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;

@Slf4j
@Tag("isolatedTest")
public class CompletionCodecTest extends SourceTestBase {
    @TempDir
    private File localDirectory;

    private static Stream<Arguments> completionFieldScenario() {
        return Stream.of(
                Arguments.of(SearchClusterContainer.ES_V7_17, SearchClusterContainer.OS_V1_3_16),
                Arguments.of(SearchClusterContainer.ES_V7_17, SearchClusterContainer.OS_V2_19_1),
                Arguments.of(SearchClusterContainer.ES_V7_17, SearchClusterContainer.OS_V3_0_0),
                Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V1_3_16),
                Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V2_19_1),
                Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V3_0_0),
                Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_V1_3_16),
                Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_V2_19_1),
                Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_V3_0_0)
        );
    }

    @ParameterizedTest(name = "Completion Field: Source {0} to Target {1}")
    @MethodSource("completionFieldScenario")
    public void completionFieldMigrationTest(
            final SearchClusterContainer.ContainerVersion sourceVersion,
            final SearchClusterContainer.ContainerVersion targetVersion
    ) {
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
            Startables.deepStart(sourceCluster, targetCluster).join();
            var sourceClusterOperations = new ClusterOperations(sourceCluster);
            var targetClusterOperations = new ClusterOperations(targetCluster);

            // Number of default shards is different across different versions on ES/OS.
            // So we explicitly set it.
            var numberOfShards = 3;

            var sourceVersion = sourceCluster.getContainerVersion().getVersion();
            boolean supportsSoftDeletes = VersionMatchers.equalOrGreaterThanES_6_5.test(sourceVersion);

            // === Create index with completion field ===
            String specialIndexName = "completion_index";
            sourceClusterOperations.createIndexWithCompletionField(specialIndexName, numberOfShards);

            // Insert a single document into it
            String completionDoc =
                "{" +
                "    \"completion\": \"openai\" " +
                "}";
            String docType = sourceClusterOperations.defaultDocType();
            sourceClusterOperations.createDocument(specialIndexName, "1", completionDoc, null, docType);

            // Perform a refresh
            sourceClusterOperations.post("/_refresh", null);

            // === ACTION: Take a snapshot ===
            var snapshotName = "my_snap";
            var snapshotRepoName = "my_snap_repo";
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(sourceCluster.getUrl())
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var snapshotCreator = new FileSystemSnapshotCreator(
                    snapshotName,
                    snapshotRepoName,
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
            var clockJitter = new Random(1);

            var transformationConfig = VersionMatchers.isES_5_X.or(VersionMatchers.isES_6_X)
                    .test(targetCluster.getContainerVersion().getVersion()) ?
                    "[{\"NoopTransformerProvider\":{}}]" // skip transformations including doc type removal
                    : null;

            // ExpectedMigrationWorkTerminationException is thrown on completion.
            var expectedTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
                    sourceRepo,
                    snapshotName,
                    List.of(),
                    targetCluster,
                    runCounter,
                    clockJitter,
                    testDocMigrationContext,
                    sourceCluster.getContainerVersion().getVersion(),
                    targetCluster.getContainerVersion().getVersion(),
                    transformationConfig
            ));

            Assertions.assertEquals(numberOfShards + 1, expectedTerminationException.numRuns);

            // Check that the docs were migrated
            checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);

            // === Validate completion field doc migrated to target ===
            var res = targetClusterOperations.get("/completion_index/_doc/1");
            System.out.println("Completion doc in target: " + res.getValue());

            JsonNode doc = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getValue());
            JsonNode sourceNode = doc.path("_source").path("completion");

            Assertions.assertTrue(sourceNode.isTextual() || sourceNode.isArray(), "Expected 'completion' field to be present and textual or array");
        } finally {
            deleteTree(localDirectory.toPath());
        }
    }
}
