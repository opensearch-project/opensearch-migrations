package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;
import org.opensearch.migrations.data.IndexOptions;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;
import org.opensearch.migrations.data.workloads.Workloads;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.utils.FileSystemUtils;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

@Tag("isolatedTest")
@Slf4j
public class LeaseExpirationTest extends SourceTestBase {

    public static final String COORDINATOR_DOCKER_HOSTNAME = "coordinator";
    public static final String TARGET_DOCKER_HOSTNAME = "target";
    private static final String DEFAULT_COORDINATOR_INDEX_SUFFIX = "";

    private static Stream<Arguments> testParameters() {
        return Stream.concat(
                // Test with all pairs with forceMoreSegments=false
                SupportedClusters.supportedPairs(true).stream()
                        // Skiping ES 2 as it requires the javascript transformer to convert "string"
                        .filter(migrationPair -> !VersionMatchers.isES_2_X.test(migrationPair.source().getVersion()))
                        .filter(migrationPair -> !VersionMatchers.isES_1_X.test(migrationPair.source().getVersion()))
                        .map(migrationPair ->
                                Arguments.of(false, migrationPair.source(), migrationPair.target())),
                // Add test for ES 7 -> OS 2 with forceMoreSegments=true
                Stream.of(Arguments.of(true, SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_4))
        );
    }

    @ParameterizedTest(name = "forceMoreSegments={0}, sourceClusterVersion={1}, targetClusterVersion={2}")
    @MethodSource("testParameters")
    public void testProcessExitsAsExpected(boolean forceMoreSegments,
                                           SearchClusterContainer.ContainerVersion sourceClusterVersion,
                                           SearchClusterContainer.ContainerVersion targetClusterVersion) {
        // Sending 10 docs per request with 2 requests concurrently with each taking 1 second is 40 docs/sec
        // will process 1640 docs in 21 seconds. With 10s lease duration, expect to be finished in 3 leases.
        // This is ensured with the toxiproxy settings, the migration should not be able to be completed
        // faster, but with a heavily loaded test environment, may be slower which is why this is marked as
        // isolated.
        // 2 Shards, for each shard, expect two status code 2 and one status code 0 (3 leases)
        int shards = 2;
        int indexDocCount = 1640 * shards;
        int migrationProcessesPerShard = 3;
        int continueExitCode = 2;
        int finalExitCodePerShard = 0;
        runTestProcessWithCheckpoint(continueExitCode, (migrationProcessesPerShard - 1) * shards,
                finalExitCodePerShard, shards, shards, indexDocCount, forceMoreSegments,
                sourceClusterVersion,
                targetClusterVersion,
                d -> runProcessAgainstToxicTarget(d.tempDirSnapshot, d.tempDirLucene, d.proxyContainer,
                        sourceClusterVersion, targetClusterVersion));
    }

    @SneakyThrows
    private void runTestProcessWithCheckpoint(int expectedInitialExitCode, int expectedInitialExitCodeCount,
                                              int expectedEventualExitCode, int expectedEventualExitCodeCount,
                                              int shards, int indexDocCount,
                                              boolean forceMoreSegments,
                                              SearchClusterContainer.ContainerVersion sourceClusterVersion,
                                              SearchClusterContainer.ContainerVersion targetClusterVersion,
                                              Function<RunData, Integer> processRunner) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var tempDirSnapshot = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
        var tempDirLucene = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");

        try (
            var esSourceContainer = new SearchClusterContainer(sourceClusterVersion)
                    .withAccessToHost(true);
            var network = Network.newNetwork();
            var osTargetContainer = new SearchClusterContainer(targetClusterVersion)
                    .withAccessToHost(true)
                    .withNetwork(network)
                    .withNetworkAliases(TARGET_DOCKER_HOSTNAME);
            var proxyContainer = new ToxiProxyWrapper(network)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(esSourceContainer::start),
                CompletableFuture.runAsync(osTargetContainer::start)
            ).join();

            proxyContainer.start("target", 9200);

