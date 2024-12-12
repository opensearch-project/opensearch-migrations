package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;
import org.opensearch.migrations.data.workloads.Workloads;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/**
 * TODO - the code in this test was lifted from ProcessLifecycleTest.java
 * Some of the functionality and code are shared between the two and should be refactored.
 */
@Slf4j
public class LeaseExpirationTest extends SourceTestBase {

    public static final String TARGET_DOCKER_HOSTNAME = "target";
    public static final String SNAPSHOT_NAME = "test_snapshot";

    @AllArgsConstructor
    @Getter
    private static class RunData {
        String[] processArgs;
        ToxiProxyWrapper proxyContainer;
    }

    @Test
    @Tag("isolatedTest")
    public void testProcessExitsAsExpected() {
        // Sending 5 docs per request with 4 requests concurrently with each taking 0.125 second is 160 docs/sec
        // will process 9760 docs in 61 seconds. With 20s lease duration, expect to be finished in 4 leases.
        // This is ensured with the toxiproxy settings, the migration should not be able to be completed
        // faster, but with a heavily loaded test environment, may be slower which is why this is marked as
        // isolated.
        // 2 Shards, for each shard, expect three status code 2 and one status code 0 (4 leases)
        int shards = 2;
        int indexDocCount = 9760 * shards;
        int migrationProcessesPerShard = 4;
        int continueExitCode = 2;
        int finalExitCodePerShard = 0;
        runTestProcessWithCheckpoint(continueExitCode, (migrationProcessesPerShard - 1) * shards,
                finalExitCodePerShard, shards, shards, indexDocCount,
            d -> runProcessAgainstToxicTarget(d.processArgs, d.proxyContainer)
        );
    }

    @SneakyThrows
    private void runTestProcessWithCheckpoint(int expectedInitialExitCode, int expectedInitialExitCodeCount,
                                              int expectedEventualExitCode, int expectedEventualExitCodeCount,
                                              int shards, int indexDocCount,
                                              Function<RunData, Integer> processRunner) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var tempDirSnapshot = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
        var tempDirLucene = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");

        try (
            var esSourceContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)
                    .withAccessToHost(true);
            var network = Network.newNetwork();
            var osTargetContainer = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
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
            var client = new OpenSearchClient(ConnectionContextTestParams.builder()
                .host(esSourceContainer.getUrl())
                .build()
                .toConnectionContext()
            );
            var generator = new WorkloadGenerator(client);
            var workloadOptions = new WorkloadOptions();

            var sourceClusterOperations = new ClusterOperations(esSourceContainer.getUrl());

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

            workloadOptions.totalDocs = indexDocCount;
            workloadOptions.workloads = List.of(Workloads.GEONAMES);
            workloadOptions.maxBulkBatchSize = 1000;
            generator.generate(workloadOptions);

            // Create the snapshot from the source cluster
            var args = new CreateSnapshot.Args();
            args.snapshotName = SNAPSHOT_NAME;
            args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            args.sourceArgs.host = esSourceContainer.getUrl();

            var snapshotCreator = new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext());
            snapshotCreator.run();

            esSourceContainer.copySnapshotData(tempDirSnapshot.toString());

            String[] processArgs = {
                "--snapshot-name",
                SNAPSHOT_NAME,
                "--snapshot-local-dir",
                tempDirSnapshot.toString(),
                "--lucene-dir",
                tempDirLucene.toString(),
                "--target-host",
                proxyContainer.getProxyUriAsString(),
                "--index-allowlist",
                "geonames",
                "--documents-per-bulk-request",
                "5",
                "--max-connections",
                "4",
                "--source-version",
                "ES_7_10",
                "--initial-lease-duration",
                "PT20s" };

            int exitCode;
            int initialExitCodeCount = 0;
            int finalExitCodeCount = 0;
            int runs = 0;
            do {
                exitCode = processRunner.apply(new RunData(processArgs, proxyContainer));
                runs++;
                if (exitCode == expectedInitialExitCode) {
                    initialExitCodeCount++;
                }
                if (exitCode == expectedEventualExitCode) {
                    finalExitCodeCount++;
                }
                log.atInfo().setMessage("Process exited with code: {}").addArgument(exitCode).log();
                // Clean tree for subsequent run
                deleteTree(tempDirLucene);
            } while (finalExitCodeCount < expectedEventualExitCodeCount && runs < expectedInitialExitCodeCount * 2);

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
            deleteTree(tempDirSnapshot);
        }
    }

    @SneakyThrows
    private static int runProcessAgainstToxicTarget(String[] processArgs, ToxiProxyWrapper proxyContainer)
    {
        var tp = proxyContainer.getProxy();
        var latency = tp.toxics().latency("latency-toxic", ToxicDirection.UPSTREAM, 125);

        // Set to less than 2x lease time to ensure leases aren't doubling
        int timeoutSeconds = 30;
        ProcessBuilder processBuilder = setupProcess(processArgs);

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

}
