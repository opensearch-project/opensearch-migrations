package org.opensearch.migrations.bulkload;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

/**
 * Covers two coordinator-outage scenarios after data migration starts:
 * - Coordinator becomes unavailable and stays down (current expected behavior: worker exits non-zero, docs preserved)
 * - Coordinator goes down, then comes back later (currently disabled, expects worker to exit with 0)
 *
 * Test timing:
 * - Start one worker at t=0s
 * - Disable coordinator at t=30s, after lease/work is already in progress
 * - Worker continues sending docs to target, but coordinator completion updates fail
 * - Worker expected to migrate 60 docs in 60 secs
 *
 * Choosing t < 60s (t=30s) : 
 * - We want to hit finalization/checkpoint calls, not startup/lease acquisition.
 * - With 60 docs, 1 doc/bulk, 1 connection, and 1000ms target upstream latency: 
 * TARGET is expected to see the last doc being indexed at the 60sec mark
 */
@Tag("isolatedTest")
@Slf4j
public class RfsOpenSearchCoordinatorOutageTest extends SourceTestBase {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int SHARDS = 1;
    private static final int TOTAL_DOCS = 60;
    private static final int DOCUMENTS_PER_BULK_REQUEST = 1;
    private static final int MAX_CONNECTIONS = 1;
    private static final String SESSION_NAME = "rfs-coordinator-outage";

    private static final int TARGET_PROXY_UPSTREAM_LATENCY_MILLIS = 1_000;

    // Timeline controls for this single-run test:
    // - Inject outage at +30s to hit "before worker completion" window
    // - RUN_TIMEOUT_SECONDS bounds one RFS process execution
    // - Additional +60s protects against CI scheduling variance
    // Total deadline = disableAt + runTimeout + buffer = 30 + 240 + 60 = 330s
    private static final int RUN_TIMEOUT_SECONDS = 240;
    private static final int READER_THREAD_JOIN_MILLIS = 5_000;
    private static final int DESTROY_GRACE_SECONDS = 10;

    private static final int COORDINATOR_DISABLE_AFTER_SECONDS = 30;
    private static final int COORDINATOR_REENABLE_AFTER_SECONDS = 120;
    private static final int TOTAL_TIMEOUT_SECONDS =
        COORDINATOR_DISABLE_AFTER_SECONDS + RUN_TIMEOUT_SECONDS + 60;
    private static final int COORDINATOR_STATE_OBSERVATION_TIMEOUT_SECONDS = 5;
    private static final int COORDINATOR_STATE_POLL_MILLIS = 200;

    @TempDir
    Path tempRootDir;

    @Test
    @SneakyThrows
    void allDocsMigratedButCoordinatorUnavailableAtCompletion() {
        var result = runCoordinatorOutageScenario(false, 0);

        // (1/3) ASSERT that TARGET cluster has ALL docs from SOURCE cluster
        Assertions.assertEquals(result.expectedDocs(), result.finalDocs(), "All docs should be on target");

        // ASSERT from COORDINATOR that at least one work item should remain incomplete
        Assertions.assertTrue(result.coordinatorHasIncompleteWorkItems(),
            "Expected coordinator working state to contain incomplete work items after outage");

        // (2/3) ASSERT : Coordinator outage thread executed and disabled connectivity as scheduled
        Assertions.assertTrue(result.outageInjected(), "Coordinator outage was not injected at scheduled time");

        // (3/3) ASSERT : RFS process failed because coordinator became unavailable during finalize path
        Assertions.assertNotEquals(0, result.exitCode(),
            "Expected non-zero exit when coordinator becomes unavailable");

        // ASSERT : RFS output contains retry exhaustion path
        // Note : Currently, there is no specific status code for RFS failures due to Coordinator unavailability
        Assertions.assertTrue(result.output().contains("RetriesExceededException")
                || result.output().contains("Unexpected error running RfsWorker"),
            "Expected failure signature from coordinator unavailability");
    }

