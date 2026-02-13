package org.opensearch.migrations.bulkload;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.data.IndexOptions;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;
import org.opensearch.migrations.data.workloads.Workloads;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE;
import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

/**
 * Validates that RFS preserves migrated documents when the coordinator becomes unavailable just before the worker’s final completion update.
 *
 * Test flow:
 *   - Run RFS for N-1 shards.
 *   - Trigger coordinator outage during RFS for the last shard.
 *
 * Failure contract asserted:
 *   - outage was injected mid-worker
 *   - Last RFS Worker exit code == 1
 *   - target doc count == expected (all docs preserved)
 *   - retry-exhaustion or failure signature in RFS output
 */
@Tag("isolatedTest")
@Slf4j
public class RfsOpenSearchCoordinatorOutageTest extends SourceTestBase {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Data config
    private static final int SHARDS = 3;
    private static final int TOTAL_DOCS = 30;
    private static final int DOCUMENTS_PER_BULK_REQUEST = 1;
    private static final int MAX_CONNECTIONS = 1;
    private static final String SESSION_NAME = "rfs-opensearch-coordinator-outage";

    // Proxy latency (upstream only)
    private static final int TARGET_PROXY_UPSTREAM_LATENCY_MILLIS = 1_000;
    private static final int COORDINATOR_PROXY_UPSTREAM_LATENCY_MILLIS = 500;

    // Harness timeouts
    private static final int MAX_BACKFILL_ROUNDS = 10;
    private static final int PROCESS_TIMEOUT_SECONDS = 120;
    private static final int OUTAGE_ROUND_TIMEOUT_SECONDS = 240;
    private static final int READER_THREAD_JOIN_MILLIS = 5_000;
    private static final int DESTROY_GRACE_SECONDS = 10;

    // Outage trigger
    private static final String OUTAGE_TRIGGER_LOG_LINE = "Reindexing completed for Index";

    @TempDir
    Path tempRootDir;