            // Populate the source cluster with data
            var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(esSourceContainer.getUrl())
                    .build()
                    .toConnectionContext());
            var client = clientFactory.determineVersionAndCreate();
            var generator = new WorkloadGenerator(client);
            var workloadOptions = new WorkloadOptions();


            var sourceClusterOperations = new ClusterOperations(esSourceContainer);

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
                    shards
            );
            sourceClusterOperations.createIndex("geonames", body);

            workloadOptions.setTotalDocs(indexDocCount);
            workloadOptions.setWorkloads(List.of(Workloads.GEONAMES));
            workloadOptions.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, shards);
            // Segments will be created on each refresh which tests segment ordering logic
            workloadOptions.setRefreshAfterEachWrite(forceMoreSegments);
            workloadOptions.setMaxBulkBatchSize(forceMoreSegments ? 10 : 1000);
            if (VersionMatchers.isES_5_X.or(VersionMatchers.isES_6_X).test(sourceClusterVersion.getVersion())) {
                workloadOptions.setDefaultDocType("myType");
            }
            generator.generate(workloadOptions);

            // Create the snapshot from the source cluster
            var args = new CreateSnapshot.Args();
            args.snapshotName = SNAPSHOT_NAME;
            args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            args.sourceArgs.host = esSourceContainer.getUrl();

            var snapshotCreator = new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext());
            snapshotCreator.run();

            esSourceContainer.copySnapshotData(tempDirSnapshot.toString());

            int exitCode;
            int initialExitCodeCount = 0;
            int finalExitCodeCount = 0;
            int runs = 0;
            do {
                exitCode = processRunner.apply(new RunData(tempDirSnapshot, tempDirLucene, proxyContainer));
                runs++;
                initialExitCodeCount += exitCode == expectedInitialExitCode ? 1 : 0;
                finalExitCodeCount += exitCode == expectedEventualExitCode ? 1 : 0;
                log.atInfo().setMessage("Process exited with code: {}").addArgument(exitCode).log();
                // Clean tree for subsequent run
                FileSystemUtils.deleteDirectories(tempDirLucene.toString());
            } while (finalExitCodeCount < expectedEventualExitCodeCount && runs < expectedInitialExitCodeCount + expectedEventualExitCodeCount);

            // Assert doc count on the target cluster matches source
            checkClusterMigrationOnFinished(esSourceContainer, osTargetContainer,
                    DocumentMigrationTestContext.factory().noOtelTracking());

            // Check if the final exit code is as expected
            Assertions.assertEquals(
                    expectedEventualExitCodeCount,
                    finalExitCodeCount,
                    "The program did not exit with the expected final exit code."
            );

            Assertions.assertEquals(
                    expectedEventualExitCode,
                    exitCode,
                    "The program did not exit with the expected final exit code."
            );

            Assertions.assertEquals(
                    expectedInitialExitCodeCount,
                    initialExitCodeCount,
                    "The program did not exit with the expected number of " + expectedInitialExitCode +" exit codes"
            );
        } finally {
            FileSystemUtils.deleteDirectories(tempDirSnapshot.toString());
        }
    }

    @SneakyThrows
    private static int runProcessAgainstToxicTarget(
        Path tempDirSnapshot,
        Path tempDirLucene,
        ToxiProxyWrapper proxyContainer,
        SearchClusterContainer.ContainerVersion sourceClusterVersion,
        SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        String targetAddress = proxyContainer.getProxyUriAsString();
        var tp = proxyContainer.getProxy();
        var latency = tp.toxics().latency("latency-toxic", ToxicDirection.UPSTREAM, 500);

        // Set to less than 2x lease time to ensure leases aren't doubling
        int timeoutSeconds = 35;

        String[] additionalArgs = {
            "--documents-per-bulk-request", "10",
            "--max-connections", "2",
            "--initial-lease-duration", "PT20s",
            "--source-version", sourceClusterVersion.getVersion().toString()
        };

        ProcessBuilder processBuilder = setupProcess(
            tempDirSnapshot,
            tempDirLucene,
            targetAddress,
            additionalArgs
        );

        var process = runAndMonitorProcess(processBuilder);
        int exitCode = waitForProcessExit(process, timeoutSeconds);

        latency.remove();

        return exitCode;
    }

    /**
     * Verifies that RFS persists checkpoint metadata ({@code successor_items}) on the coordinator
     * before the lease expires, and that the checkpoint value reflects the expected doc position.
     *
     * Setup: 50 docs, 1 doc/sec, PT60s lease. Coordinator disabled at t=40s, re-enabled at t=48s.
     * Expected checkpoint at t=45s (75% of PT60s lease) pointing to doc 45 ({@code geonames__0__45}).
     * Coordinator recovers within lease window, worker completes successfully (exit 0).
     * Assertions run after worker exits.
     */
    @Disabled("MIGRATIONS-2864: expected to pass after pre-expiry checkpointing is implemented")
    @Test
    @SneakyThrows
    public void testEarlyCheckpointPersistedBeforeLeaseExpiry() {
        final int TOTAL_DOCS = 50;
        final int SHARDS = 1;
        final int COORDINATOR_DISABLE_AFTER_SECONDS = 40;
        final int COORDINATOR_REENABLE_AFTER_SECONDS = 8; // re-enabled at t=48s, within lease window
        // At 75% of PT60s = t=45s, progressCursor should be at doc 45
        // Successor work item ID: geonames__0__45
        final String EXPECTED_CHECKPOINT_WORK_ITEM = "geonames__0__45";

        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var tempDirSnapshot = Files.createTempDirectory("earlyCheckpoint_snapshot");
        var tempDirLucene = Files.createTempDirectory("earlyCheckpoint_lucene");

        try (
            var network = Network.newNetwork();
            var esSourceContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)
                .withAccessToHost(true);
            var osTargetContainer = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
                .withAccessToHost(true).withNetwork(network).withNetworkAliases(TARGET_DOCKER_HOSTNAME);
            var osCoordinatorContainer = new SearchClusterContainer(SearchClusterContainer.OS_V3_0_0)
                .withAccessToHost(true).withNetwork(network).withNetworkAliases(COORDINATOR_DOCKER_HOSTNAME);
            var targetProxy = new ToxiProxyWrapper(network);
            var coordinatorProxy = new ToxiProxyWrapper(network)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(esSourceContainer::start),
                CompletableFuture.runAsync(osTargetContainer::start),
                CompletableFuture.runAsync(osCoordinatorContainer::start)
            ).join();

            targetProxy.start(TARGET_DOCKER_HOSTNAME, 9200);
            coordinatorProxy.start(COORDINATOR_DOCKER_HOSTNAME, 9200);

            // Populate source cluster
            var sourceClusterOperations = new ClusterOperations(esSourceContainer);
            sourceClusterOperations.createIndex("geonames",
                "{\"settings\":{\"index\":{\"number_of_shards\":" + SHARDS + ",\"number_of_replicas\":0}}}");
            for (int i = 1; i <= TOTAL_DOCS; i++) {
                sourceClusterOperations.createDocument("geonames", String.valueOf(i),
                    "{\"name\":\"doc-" + i + "\",\"score\":" + i + "}");
            }
            sourceClusterOperations.refresh();

            // Create snapshot
            createSnapshot(esSourceContainer, SNAPSHOT_NAME, testSnapshotContext);
            esSourceContainer.copySnapshotData(tempDirSnapshot.toString());

            var targetOps = new ClusterOperations(osTargetContainer);
            var coordinatorOps = new ClusterOperations(osCoordinatorContainer);
            var coordinatorIndexName = OpenSearchWorkCoordinator.getFinalIndexName(DEFAULT_COORDINATOR_INDEX_SUFFIX);

            // Two scheduled events: disable at t=40s, re-enable at t=48s
            var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "coordinator-outage-scheduler");
                t.setDaemon(true);
                return t;
            });
            scheduler.schedule(() -> {
                coordinatorProxy.disable();
                log.atInfo().setMessage("Coordinator disabled at ~{}s")
                    .addArgument(COORDINATOR_DISABLE_AFTER_SECONDS).log();
            }, COORDINATOR_DISABLE_AFTER_SECONDS, TimeUnit.SECONDS);
            scheduler.schedule(() -> {
                coordinatorProxy.enable();
                log.atInfo().setMessage("Coordinator re-enabled at t=~{}s")
                    .addArgument(COORDINATOR_DISABLE_AFTER_SECONDS + COORDINATOR_REENABLE_AFTER_SECONDS).log();
            }, COORDINATOR_DISABLE_AFTER_SECONDS + COORDINATOR_REENABLE_AFTER_SECONDS, TimeUnit.SECONDS);

            try {
                int exitCode = runSingleWorkerWithDedicatedCoordinator(
                    tempDirSnapshot, tempDirLucene, targetProxy, coordinatorProxy);
                long finalDocCount = targetOps.getDocCount("geonames");
                log.atInfo().setMessage("Run: exit code {}, target doc count: {}")
                    .addArgument(exitCode).addArgument(finalDocCount).log();

                // ASSERT: worker exits 0 ; coordinator recovered within lease window, work completed
                Assertions.assertEquals(0, exitCode,
                    "Worker should complete successfully (exit 0) ; coordinator recovered before lease expired");

                // ASSERT: all docs migrated to target
                Assertions.assertEquals(TOTAL_DOCS, finalDocCount,
                    "All " + TOTAL_DOCS + " docs should be on target after successful completion");

                // ASSERT: coordinator has a work item with successor_items (checkpoint was persisted)
                coordinatorOps.refresh(coordinatorIndexName);
                var successorQuery = "{\"query\":{\"exists\":{\"field\":\""
                    + OpenSearchWorkCoordinator.SUCCESSOR_ITEMS_FIELD_NAME + "\"}}}";
                var searchResponse = coordinatorOps.post("/" + coordinatorIndexName + "/_search", successorQuery);
                Assertions.assertEquals(200, searchResponse.getKey(), "Failed to query coordinator index");
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var hits = mapper.readTree(searchResponse.getValue()).path("hits").path("hits");
                Assertions.assertTrue(hits.size() > 0,
                    "Expected at least one work item with successor_items on coordinator");

                // ASSERT: the successor work item points to doc 45 (checkpoint at 75% of PT60s lease = t=45s)
                // Today this fails ; checkpoint happens at lease expiry, not at 75%
                var successorItems = hits.get(0).path("_source")
                    .path(OpenSearchWorkCoordinator.SUCCESSOR_ITEMS_FIELD_NAME).asText();
                Assertions.assertEquals(EXPECTED_CHECKPOINT_WORK_ITEM, successorItems,
                    "Successor work item should be " + EXPECTED_CHECKPOINT_WORK_ITEM
                    + " (checkpoint at doc 45 = 75% of PT60s lease), got: " + successorItems);
            } finally {
                scheduler.shutdownNow();
            }
        } finally {
            FileSystemUtils.deleteDirectories(tempDirSnapshot.toString(), tempDirLucene.toString());
        }
    }

    @SneakyThrows
    private static int runSingleWorkerWithDedicatedCoordinator(
        Path tempDirSnapshot,
        Path tempDirLucene,
        ToxiProxyWrapper targetProxy,
        ToxiProxyWrapper coordinatorProxy
    ) {
        String targetAddress = targetProxy.getProxyUriAsString();
        String coordinatorAddress = coordinatorProxy.getProxyUriAsString();

        var tp = targetProxy.getProxy();
        var latency = tp.toxics().latency("target-latency", ToxicDirection.UPSTREAM, 1_000);

        // 1 doc/bulk, 1 connection, 1000ms latency = deterministic 1 doc/sec
        int timeoutSeconds = 180;

        String[] additionalArgs = {
            "--documents-per-bulk-request", "1",
            "--max-connections", "1",
            "--initial-lease-duration", "PT60s",
            "--source-version", SearchClusterContainer.ES_V7_10_2.getVersion().toString(),
            "--coordinator-host", coordinatorAddress
        };

        ProcessBuilder processBuilder = setupProcess(
            tempDirSnapshot, tempDirLucene, targetAddress, additionalArgs);

        var process = runAndMonitorProcess(processBuilder);
        int exitCode = waitForProcessExit(process, timeoutSeconds);
        latency.remove();
        return exitCode;
    }
}