    @Test
    @Disabled("Known limitation: current coordinator retry window is shorter than long restart duration")
    @SneakyThrows
    void allDocsMigratedButCoordinatorLongRestartsAtCompletion() {
        var result = runCoordinatorOutageScenario(true, COORDINATOR_REENABLE_AFTER_SECONDS);

        // (1/5) ASSERT : Coordinator outage thread executed and disabled connectivity as scheduled
        Assertions.assertTrue(result.outageInjected(), "Coordinator outage was not injected at scheduled time");
        
        // (2/5) ASSERT that COORDINATOR was RE-ENABLED as scheduled
        Assertions.assertTrue(result.coordinatorReEnabled(),
            "Coordinator was not re-enabled after long restart window");
        
        // (3/5) ASSERT that TARGET cluster has ALL docs from SOURCE cluster
        Assertions.assertEquals(result.expectedDocs(), result.finalDocs(), "All docs should be on target");

        // (4/5) ASSERT that RFS retries M times and waits for COORDINATOR
        Assertions.assertEquals(0, result.exitCode(),
            "Expected RFS to recover and exit cleanly after coordinator returns");
        
        // (5/5) ASSERT that every entry in COORDINATOR State has a `completedAt` field
        Assertions.assertFalse(result.coordinatorHasIncompleteWorkItems(),
            "Expected coordinator working state to have no incomplete work items after recovery");
    }

    @SneakyThrows
    private ScenarioResult runCoordinatorOutageScenario(boolean reEnableCoordinator, int reEnableAfterSeconds) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var tempDirSnapshot = Files.createDirectory(tempRootDir.resolve("rfsCoordinatorOutage_snapshot"));

        try (
            var network = Network.newNetwork();
            var esSourceContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)
                .withAccessToHost(true).withNetwork(network);
            var osTargetContainer = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
                .withAccessToHost(true).withNetwork(network).withNetworkAliases("target");
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

            // === ACTION: Place a toxi proxy infront of the TARGET cluster  ===
            targetProxy.start("target", 9200);
            targetProxy.getProxy().toxics().latency("target-upstream", ToxicDirection.UPSTREAM,
                TARGET_PROXY_UPSTREAM_LATENCY_MILLIS);

            // ASSERT that TARGET cluster is healthy and reachable
            assertClusterReachability(targetProxy.getProxyUriAsString(), "target", true);

            // === ACTION : Place a toxi proxy infront of the COORDINATOR cluster ===
            coordinatorProxy.start("coordinator", 9200);

            // ASSERT that COORDINATOR cluster is healthy and reachable
            assertClusterReachability(coordinatorProxy.getProxyUriAsString(), "coordinator", true);

            // === ACTION : Ingest data on SOURCE cluster and take a snapshot ===
            var sourceClusterOperations = new ClusterOperations(esSourceContainer);
            setupAndSnapshotSourceCluster(
                sourceClusterOperations, esSourceContainer, SHARDS, "geonames", TOTAL_DOCS, tempDirSnapshot, testSnapshotContext);
            
            // ASSERT source snapshot has expected number of docs
            var expectedDocs = getDocCountFromCluster(esSourceContainer.getUrl(), "geonames", true);
            Assertions.assertEquals(TOTAL_DOCS, expectedDocs, "Expected source doc count to match configured TOTAL_DOCS");

            // Build RFS CLI args for single-worker, single-doc bulk processing ===
            String[] workerArgs = {
                "--source-version", "ES_7_10",
                "--session-name", SESSION_NAME,
                "--coordinator-host", coordinatorProxy.getProxyUriAsString(),
                "--max-connections", String.valueOf(MAX_CONNECTIONS),
                "--documents-per-bulk-request", String.valueOf(DOCUMENTS_PER_BULK_REQUEST),
                "--initial-lease-duration", "PT99M"
            };

            // === ACTION : Start outage scheduler for coordinator disable at fixed elapsed time ===
            var testStartNanos = System.nanoTime();
            var scenarioTimeoutSeconds = TOTAL_TIMEOUT_SECONDS + (reEnableCoordinator ? reEnableAfterSeconds : 0);
            var totalDeadlineNanos = testStartNanos + TimeUnit.SECONDS.toNanos(scenarioTimeoutSeconds);
            var outageInjected = new AtomicBoolean(false);
            var coordinatorReEnabled = new AtomicBoolean(false);

