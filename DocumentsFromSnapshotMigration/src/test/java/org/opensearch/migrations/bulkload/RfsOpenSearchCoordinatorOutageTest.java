package org.opensearch.migrations.bulkload;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

/**
 * Validates current RFS behavior when coordinator access is interrupted after document migration completes.
 *
 * Scenario:
 * 1) Run backfill with a separate coordinator cluster behind Toxiproxy
 * 2) Inject coordinator outage when RFS logs reindex completion for the single-shard data set
 * 3) Keep assertions on currently present failure contract:
 *    - process exits with failure (exit code 1)
 *    - all documents are still present on target
 *    - retry-exhaustion/failure signature is visible in logs
 */
@Tag("isolatedTest")
@Slf4j
public class RfsOpenSearchCoordinatorOutageTest extends SourceTestBase {

    private static final int SHARDS = 1;
    private static final int TOTAL_DOCS = 3000;
    // Harness-side max wait. If exceeded, the test stops the RFS process to keep CI bounded.
    private static final int HARNESS_RFS_MAX_WAIT_SECONDS = 240;
    private static final String SESSION_NAME = "rfs-opensearch-coordinator-outage";
    private static final int DOCUMENTS_PER_BULK_REQUEST = 100;

    @Test
    @SneakyThrows
    void shouldPreserveMigratedDocsButFailFinalizationWhenCoordinatorUnavailableStartsPostBackfill() {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var tempDirSnapshot = Files.createTempDirectory("rfsCoordinatorOutage_snapshot");
        var tempDirLucene = Files.createTempDirectory("rfsCoordinatorOutage_lucene");

        try (
            var network = Network.newNetwork();
            var esSourceContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)
                .withAccessToHost(true)
                .withNetwork(network);
            var osTargetContainer = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
                .withAccessToHost(true)
                .withNetwork(network)
                .withNetworkAliases("target");
            var osCoordinatorContainer = new SearchClusterContainer(SearchClusterContainer.OS_V3_0_0)
                .withAccessToHost(true)
                .withNetwork(network)
                .withNetworkAliases("coordinator");
            var coordinatorProxy = new ToxiProxyWrapper(network)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(esSourceContainer::start),
                CompletableFuture.runAsync(osTargetContainer::start),
                CompletableFuture.runAsync(osCoordinatorContainer::start)
            ).join();

            coordinatorProxy.start("coordinator", 9200);
            coordinatorProxy.getProxy().toxics().latency("coord-latency", ToxicDirection.UPSTREAM, 300);

