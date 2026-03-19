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
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

@Tag("isolatedTest")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@Slf4j
public class LeaseExpirationTest extends SourceTestBase {

    public static final String COORDINATOR_DOCKER_HOSTNAME = "coordinator";
    public static final String TARGET_DOCKER_HOSTNAME = "target";
    private static final String DEFAULT_COORDINATOR_INDEX_SUFFIX = "";

    private static Stream<Arguments> testParameters() {
        // Lease expiration tests target-side work coordination behavior.
        // Source version is fixed (ES 7.10 is the most common migration path).
        // Parameterize on target versions for O(N) coverage.
        var source = SearchClusterContainer.ES_V7_10_2;
        return Stream.concat(
                SupportedClusters.targets().stream()
                        .map(target -> Arguments.of(false, source, target)),
                // Add test with forceMoreSegments=true to exercise segment ordering logic
                Stream.of(Arguments.of(true, source, SearchClusterContainer.OS_V2_19_4))
        );
    }

    @ParameterizedTest(name = "forceMoreSegments={0}, sourceClusterVersion={1}, targetClusterVersion={2}")
    @MethodSource("testParameters")
    public void testProcessExitsAsExpected(boolean forceMoreSegments,
                                           SearchClusterContainer.ContainerVersion sourceClusterVersion,
                                           SearchClusterContainer.ContainerVersion targetClusterVersion) {
        // With 10 docs/bulk, 2 connections, 500ms latency, results in ~40 docs/sec throughput.
        // Lease is PT27s with early trigger at 75% = ~20s effective work time.
        // ~20s × 40 docs/sec = ~800 docs/lease. 1640 docs/shard needs ~3 leases (2 handoffs + 1 completion).
        // This is ensured with the toxiproxy settings, the migration should not be able to be completed
        // faster, but with a heavily loaded test environment, may be slower which is why this is marked as
        // isolated.
        // 2 Shards, for each shard, expect two status code 2 and one status code 0 (3 leases)
        int shards = 2;
        int indexDocCount = 1640 * shards;
        int continueExitCode = 2;
        int finalExitCodePerShard = 0;
        // Allow up to 20 runs total (pipeline throughput varies with CI load)
        int maxRuns = 20;
        runTestProcessWithCheckpoint(continueExitCode,
                finalExitCodePerShard, shards, shards, indexDocCount, forceMoreSegments,
                maxRuns,
                sourceClusterVersion,
                targetClusterVersion,
                d -> runProcessAgainstToxicTarget(d.tempDirSnapshot, d.tempDirLucene, d.proxyContainer,
                        sourceClusterVersion, targetClusterVersion));
    }

    @SneakyThrows
    private void runTestProcessWithCheckpoint(int expectedInitialExitCode,
                                              int expectedEventualExitCode, int expectedEventualExitCodeCount,
                                              int shards, int indexDocCount,
                                              boolean forceMoreSegments,
                                              int maxRuns,
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
            } while (finalExitCodeCount < expectedEventualExitCodeCount && runs < maxRuns);

            // All shards must complete (exit code 0)
            Assertions.assertEquals(
                    expectedEventualExitCodeCount,
                    finalExitCodeCount,
                    "Expected " + expectedEventualExitCodeCount + " completions (exit code " + expectedEventualExitCode + ") but got " + finalExitCodeCount
            );

            // Last exit code must be completion
            Assertions.assertEquals(
                    expectedEventualExitCode,
                    exitCode,
                    "The program did not exit with the expected final exit code."
            );

            // At least one lease expiration must have occurred (proves checkpoint/resume works)
            Assertions.assertTrue(
                    initialExitCodeCount >= 1,
                    "Expected at least 1 lease expiration (exit code " + expectedInitialExitCode + ") but got " + initialExitCodeCount
            );

            // Assert doc count on the target cluster matches source
            checkClusterMigrationOnFinished(esSourceContainer, osTargetContainer,
                    DocumentMigrationTestContext.factory().noOtelTracking());
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

