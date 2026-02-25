package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

@Tag("isolatedTest")
@Slf4j
public class LeaseExpirationTest extends SourceTestBase {

    public static final String TARGET_DOCKER_HOSTNAME = "target";

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
        // The pipeline is fast — with 1500ms upstream latency, 10 docs/batch, 2 connections,
        // throughput is ~13 docs/sec. With PT10s lease, each run processes ~130 docs before
        // lease expires. 1640 docs per shard requires multiple lease expirations.
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

            // Assert doc count on the target cluster matches source
            checkClusterMigrationOnFinished(esSourceContainer, osTargetContainer,
                    DocumentMigrationTestContext.factory().noOtelTracking());

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
        var latency = tp.toxics().latency("latency-toxic", ToxicDirection.UPSTREAM, 3000);

        // Set to less than 2x lease time to ensure leases aren't doubling
        int timeoutSeconds = 60;

        String[] additionalArgs = {
            "--documents-per-bulk-request", "10",
            "--max-connections", "1",
            "--initial-lease-duration", "PT10s",
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

}