            var sourceClusterOperations = new ClusterOperations(esSourceContainer);
            sourceClusterOperations.createIndex(
                "geonames",
                "{\"settings\":{\"index\":{\"number_of_shards\":" + SHARDS + ",\"number_of_replicas\":0}}}"
            );

            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(esSourceContainer.getUrl())
                .build()
                .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var generator = new WorkloadGenerator(sourceClient);
            var workloadOptions = new WorkloadOptions();
            workloadOptions.setTotalDocs(TOTAL_DOCS);
            workloadOptions.setWorkloads(List.of(Workloads.GEONAMES));
            workloadOptions.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, SHARDS);
            workloadOptions.setRefreshAfterEachWrite(false);
            workloadOptions.setMaxBulkBatchSize(1000);
            generator.generate(workloadOptions);

            var args = new CreateSnapshot.Args();
            args.snapshotName = SNAPSHOT_NAME;
            args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            args.sourceArgs.host = esSourceContainer.getUrl();
            
            var snapshotCreator = new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext());
            snapshotCreator.run();
            esSourceContainer.copySnapshotData(tempDirSnapshot.toString());

            var expectedDocsOnTarget = getTargetCount(esSourceContainer.getUrl(), "geonames", true);
            Assertions.assertTrue(expectedDocsOnTarget > 0, "Expected source doc count to be positive");

            var coordinatorBefore = readCoordinatorState(osCoordinatorContainer.getUrl());
            var targetDocsBeforeRun = getTargetCount(osTargetContainer.getUrl(), "geonames", false);

            String[] additionalArgs = {
                "--source-version", "ES_7_10",
                "--session-name", SESSION_NAME,
                "--coordinator-host", coordinatorProxy.getProxyUriAsString(),
                "--max-connections", "2",
                "--documents-per-bulk-request", String.valueOf(DOCUMENTS_PER_BULK_REQUEST)
            };

            var processBuilder = setupProcess(tempDirSnapshot, tempDirLucene, osTargetContainer.getUrl(), additionalArgs);
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE);

            var process = processBuilder.start();
            var output = new StringBuilder();
            var outageInjected = new AtomicBoolean(false);
            var outageInjectedAt = new AtomicReference<Instant>();
            var outageInjectionReason = new AtomicReference<>("not-injected");
            var readerThread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                        if (line.contains("Reindexing completed for Index")) {
                            // With SHARDS=1, this indicates the full data set is done on target.
                            // Inject outage at the exact completion log line to target the post-backfill phase
                            if (outageInjected.compareAndSet(false, true)) {
                                coordinatorProxy.disable();
                                outageInjectedAt.set(Instant.now());
                                outageInjectionReason.set("post-reindex-complete-log");
                                log.atInfo().setMessage("Injected coordinator outage after observing reindex completion log")
                                    .log();
                            }
                        }
                        log.atInfo().setMessage("from sub-process [{}]: {}")
                            .addArgument(() -> process.toHandle().pid())
                            .addArgument(line)
                            .log();
                    }
                } catch (Exception e) {
                    log.atWarn().setCause(e).setMessage("Failed to read RFS process output").log();
                }
            }, "rfs-coordinator-outage-reader");
            readerThread.start();

            Instant startedAt = Instant.now();
            Instant rfsProcessEndedAt;

            boolean coordinatorReEnabled = false;
            boolean didRfsProcessTimeOut = false;

            long harnessWaitDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(HARNESS_RFS_MAX_WAIT_SECONDS);
            long reEnableAfterMillis = 15_000;
            while (process.isAlive() && System.nanoTime() < harnessWaitDeadlineNanos) {
                // This runs only after outage injection.
                // It waits for the configured delay from outageInjectedAt, then restores coordinator
                // connectivity exactly once by calling coordinatorProxy.enable()
                if (outageInjected.get() && !coordinatorReEnabled && outageInjectedAt.get() != null) {
                    var elapsedMs = Duration.between(outageInjectedAt.get(), Instant.now()).toMillis();
                    if (elapsedMs >= reEnableAfterMillis) {
                        coordinatorProxy.enable();
                        coordinatorReEnabled = true;
                        log.atInfo().setMessage("Re-enabled coordinator after {}ms")
                            .addArgument(elapsedMs)
                            .log();
                    }
                }
                TimeUnit.MILLISECONDS.sleep(250);
            }
            if (outageInjected.get() && !coordinatorReEnabled) {
                coordinatorProxy.enable();
                coordinatorReEnabled = true;
            }

            if (process.isAlive()) {
                didRfsProcessTimeOut = true;
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
            rfsProcessEndedAt = Instant.now();
            readerThread.join(TimeUnit.SECONDS.toMillis(5));

            var rfsFinalExitCode = process.exitValue();
            var finalDocsOnTarget = getTargetCount(osTargetContainer.getUrl(), "geonames", false);
            var coordinatorStateAfterRun = readCoordinatorState(osCoordinatorContainer.getUrl());

            Assertions.assertTrue(outageInjected.get(), "Coordinator outage was not injected");
            Assertions.assertFalse(didRfsProcessTimeOut, "Harness timed out while waiting for RFS process");
            // Current contract: finalization fails when coordinator is interrupted post-backfill,
            // but migrated documents remain intact on target.
            Assertions.assertEquals(1, rfsFinalExitCode,
                "Expected current failure-mode exit code when coordinator finalization is interrupted");
            Assertions.assertEquals(expectedDocsOnTarget, finalDocsOnTarget,
                "All documents should be present on target despite coordinator failure");
            Assertions.assertTrue(output.toString().contains("RetriesExceededException")
                    || output.toString().contains("Unexpected error running RfsWorker"),
                "Expected retry-exhaustion/failure signature in RFS output");

            log.atInfo().setMessage("Coordinator outage test summary: rfsFinalExitCode={}, startedAt={}, outageInjectedAt={}, rfsProcessEndedAt={}, expectedDocsOnTarget={}, targetDocsBeforeRun={}, finalDocsOnTarget={}, coordinatorReEnabled={}, outageInjectionReason={}")
                .addArgument(rfsFinalExitCode)
                .addArgument(startedAt)
                .addArgument(outageInjectedAt.get())
                .addArgument(rfsProcessEndedAt)
                .addArgument(expectedDocsOnTarget)
                .addArgument(targetDocsBeforeRun)
                .addArgument(finalDocsOnTarget)
                .addArgument(coordinatorReEnabled)
                .addArgument(outageInjectionReason.get())
                .log();

            log.atInfo().setMessage("Target count snapshot: targetDocsBeforeRun={}, finalDocsOnTarget={}")
                .addArgument(targetDocsBeforeRun)
                .addArgument(finalDocsOnTarget)
                .log();
            log.atInfo().setMessage("Coordinator state before run: {}")
                .addArgument(coordinatorBefore)
                .log();
            log.atInfo().setMessage("Coordinator state after run: {}")
                .addArgument(coordinatorStateAfterRun)
                .log();
            log.atInfo().setMessage("Captured RFS process output ({} chars):\n{}")
                .addArgument(output.length())
                .addArgument(output.toString())
                .log();
        } finally {
            FileSystemUtils.deleteDirectories(tempDirSnapshot.toString(), tempDirLucene.toString());
        }
    }

    private static long getTargetCount(String targetHost, String index, boolean failIfMissing) {
        var client = new RestClient(ConnectionContextTestParams.builder()
            .host(targetHost)
            .build()
            .toConnectionContext());

        var refresh = client.get(index + "/_refresh", null);
        if (refresh.statusCode == 404 && !failIfMissing) {
            return 0;
        }
        Assertions.assertEquals(200, refresh.statusCode, "Target refresh failed for index " + index);

        var count = client.get(index + "/_count", null);
        if (count.statusCode == 404 && !failIfMissing) {
            return 0;
        }
        Assertions.assertEquals(200, count.statusCode, "Target count failed for index " + index);

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(count.body).path("count").asLong();
        } catch (Exception e) {
            throw new RuntimeException("Failed parsing target count response", e);
        }
    }

    private static String readCoordinatorState(String coordinatorHost) {
        var client = new RestClient(ConnectionContextTestParams.builder()
            .host(coordinatorHost)
            .build()
            .toConnectionContext());
        var response = client.get(".migrations_working_state*/_search?size=1000", null);
        if (response.statusCode == 404) {
            return "{\"note\":\"working state index not found\"}";
        }
        return response.body == null ? "" : response.body;
    }
}
