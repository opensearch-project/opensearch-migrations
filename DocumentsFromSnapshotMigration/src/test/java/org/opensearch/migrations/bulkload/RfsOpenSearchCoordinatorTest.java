package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.data.IndexOptions;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;
import org.opensearch.migrations.data.workloads.Workloads;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

/**
 * Focused integration test for running RFS with a separate coordinator cluster.
 * This validates end-to-end document backfill correctness with two workers and
 * work coordination happening on a different cluster than the target.
 */
@Tag("isolatedTest")
@Slf4j
public class RfsOpenSearchCoordinatorTest extends SourceTestBase {

    private static final int RFS_WORKER_COUNT = 2;
    private static final int SHARDS = 3;
    private static final int TOTAL_DOCS = 3000;
    private static final int PROCESS_TIMEOUT_SECONDS = 240;
    private static final int MAX_ROUNDS = 20;
    private static final String SESSION_NAME = "rfs-opensearch-coordinator-e2e";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static Stream<Arguments> testParameters() {
        return Stream.concat(
            // Match LeaseExpirationTest coverage:
            // all supported pairs (excluding ES 1.x/2.x) with forceMoreSegments=false.
            SupportedClusters.supportedPairs(true).stream()
                .filter(migrationPair -> !VersionMatchers.isES_2_X.test(migrationPair.source().getVersion()))
                .filter(migrationPair -> !VersionMatchers.isES_1_X.test(migrationPair.source().getVersion()))
                .map(migrationPair -> Arguments.of(false, migrationPair.source(), migrationPair.target())),
            // Keep the additional segment-heavy coverage aligned as well.
            Stream.of(Arguments.of(true, SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_4))
        );
    }

    @ParameterizedTest(name = "forceMoreSegments={0}, sourceClusterVersion={1}, targetClusterVersion={2}")
    @MethodSource("testParameters")
    @SneakyThrows
    void useDedicatedOpenSearchClusterForRfsWorkCoordinator(boolean forceMoreSegments,
                                                            SearchClusterContainer.ContainerVersion sourceClusterVersion,
                                                            SearchClusterContainer.ContainerVersion targetClusterVersion) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var tempDirSnapshot = Files.createTempDirectory("separateCoordinator_snapshot");
        var tempDirLuceneRoot = Files.createTempDirectory("separateCoordinator_lucene_root");

        try (
            var network = Network.newNetwork();
            var sourceContainer = new SearchClusterContainer(sourceClusterVersion)
                .withAccessToHost(true)
                .withNetwork(network);
            var targetContainer = new SearchClusterContainer(targetClusterVersion)
                .withAccessToHost(true)
                .withNetwork(network);
            var osCoordinatorContainer = new SearchClusterContainer(SearchClusterContainer.OS_V3_0_0)
                .withAccessToHost(true)
                .withNetwork(network)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(sourceContainer::start),
                CompletableFuture.runAsync(targetContainer::start),
                CompletableFuture.runAsync(osCoordinatorContainer::start)
            ).join();