        // With 10 docs/bulk, 2 connections, 500ms latency, effective throughput is ~40 docs/sec.
        // The anticipated effective work time per lease is the time the worker actually processes
        // documents before the early checkpoint trigger fires.
        int anticipatedEffectiveWorkSeconds = 20;
        double earlyTriggerFraction = 0.75;
        // Lease duration is set so that the 75% early trigger mark equals the anticipated work time:
        //   anticipatedEffectiveWorkSeconds / earlyTriggerFraction = 20 / 0.75 ≈ 26.67 → round to 27s
        // Early trigger fires at: 27 * 0.75 = 20.25s ≈ anticipated effective work time
        long leaseDurationSeconds = Math.round(anticipatedEffectiveWorkSeconds / earlyTriggerFraction);

        // Less than 2x lease duration to ensure leases aren't doubling
        int timeoutSeconds = (int) (leaseDurationSeconds * 2 - 1);

        String[] additionalArgs = {
            "--documents-per-bulk-request", "10",
            "--max-connections", "2",
            "--initial-lease-duration", "PT" + leaseDurationSeconds + "s",
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
     * Verifies early lease-timeout handoff during a transient coordinator outage.
     *
     * Setup: 80 docs at ~1 doc/sec with PT60s lease. Coordinator is disabled at t=40s and
     * re-enabled at t=48s. Expected behavior is early trigger at t=45s, worker exit with
     * PROCESS_TIMED_OUT (2), and persisted successor checkpoint in the early-trigger range
     * (distinct from lease-expiry behavior).
     */
    @Test
    @SneakyThrows
    public void testEarlyCheckpointPersistedBeforeLeaseExpiry() {
        final int TOTAL_DOCS = 80;
        final int SHARDS = 1;
        final int COORDINATOR_DISABLE_AFTER_SECONDS = 40;
        final int COORDINATOR_REENABLE_AFTER_SECONDS = 8; // re-enabled at t=48s, within lease window

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

                // ASSERT: worker exits the lease-timeout handoff path
                Assertions.assertEquals(RfsMigrateDocuments.PROCESS_TIMED_OUT_EXIT_CODE, exitCode,
                    "Worker should exit with PROCESS_TIMED_OUT (2); lease expired before all docs migrated");

                // ASSERT: target doc count falls in early-trigger band, not lease-expiry band
                Assertions.assertTrue(finalDocCount >= 42 && finalDocCount <= 46,
                    "Target should have 42-46 docs (early checkpoint at ~t=45s); got " + finalDocCount);

                // ASSERT: parent work item contains successor handoff metadata
                var parentWorkItemId = new IWorkCoordinator.WorkItemAndDuration
                    .WorkItem("geonames", 0, 0L).toString();
                coordinatorOps.refresh(coordinatorIndexName);
                var parentQuery = "{\"query\":{\"ids\":{\"values\":[\"" + parentWorkItemId + "\"]}}}";
                var searchResponse = coordinatorOps.post("/" + coordinatorIndexName + "/_search", parentQuery);
                Assertions.assertEquals(200, searchResponse.getKey(), "Failed to query coordinator index");
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var parentHits = mapper.readTree(searchResponse.getValue()).path("hits").path("hits");
                Assertions.assertEquals(1, parentHits.size(),
                    "Expected exactly one hit for parent work item " + parentWorkItemId);
                var successorItems = parentHits.get(0).path("_source")
                    .path(OpenSearchWorkCoordinator.SUCCESSOR_ITEMS_FIELD_NAME).asText();

                // ASSERT: successor checkpoint doc is in early-trigger range
                Assertions.assertTrue(successorItems.startsWith("geonames__0__"),
                    "Successor should be for shard 0, got: " + successorItems);
                var checkpointDoc = Integer.parseInt(successorItems.substring("geonames__0__".length()));
                Assertions.assertTrue(checkpointDoc >= 40 && checkpointDoc <= 44,
                    "Checkpoint doc should be 40-44 (early trigger at ~t=45s); got " + checkpointDoc);
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
