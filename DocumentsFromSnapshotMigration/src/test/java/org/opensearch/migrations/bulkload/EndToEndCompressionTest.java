package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.ArrayList;
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
import org.opensearch.migrations.utils.FileSystemUtils;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;

@Tag("isolatedTest")
@Slf4j
public class EndToEndCompressionTest extends SourceTestBase {
    @TempDir
    private File localDirectory;

    private static Stream<Arguments> scenarios() {
        var target = SearchClusterContainer.OS_LATEST;
        return
                SupportedClusters.supportedSources(true).stream()
                        .flatMap(source -> {
                            List<Arguments> args = new ArrayList<>();

                            // Add best_compression if supported
                            if (!VersionMatchers.isES_1_X.test(source.getVersion())) {
                                args.add(Arguments.of(source, target, "best_compression"));
                            }

                            // TODO: Enable below tests once zstd and zstd_no_dict are supported on OS 2
                            // Add zstd and zstd_no_dict if OS version >= 2.9.0
                            if (VersionMatchers.equalOrGreaterThanOS_2_9.test(source.getVersion())) {
                                log.atInfo().setMessage("Skipping OS 2 test with ZSTD since unsupported")
                                        .log();
//                        args.add(Arguments.of(source, target, "zstd"));
//                        args.add(Arguments.of(source, target, "zstd_no_dict"));
                            }
                            return args.stream();
                        });
    }

    @ParameterizedTest(name = "Source {0} to Target {1} using codec {2}")
    @MethodSource("scenarios")
    public void migrationDocuments(
            final SearchClusterContainer.ContainerVersion sourceVersion,
            final SearchClusterContainer.ContainerVersion targetVersion,
            final String codec) {
        try (
                final var sourceCluster = new SearchClusterContainer(sourceVersion);
                final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            migrationDocumentsWithClusters(sourceCluster, targetCluster, codec);
        }
    }

    @SneakyThrows
    private void migrationDocumentsWithClusters(
            final SearchClusterContainer sourceCluster,
            final SearchClusterContainer targetCluster,
            final String codec
    ) {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();

        try {
            // === ACTION: Set up the source/target clusters ===
            Startables.deepStart(sourceCluster, targetCluster).join();

            var indexName = "compressed_index";
            var numberOfShards = 1;
            var sourceClusterOperations = new ClusterOperations(sourceCluster);
            var targetClusterOperations = new ClusterOperations(targetCluster);

            String body = String.format(
                    "{" +
                            "  \"settings\": {" +
                            "    \"number_of_shards\": %d," +
                            "    \"number_of_replicas\": 0," +
                            "    \"codec\": \"%s\"," +
                            "    \"refresh_interval\": -1" +
                            "  }" +
                            "}",
                    numberOfShards,
                    codec
            );
            sourceClusterOperations.createIndex(indexName, body);
            targetClusterOperations.createIndex(indexName, body);

            sourceClusterOperations.createDocument(indexName, "222", "{\"score\": 42}");
            sourceClusterOperations.get("/_refresh");

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
            var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(
                    sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);

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
        } finally {
            FileSystemUtils.deleteDirectories(localDirectory.toString());
        }
    }
}