            var outageThread = new Thread(() ->
                manageCoordinatorOutageWindow(
                    coordinatorProxy, testStartNanos, totalDeadlineNanos, outageInjected,
                    coordinatorReEnabled, reEnableCoordinator, reEnableAfterSeconds),
                "rfs-coordinator-outage-scheduler");
            outageThread.start();

            // === ACTION : Run one RFS process while outage scheduler is active ===
            var runResult = runRfsOnce(tempDirSnapshot, targetProxy.getProxyUriAsString(), workerArgs, 1, totalDeadlineNanos);

            // === ACTION : Wait for outage scheduler thread to finish after RFS exits ===
            outageThread.join(TimeUnit.SECONDS.toMillis(reEnableCoordinator ? reEnableAfterSeconds + 10 : 5));

            var finalDocs = getDocCountFromCluster(osTargetContainer.getUrl(), "geonames", false);
            var coordinatorHasIncompleteWorkItems = waitForCoordinatorIncompleteWorkItems(
                osCoordinatorContainer.getUrl(), true);

            // === ACTION : Log one-line run summary for outage timing, exit behavior, and doc totals ===
            log.atInfo().setMessage("Test summary: exitCode={}, outageInjected={}, coordinatorReEnabled={}, docs={}/{}")
                .addArgument(runResult.exitCode())
                .addArgument(outageInjected.get())
                .addArgument(coordinatorReEnabled.get())
                .addArgument(finalDocs)
                .addArgument(expectedDocs)
                .log();
            return new ScenarioResult(
                runResult.exitCode(),
                outageInjected.get(),
                coordinatorReEnabled.get(),
                expectedDocs,
                finalDocs,
                runResult.output(),
                coordinatorHasIncompleteWorkItems);
        } finally {
            // Cleanup temp directories created during this test run
            try (var stream = Files.list(tempRootDir)) {
                var dirsToDelete = stream.filter(Files::isDirectory)
                    .map(Path::toString)
                    .toList();
                if (!dirsToDelete.isEmpty()) {
                    FileSystemUtils.deleteDirectories(dirsToDelete.toArray(String[]::new));
                }
            }
        }
    }

    @SneakyThrows
    private void setupAndSnapshotSourceCluster(ClusterOperations sourceClusterOperations,
                                               SearchClusterContainer sourceCluster,
                                               int shardCount,
                                               String indexName,
                                               int numberOfDocs,
                                               Path snapshotDir,
                                               SnapshotTestContext testSnapshotContext) {
        sourceClusterOperations.createIndex(indexName,
            "{\"settings\":{\"index\":{\"number_of_shards\":" + shardCount + ",\"number_of_replicas\":0}}}");
        for (int i = 1; i <= numberOfDocs; i++) {
            sourceClusterOperations.createDocument(indexName, String.valueOf(i),
                "{\"name\":\"doc-" + i + "\",\"score\":" + i + "}");
        }
        sourceClusterOperations.post("/_refresh", null);

        var snapshotArgs = new CreateSnapshot.Args();
        snapshotArgs.snapshotName = SNAPSHOT_NAME;
        snapshotArgs.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
        snapshotArgs.sourceArgs.host = sourceCluster.getUrl();
        new CreateSnapshot(snapshotArgs, testSnapshotContext.createSnapshotCreateContext()).run();
        sourceCluster.copySnapshotData(snapshotDir.toString());
    }

    @SneakyThrows
    private RunResult runRfsOnce(Path snapshotDir, String targetProxyHost, String[] workerArgs, int runIndex,
                                 long totalDeadlineNanos) {
        // Create per-run Lucene temp directory for unpacked shard files
        var luceneDir = Files.createTempDirectory(tempRootDir, "rfsCoordinatorOutage_lucene_run" + runIndex);
        var output = new StringBuilder();
        try {
            // Starting with one RFS subprocess for this run
            var pb = setupProcess(snapshotDir, luceneDir, targetProxyHost, workerArgs);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            var process = pb.start();

            // Stream subprocess output concurrently for logs and assertions
            var readerThread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.atInfo().setMessage("from run-{} worker [{}]: {}")
                            .addArgument(runIndex)
                            .addArgument(() -> process.toHandle().pid())
                            .addArgument(line)
                            .log();
                        output.append(line).append('\n');
                    }
                } catch (Exception e) {
                    log.atWarn().setCause(e).setMessage("Failed to read run output").log();
                }
            }, "rfs-coordinator-run-" + runIndex + "-reader");
            readerThread.start();

            // Ensure this run still has time left within total test deadline
            var nanosLeft = totalDeadlineNanos - System.nanoTime();
            if (nanosLeft <= 0) {
                Assertions.fail("Exceeded total timeout before run " + runIndex + " could complete");
            }

            // Wait for process completion with bounded per-run timeout
            var runWaitSeconds = Math.max(1, Math.min(RUN_TIMEOUT_SECONDS, TimeUnit.NANOSECONDS.toSeconds(nanosLeft)));
            if (!process.waitFor(runWaitSeconds, TimeUnit.SECONDS)) {
                // Graceful stop first, then force kill if still alive
                process.destroy();
                if (!process.waitFor(DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
                log.atWarn().setMessage("Run {} output before timeout ({} chars):\n{}")
                    .addArgument(runIndex).addArgument(output.length()).addArgument(output).log();
                Assertions.fail("RFS run " + runIndex + " timed out after " + runWaitSeconds + " seconds");
            }

            // Allow output reader thread to drain remaining subprocess lines
            readerThread.join(READER_THREAD_JOIN_MILLIS);
            return new RunResult(process.exitValue(), output.toString());
        } finally {
            // Cleanup per-run Lucene temp directory
            FileSystemUtils.deleteDirectories(luceneDir.toString());
        }
    }

    @SneakyThrows
    private void manageCoordinatorOutageWindow(ToxiProxyWrapper coordinatorProxy, long testStartNanos,
                                               long totalDeadlineNanos, AtomicBoolean outageInjected,
                                               AtomicBoolean coordinatorReEnabled,
                                               boolean reEnableCoordinator, int reEnableAfterSeconds) {
        // Wait until elapsed test time reaches configured outage trigger
        while (System.nanoTime() - testStartNanos < TimeUnit.SECONDS.toNanos(COORDINATOR_DISABLE_AFTER_SECONDS)) {
            if (System.nanoTime() >= totalDeadlineNanos) {
                Assertions.fail("Total timeout reached before scheduled coordinator outage action");
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }

        // Disable coordinator connectivity through the proxy
        coordinatorProxy.disable();

        // Verify coordinator is actually unreachable after disable
        assertClusterReachability(coordinatorProxy.getProxyUriAsString(), "coordinator", false);

        // Record that outage was injected for test assertions
        outageInjected.set(true);
        log.atInfo().setMessage("Coordinator disabled at ~{}s")
            .addArgument(COORDINATOR_DISABLE_AFTER_SECONDS)
            .log();

        if (!reEnableCoordinator) {
            return;
        }

        // Wait additional restart window, then bring coordinator back.
        while (System.nanoTime() - testStartNanos <
            TimeUnit.SECONDS.toNanos(COORDINATOR_DISABLE_AFTER_SECONDS + reEnableAfterSeconds)) {
            if (System.nanoTime() >= totalDeadlineNanos) {
                Assertions.fail("Total timeout reached before scheduled coordinator re-enable action");
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }

        coordinatorProxy.enable();
        assertClusterReachability(coordinatorProxy.getProxyUriAsString(), "coordinator", true);
        coordinatorReEnabled.set(true);
        log.atInfo().setMessage("Coordinator re-enabled after {}s restart window")
            .addArgument(reEnableAfterSeconds)
            .log();
    }

    @SneakyThrows
    private void assertClusterReachability(String host, String clusterLabel, boolean expectedReachable) {
        // Equivalent to "curl <host>/_cluster/health"
        var client = new RestClient(ConnectionContextTestParams.builder().host(host).build().toConnectionContext());
        try {
            var response = client.get("_cluster/health", null);
            if (expectedReachable) {
                Assertions.assertEquals(200, response.statusCode,
                    "Expected " + clusterLabel + " cluster health endpoint to be reachable");
                log.atInfo().setMessage("Verified {} cluster is reachable (status={})")
                    .addArgument(clusterLabel)
                    .addArgument(response.statusCode)
                    .log();
            } else {
                Assertions.assertNotEquals(200, response.statusCode,
                    "Expected " + clusterLabel + " cluster to be unreachable");
                log.atInfo().setMessage("Verified {} cluster is unreachable (status={})")
                    .addArgument(clusterLabel)
                    .addArgument(response.statusCode)
                    .log();
            }
        } catch (Exception e) {
            if (!expectedReachable) {
                log.atInfo().setMessage("Verified {} cluster is unreachable (exception: {})")
                    .addArgument(clusterLabel)
                    .addArgument(e.getClass().getSimpleName())
                    .log();
                return;
            }
            Assertions.fail("Expected " + clusterLabel + " cluster to be reachable: " + e.getMessage(), e);
        }
    }

    private static long getDocCountFromCluster(String host, String index, boolean failIfMissing) {
        var client = new RestClient(ConnectionContextTestParams.builder().host(host).build().toConnectionContext());
        var refresh = client.get(index + "/_refresh", null);
        if (refresh.statusCode == 404 && !failIfMissing) return 0;
        Assertions.assertEquals(200, refresh.statusCode, "Refresh failed for " + index);
        var count = client.get(index + "/_count", null);
        if (count.statusCode == 404 && !failIfMissing) return 0;
        Assertions.assertEquals(200, count.statusCode, "Count failed for " + index);
        try {
            return OBJECT_MAPPER.readTree(count.body).path("count").asLong();
        } catch (Exception e) {
            throw new RuntimeException("Failed parsing count response", e);
        }
    }

    @SneakyThrows
    private static boolean waitForCoordinatorIncompleteWorkItems(String coordinatorHost, boolean expectedIncomplete) {
        var client = new RestClient(ConnectionContextTestParams.builder()
            .host(coordinatorHost).build().toConnectionContext());
        var coordinatorIndexName = OpenSearchWorkCoordinator.getFinalIndexName(SESSION_NAME);
        var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(COORDINATOR_STATE_OBSERVATION_TIMEOUT_SECONDS);

        while (System.nanoTime() < deadlineNanos) {
            var refreshResponse = client.get(coordinatorIndexName + "/_refresh", null);
            if (refreshResponse.statusCode == 200) {
                var searchResponse = client.get(coordinatorIndexName + "/_search", null);
                if (searchResponse.statusCode == 200) {
                    var hasIncomplete = hasIncompleteWorkItems(searchResponse.body);
                    if (hasIncomplete == expectedIncomplete) {
                        return hasIncomplete;
                    }
                }
            }
            TimeUnit.MILLISECONDS.sleep(COORDINATOR_STATE_POLL_MILLIS);
        }

        return !expectedIncomplete;
    }

    private static boolean hasIncompleteWorkItems(String searchBody) {
        if (searchBody == null || searchBody.isBlank()) {
            return false;
        }
        try {
            var hits = OBJECT_MAPPER.readTree(searchBody).path("hits").path("hits");
            for (var hit : hits) {
                var source = hit.path("_source");
                if (source.path("completedAt").isMissingNode() || source.path("completedAt").isNull()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Failed parsing coordinator working state response", e);
        }
    }

    private record ScenarioResult(int exitCode, boolean outageInjected, boolean coordinatorReEnabled,
                                  long expectedDocs, long finalDocs, String output,
                                  boolean coordinatorHasIncompleteWorkItems) {}
    private record RunResult(int exitCode, String output) {}
}