            var sourceClusterOperations = new ClusterOperations(sourceContainer);
            sourceClusterOperations.createIndex(
                "geonames",
                "{\"settings\":{\"index\":{\"number_of_shards\":3,\"number_of_replicas\":0}}}"
            );

            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(sourceContainer.getUrl())
                .build()
                .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var generator = new WorkloadGenerator(sourceClient);
            var workloadOptions = new WorkloadOptions();
            workloadOptions.setTotalDocs(TOTAL_DOCS);
            workloadOptions.setWorkloads(List.of(Workloads.GEONAMES));
            workloadOptions.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, SHARDS);
            workloadOptions.setRefreshAfterEachWrite(forceMoreSegments);
            workloadOptions.setMaxBulkBatchSize(forceMoreSegments ? 10 : 1000);
            if (VersionMatchers.isES_5_X.or(VersionMatchers.isES_6_X).test(sourceClusterVersion.getVersion())) {
                workloadOptions.setDefaultDocType("myType");
            }
            generator.generate(workloadOptions);

            var args = new CreateSnapshot.Args();
            args.snapshotName = SNAPSHOT_NAME;
            args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            args.sourceArgs.host = sourceContainer.getUrl();

            var snapshotCreator = new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext());
            snapshotCreator.run();
            sourceContainer.copySnapshotData(tempDirSnapshot.toString());

            runWorkersUntilNoWorkLeft(
                tempDirSnapshot,
                tempDirLuceneRoot,
                targetContainer.getUrl(),
                osCoordinatorContainer.getUrl(),
                forceMoreSegments,
                sourceClusterVersion
            );

            // Expected outcome 1:
            // all documents from source indices are present on target with matching counts.
            checkClusterMigrationOnFinished(
                sourceContainer,
                targetContainer,
                DocumentMigrationTestContext.factory().noOtelTracking()
            );

            // Expected outcome 2:
            // coordination state is written on the separate coordinator cluster, proving
            // the run did not silently fall back to target-host coordination.
            var workingStateDump = dumpWorkingStateIndex(osCoordinatorContainer.getUrl());
            assertWorkingStateHasEntries(workingStateDump);

            log.atInfo().setMessage("Separate coordinator migration completed and coordinator state verified").log();
        } finally {
            FileSystemUtils.deleteDirectories(tempDirSnapshot.toString(), tempDirLuceneRoot.toString());
        }
    }

    @SneakyThrows
    private static void runWorkersUntilNoWorkLeft(
        Path tempDirSnapshot,
        Path tempDirLuceneRoot,
        String targetHost,
        String coordinatorHost,
        boolean forceMoreSegments,
        SearchClusterContainer.ContainerVersion sourceClusterVersion
    ) {
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            var workerDirs = new ArrayList<Path>(RFS_WORKER_COUNT);
            var processes = new ArrayList<Process>(RFS_WORKER_COUNT);
            var exitCodes = new ArrayList<Integer>(RFS_WORKER_COUNT);

            for (int worker = 1; worker <= RFS_WORKER_COUNT; worker++) {
                var workerLuceneDir = Files.createDirectory(tempDirLuceneRoot.resolve("round-" + round + "-worker-" + worker));
                workerDirs.add(workerLuceneDir);

                String[] additionalArgs = {
                    "--source-version", sourceClusterVersion.getVersion().toString(),
                    "--session-name", SESSION_NAME,
                    "--coordinator-host", coordinatorHost,
                    "--max-connections", "2",
                    "--documents-per-bulk-request", forceMoreSegments ? "10" : "100"
                };

                ProcessBuilder processBuilder = setupProcess(tempDirSnapshot, workerLuceneDir, targetHost, additionalArgs);
                processes.add(runAndMonitorProcess(processBuilder));
            }

            for (Process process : processes) {
                boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroy();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                    Assertions.fail("RFS worker process timed out in round " + round);
                }
                exitCodes.add(process.exitValue());
            }

            for (Path workerDir : workerDirs) {
                FileSystemUtils.deleteDirectories(workerDir.toString());
            }

            log.atInfo().setMessage("Round {} worker exit codes: {}")
                .addArgument(round)
                .addArgument(exitCodes)
                .log();

            boolean allNoWorkLeft = true;
            for (int exitCode : exitCodes) {
                if (exitCode != 0 && exitCode != org.opensearch.migrations.RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE) {
                    Assertions.fail("Unexpected RFS worker exit code " + exitCode + " in round " + round);
                }
                if (exitCode != org.opensearch.migrations.RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE) {
                    allNoWorkLeft = false;
                }
            }

            if (allNoWorkLeft) {
                // Expected outcome 3:
                // workers eventually report NO_WORK_LEFT (exit code 3), proving
                // coordinated convergence rather than perpetual retries/timeouts.
                return;
            }
        }

        Assertions.fail("Workers did not converge to NO_WORK_LEFT within " + MAX_ROUNDS + " rounds");
    }

    private static String dumpWorkingStateIndex(String coordinatorHost) {
        var client = new RestClient(ConnectionContextTestParams.builder()
            .host(coordinatorHost)
            .build()
            .toConnectionContext());

        var response = client.get(".migrations_working_state_" + SESSION_NAME + "/_search?size=1000", null);
        Assertions.assertEquals(200, response.statusCode,
            "Expected to read .migrations_working_state from coordinator cluster");
        return response.body == null ? "" : response.body;
    }

    @SneakyThrows
    private static void assertWorkingStateHasEntries(String workingStateDump) {
        Assertions.assertFalse(workingStateDump.isBlank(), "Expected non-empty coordinator working state response body");
        var responseBody = OBJECT_MAPPER.readTree(workingStateDump);
        var totalNode = responseBody.path("hits").path("total");
        var total = totalNode.isObject() ? totalNode.path("value").asLong(0) : totalNode.asLong(0);
        if (total == 0) {
            total = responseBody.path("hits").path("hits").size();
        }
        Assertions.assertTrue(total > 0, "Expected at least one coordinator working-state document");
    }
}
