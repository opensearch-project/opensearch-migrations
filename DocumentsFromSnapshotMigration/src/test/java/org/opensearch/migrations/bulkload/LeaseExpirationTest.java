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
import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
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
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            log.atError().setMessage("Process timed out, attempting to kill it...").log();
            process.destroy(); // Try to be nice about things first...
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                log.atError().setMessage("Process still running, attempting to force kill it...").log();
                process.destroyForcibly();
            }
            Assertions.fail("The process did not finish within the timeout period (" + timeoutSeconds + " seconds).");
        }

        latency.remove();

        return process.exitValue();
    }

    /**
     * Captures current lease-timeout behavior when coordinator goes down before lease expiry
     * and later recovers. Documents the current contract: cleanup/checkpoint fails while
     * coordinator is unavailable, and a later worker reclaims and completes the work.
     *
     * <p>Architecture:
     * <pre>
     *   Source (ES 7.10) ──snapshot──► RFS Process
     *                                    ├──► Target (OS 2.19) via ToxiProxy (1000ms latency)
     *                                    └──► Coordinator (OS 3.0) via ToxiProxy (disable/enable)
     * </pre>
     *
     * <p>With 1 doc/bulk, 1 connection, and 1000ms upstream latency on the target proxy,
     * each bulk request takes ~1s round-trip, so 60 docs take ~60s to migrate.
     *
     * <p>Timeline:
     * <pre>
     *   t=0s    : Worker 1 starts, acquires lease (expires ~t=30s), begins migrating at 1 doc/sec
     *   t=25s   : Coordinator disabled (before lease expiry)
     *   t≈30s   : Lease expires, cleanup/checkpoint path runs, coordinator call fails
     *   t≈30-40s: Retry behavior in successor-work-item path consumes ~10s
     *   t≈40s   : Worker 1 exits with PROCESS_TIMED_OUT (2)
     *   t≈40-45s: Retry workers may fail while coordinator is still down (exit 1 or 2)
     *   t=45s   : Coordinator re-enabled
     *             Next worker reclaims expired work item and completes migration
     *             Final worker exits NO_WORK_LEFT (3)
     * </pre>
     *
     * <p>Scale=1: only one worker at a time. Sequential process invocations simulate pod restart.
     */
    @Test
    @SneakyThrows
    public void testLeaseReclamationAfterCoordinatorOutage() {
        final int TOTAL_DOCS = 60;
        final int SHARDS = 1;
        final int COORDINATOR_DISABLE_AFTER_SECONDS = 25;
        final int COORDINATOR_OUTAGE_DURATION_SECONDS = 20; // re-enabled at t=45s
        final int MAX_RUNS = 10; // safety cap to prevent infinite loop on regression

        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var tempDirSnapshot = Files.createTempDirectory("leaseReclamation_snapshot");
        var tempDirLucene = Files.createTempDirectory("leaseReclamation_lucene");

        try (
            var network = Network.newNetwork();
            var esSourceContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)
                .withAccessToHost(true);
            var osTargetContainer = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
                .withAccessToHost(true).withNetwork(network).withNetworkAliases(TARGET_DOCKER_HOSTNAME);
            var osCoordinatorContainer = new SearchClusterContainer(SearchClusterContainer.OS_V3_0_0)
                .withAccessToHost(true).withNetwork(network).withNetworkAliases("coordinator");
            var targetProxy = new ToxiProxyWrapper(network);
            var coordinatorProxy = new ToxiProxyWrapper(network)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(esSourceContainer::start),
                CompletableFuture.runAsync(osTargetContainer::start),
                CompletableFuture.runAsync(osCoordinatorContainer::start)
            ).join();

            targetProxy.start(TARGET_DOCKER_HOSTNAME, 9200);
            coordinatorProxy.start("coordinator", 9200);

            // Populate source cluster
            var sourceClusterOperations = new ClusterOperations(esSourceContainer);
            sourceClusterOperations.createIndex("geonames",
                "{\"settings\":{\"index\":{\"number_of_shards\":" + SHARDS + ",\"number_of_replicas\":0}}}");
            for (int i = 1; i <= TOTAL_DOCS; i++) {
                sourceClusterOperations.createDocument("geonames", String.valueOf(i),
                    "{\"name\":\"doc-" + i + "\",\"score\":" + i + "}");
            }
            sourceClusterOperations.post("/_refresh", null);

            // Create snapshot
            createSnapshot(esSourceContainer, SNAPSHOT_NAME, testSnapshotContext);
            esSourceContainer.copySnapshotData(tempDirSnapshot.toString());

            // Schedule coordinator outage: disable at t=25s, re-enable at t=45s
            var disabledAtNanos = new java.util.concurrent.atomic.AtomicLong(0);
            var reEnabledAtNanos = new java.util.concurrent.atomic.AtomicLong(0);
            var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "coordinator-outage-scheduler");
                t.setDaemon(true);
                return t;
            });
            scheduler.schedule(() -> {
                coordinatorProxy.disable();
                disabledAtNanos.set(System.nanoTime());
                log.atInfo().setMessage("Coordinator disabled at ~{}s")
                    .addArgument(COORDINATOR_DISABLE_AFTER_SECONDS).log();
                scheduler.schedule(() -> {
                    coordinatorProxy.enable();
                    reEnabledAtNanos.set(System.nanoTime());
                    log.atInfo().setMessage("Coordinator re-enabled after {}s outage window")
                        .addArgument(COORDINATOR_OUTAGE_DURATION_SECONDS).log();
                }, COORDINATOR_OUTAGE_DURATION_SECONDS, TimeUnit.SECONDS);
            }, COORDINATOR_DISABLE_AFTER_SECONDS, TimeUnit.SECONDS);

            var runs = new java.util.ArrayList<RunObservation>();
            try {
                int exitCode;
                var targetClient = new RestClient(ConnectionContextTestParams.builder()
                    .host(osTargetContainer.getUrl()).build().toConnectionContext());
                do {
                    var runStart = System.nanoTime();
                    exitCode = runProcessWithCoordinator(
                        tempDirSnapshot, tempDirLucene, targetProxy, coordinatorProxy);
                    var runEnd = System.nanoTime();
                    targetClient.get("geonames/_refresh", null);
                    var countResp = targetClient.get("geonames/_count", null);
                    long docCount = countResp.statusCode == 200
                        ? new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(countResp.body).path("count").asLong()
                        : -1;
                    runs.add(new RunObservation(runStart, runEnd, exitCode, docCount));
                    log.atInfo().setMessage("Run {}: exit code {}, target doc count: {}")
                        .addArgument(runs.size()).addArgument(exitCode)
                        .addArgument(docCount).log();
                    FileSystemUtils.deleteDirectories(tempDirLucene.toString());
                    Files.createDirectories(tempDirLucene);
                } while (exitCode != RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE && runs.size() < MAX_RUNS);

                log.atInfo().setMessage("Exit code history: {}")
                    .addArgument(() -> runs.stream().map(RunObservation::exitCode).toList()).log();

                // ASSERT: outage was injected and recovered
                Assertions.assertTrue(disabledAtNanos.get() > 0, "Coordinator outage was not injected");
                Assertions.assertTrue(reEnabledAtNanos.get() > 0, "Coordinator was not re-enabled");

                // ASSERT: Worker 1 hit lease expiry during coordinator outage
                Assertions.assertEquals(RfsMigrateDocuments.PROCESS_TIMED_OUT_EXIT_CODE, runs.get(0).exitCode(),
                    "Worker 1 should exit with PROCESS_TIMED_OUT (lease expired during coordinator outage)");

                // ASSERT: Worker 1 was running when coordinator went down (outage overlapped active migration)
                Assertions.assertTrue(runs.get(0).startNanos() < disabledAtNanos.get(),
                    "Worker 1 should have started before coordinator was disabled");
                Assertions.assertTrue(disabledAtNanos.get() < runs.get(0).endNanos(),
                    "Coordinator should have been disabled before Worker 1 finished");

                // ASSERT: Worker 1 had actually started migrating docs before timeout
                // (supports that lease-timeout cleanup ran with migration progress already made)
                Assertions.assertTrue(runs.get(0).targetDocCount() > 0 && runs.get(0).targetDocCount() < TOTAL_DOCS,
                    "Worker 1 should have migrated some (but not all) docs before lease expired, got " + runs.get(0).targetDocCount());

                // ASSERT: a worker successfully completed work (exit 0) before terminal state
                var successRun = runs.stream().filter(r -> r.exitCode() == 0).findFirst();
                Assertions.assertTrue(successRun.isPresent(),
                    "At least one worker should have completed work successfully (exit 0) before NO_WORK_LEFT");

                // ASSERT: successful reclaim completed after coordinator recovered
                Assertions.assertTrue(successRun.get().endNanos() > reEnabledAtNanos.get(),
                    "Successful worker should have finished after coordinator was re-enabled");

                // ASSERT: terminal state reached
                Assertions.assertEquals(RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE,
                    runs.get(runs.size() - 1).exitCode(),
                    "Final worker should exit with NO_WORK_LEFT (3), got history: "
                        + runs.stream().map(RunObservation::exitCode).toList());

                // ASSERT: all docs migrated to target (query target directly, bypassing proxy)
                Assertions.assertEquals(200, targetClient.get("geonames/_refresh", null).statusCode);
                var countResponse = targetClient.get("geonames/_count", null);
                Assertions.assertEquals(200, countResponse.statusCode);
                var actualDocs = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(countResponse.body).path("count").asLong();
                Assertions.assertEquals(TOTAL_DOCS, actualDocs,
                    "All " + TOTAL_DOCS + " docs should be on target after lease reclamation");

                // ASSERT: coordinator has no incomplete work items (all work reclaimed and completed)
                var coordinatorClient = new RestClient(ConnectionContextTestParams.builder()
                    .host(osCoordinatorContainer.getUrl()).build().toConnectionContext());
                var coordinatorIndexName = OpenSearchWorkCoordinator.getFinalIndexName(DEFAULT_COORDINATOR_INDEX_SUFFIX);
                Assertions.assertEquals(200,
                    coordinatorClient.get(coordinatorIndexName + "/_refresh", null).statusCode,
                    "Failed to refresh coordinator index");
                var incompleteQuery = "{\"query\":{\"bool\":{\"must_not\":{\"exists\":{\"field\":\"completedAt\"}}}}}";
                var incompleteResponse = coordinatorClient.post(coordinatorIndexName + "/_count", incompleteQuery, null);
                Assertions.assertEquals(200, incompleteResponse.statusCode, "Failed to query coordinator index");
                var incompleteCount = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(incompleteResponse.body).path("count").asLong();
                Assertions.assertEquals(0, incompleteCount,
                    "All coordinator work items should be completed after recovery, found " + incompleteCount + " incomplete");
            } finally {
                scheduler.shutdownNow();
            }
        } finally {
            FileSystemUtils.deleteDirectories(tempDirSnapshot.toString(), tempDirLucene.toString());
        }
    }

    @SneakyThrows
    private static int runProcessWithCoordinator(
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
            "--initial-lease-duration", "PT30s",
            "--source-version", SearchClusterContainer.ES_V7_10_2.getVersion().toString(),
            "--coordinator-host", coordinatorAddress
        };

        ProcessBuilder processBuilder = setupProcess(
            tempDirSnapshot, tempDirLucene, targetAddress, additionalArgs);

        var process = runAndMonitorProcess(processBuilder);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            log.atError().setMessage("Process timed out, attempting to kill it...").log();
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                log.atError().setMessage("Process still running, force killing...").log();
                process.destroyForcibly();
            }
            Assertions.fail("Process did not finish within " + timeoutSeconds + " seconds.");
        }

        latency.remove();
        return process.exitValue();
    }

    private record RunObservation(long startNanos, long endNanos, int exitCode, long targetDocCount) {}

}