    @Test
    @SneakyThrows
    void shouldPreserveMigratedDocsButFailFinalizationWhenCoordinatorUnavailableStartsPostBackfill() {
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

            targetProxy.start("target", 9200);
            targetProxy.getProxy().toxics().latency("target-upstream", ToxicDirection.UPSTREAM,
                TARGET_PROXY_UPSTREAM_LATENCY_MILLIS);
            coordinatorProxy.start("coordinator", 9200);
            coordinatorProxy.getProxy().toxics().latency("coord-upstream", ToxicDirection.UPSTREAM,
                COORDINATOR_PROXY_UPSTREAM_LATENCY_MILLIS);

            var expectedDocs = setupClustersAndData(esSourceContainer, tempDirSnapshot, testSnapshotContext);
            // Diagnostic-only baseline snapshot for debugging; not part of pass/fail contract assertions.
            var coordStateBefore = readCoordinatorState(osCoordinatorContainer.getUrl());

            String[] workerArgs = {
                "--source-version", "ES_7_10",
                "--session-name", SESSION_NAME,
                "--coordinator-host", coordinatorProxy.getProxyUriAsString(),
                "--max-connections", String.valueOf(MAX_CONNECTIONS),
                "--documents-per-bulk-request", String.valueOf(DOCUMENTS_PER_BULK_REQUEST)
            };

            // Run RFS for N-1 shards
            runBackfillUntilOnePending(tempDirSnapshot, targetProxy.getProxyUriAsString(),
                osTargetContainer.getUrl(), osCoordinatorContainer.getUrl(), workerArgs, expectedDocs);

            // Before starting the outage
            var preOutageState = readCoordinatorState(osCoordinatorContainer.getUrl());
            Assertions.assertTrue(countIncompleteWorkItems(preOutageState) > 0,
                "No incomplete work items before outage injection: " + preOutageState);

            // Run RFS for last shard
            var result = runFinalWorkerWithMidWorkerOutage(
                tempDirSnapshot, targetProxy.getProxyUriAsString(), coordinatorProxy, workerArgs);

            var finalDocs = getTargetDocCount(osTargetContainer.getUrl(), "geonames", false);
            var coordStateAfter = readCoordinatorState(osCoordinatorContainer.getUrl());

            log.atInfo().setMessage("Test summary: exitCode={}, outageInjected={}, docs={}/{}")
                .addArgument(result.exitCode).addArgument(result.outageInjected)
                .addArgument(finalDocs).addArgument(expectedDocs).log();

            // Dump logs from the Coordinator for observations
            log.atInfo().setMessage("Coordinator before: {}").addArgument(coordStateBefore).log();
            log.atInfo().setMessage("Coordinator after: {}").addArgument(coordStateAfter).log();

            // Log full output only on failure for diagnostics
            if (result.exitCode != 1 || finalDocs != expectedDocs || !result.outageInjected) {
                log.atWarn().setMessage("Outage-worker output ({} chars):\n{}")
                    .addArgument(result.output.length()).addArgument(result.output).log();
            }

            // Test Assertions
            Assertions.assertTrue(result.outageInjected, "Coordinator outage was not injected mid-worker");
            Assertions.assertFalse(result.timedOut, "Outage-round worker timed out");
            Assertions.assertEquals(expectedDocs, finalDocs, "All docs should be on target despite coordinator failure");
            Assertions.assertEquals(1, result.exitCode, "Expected exit code 1 during coordinator outage");
            Assertions.assertTrue(
                result.output.contains("RetriesExceededException")
                    || result.output.contains("Unexpected error running RfsWorker"),
                "Expected failure signature in RFS output");
        } finally {
            // Defensive cleanup in addition to @TempDir lifecycle.
            cleanupLeakedTempDirs();
        }
    }

    // Helper Methods

    @SneakyThrows
    private long setupClustersAndData(SearchClusterContainer esSource, Path snapshotDir,
                                      SnapshotTestContext testSnapshotContext) {
        new ClusterOperations(esSource).createIndex("geonames",
            "{\"settings\":{\"index\":{\"number_of_shards\":" + SHARDS + ",\"number_of_replicas\":0}}}");

        var sourceClient = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
            .host(esSource.getUrl()).build().toConnectionContext())
            .determineVersionAndCreate();
        var opts = new WorkloadOptions();
        opts.setTotalDocs(TOTAL_DOCS);
        opts.setWorkloads(List.of(Workloads.GEONAMES));
        opts.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, SHARDS);
        opts.setRefreshAfterEachWrite(false);
        opts.setMaxBulkBatchSize(1000);
        new WorkloadGenerator(sourceClient).generate(opts);

        var snapshotArgs = new CreateSnapshot.Args();
        snapshotArgs.snapshotName = SNAPSHOT_NAME;
        snapshotArgs.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
        snapshotArgs.sourceArgs.host = esSource.getUrl();
        new CreateSnapshot(snapshotArgs, testSnapshotContext.createSnapshotCreateContext()).run();
        esSource.copySnapshotData(snapshotDir.toString());

        var expectedDocs = getTargetDocCount(esSource.getUrl(), "geonames", true);
        Assertions.assertTrue(expectedDocs > 0, "Expected source doc count to be positive");
        return expectedDocs;
    }

    @SneakyThrows
    private void runBackfillUntilOnePending(
        Path snapshotDir, String targetProxyHost, String targetDirectHost,
        String coordinatorDirectHost, String[] workerArgs, long expectedDocs
    ) {
        int round;
        for (round = 1; round <= MAX_BACKFILL_ROUNDS; round++) {
            if (round > 1) {
                var pending = countIncompleteWorkItems(readCoordinatorState(coordinatorDirectHost));
                var docs = getTargetDocCount(targetDirectHost, "geonames", false);
                log.atInfo().setMessage("Pre-round {} check: {} incomplete, {}/{} docs")
                    .addArgument(round).addArgument(pending)
                    .addArgument(docs).addArgument(expectedDocs).log();
                if (pending == 1 && docs > 0) {
                    log.atInfo().setMessage("Stopping backfill — 1 shard remains for Phase 2").log();
                    return;
                }
            }
            runBackfillRound(snapshotDir, targetProxyHost, workerArgs, round);
        }
        Assertions.fail("Backfill did not converge within " + MAX_BACKFILL_ROUNDS + " rounds");
    }

    @SneakyThrows
    private OutageRoundResult runFinalWorkerWithMidWorkerOutage(
        Path snapshotDir, String targetProxyHost, ToxiProxyWrapper coordinatorProxy, String[] workerArgs
    ) {
        var outageInjected = new AtomicBoolean(false);
        var timedOut = new AtomicBoolean(false);
        var output = new StringBuilder();
        var outageDir = Files.createTempDirectory(tempRootDir, "rfsCoordinatorOutage_lucene_outage");
        int exitCode = -1;

        try {
            var pb = setupProcess(snapshotDir, outageDir, targetProxyHost, workerArgs);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            var process = pb.start();

            var readerThread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.atInfo().setMessage("from outage-worker [{}]: {}")
                            .addArgument(() -> process.toHandle().pid())
                            .addArgument(line).log();
                        output.append(line).append('\n');
                        if (line.contains(OUTAGE_TRIGGER_LOG_LINE)
                                && outageInjected.compareAndSet(false, true)) {
                            try {
                                coordinatorProxy.disable();
                                log.atInfo().setMessage("Injected coordinator outage mid-worker").log();
                            } catch (Exception e) {
                                log.atError().setCause(e).setMessage("Failed to disable coordinator proxy").log();
                            }
                        }
                    }
                } catch (Exception e) {
                    log.atWarn().setCause(e).setMessage("Failed to read outage-worker output").log();
                }
            }, "rfs-coordinator-outage-reader");
            readerThread.start();

            if (!process.waitFor(OUTAGE_ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                timedOut.set(true);
                process.destroy();
                if (!process.waitFor(DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
            readerThread.join(READER_THREAD_JOIN_MILLIS);
            exitCode = process.exitValue();
        } finally {
            coordinatorProxy.enable();
            FileSystemUtils.deleteDirectories(outageDir.toString());
        }
        return new OutageRoundResult(exitCode, outageInjected.get(), timedOut.get(), output.toString());
    }

    @SneakyThrows
    private void runBackfillRound(Path snapshotDir, String targetHost, String[] args, int round) {
        var luceneDir = Files.createTempDirectory(tempRootDir, "rfsCoordinatorOutage_lucene_round" + round);
        try {
            var proc = runAndMonitorProcess(setupProcess(snapshotDir, luceneDir, targetHost, args));
            if (!proc.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                Assertions.fail("Worker timed out in backfill round " + round);
            }
            int exitCode = proc.exitValue();
            log.atInfo().setMessage("Backfill round {} exit code: {}").addArgument(round).addArgument(exitCode).log();
            if (exitCode != 0 && exitCode != NO_WORK_LEFT_EXIT_CODE) {
                Assertions.fail("Unexpected exit code " + exitCode + " in backfill round " + round);
            }
        } finally {
            FileSystemUtils.deleteDirectories(luceneDir.toString());
        }
    }

    private static long getTargetDocCount(String host, String index, boolean failIfMissing) {
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

    private static String readCoordinatorState(String coordinatorHost) {
        var client = new RestClient(ConnectionContextTestParams.builder()
            .host(coordinatorHost).build().toConnectionContext());
        var response = client.get(".migrations_working_state*/_search?size=1000", null);
        return response.statusCode == 404 ? "{}" : (response.body == null ? "" : response.body);
    }

    private static long countIncompleteWorkItems(String stateJson) {
        try {
            if (stateJson == null || stateJson.isBlank() || "{}".equals(stateJson.trim())) {
                return 0;
            }
            var hits = OBJECT_MAPPER.readTree(stateJson).path("hits").path("hits");
            if (!hits.isArray()) {
                return 0;
            }
            long incomplete = 0;
            for (var hit : hits) {
                if (!hit.path("_source").has("completedAt")) incomplete++;
            }
            return incomplete;
        } catch (Exception e) {
            throw new RuntimeException("Failed parsing coordinator state", e);
        }
    }

    private record OutageRoundResult(int exitCode, boolean outageInjected,
                                     boolean timedOut, String output) {}

    @SneakyThrows
    private void cleanupLeakedTempDirs() {
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
