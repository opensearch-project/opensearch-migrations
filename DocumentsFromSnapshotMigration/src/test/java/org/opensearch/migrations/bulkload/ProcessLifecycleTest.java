package org.opensearch.migrations.bulkload;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.testcontainers.OpensearchContainer;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.Network;

/**
 * TODO - the code in this test was lifted from FullTest.java (now named ParallelDocumentMigrationsTest.java).
 * Some of the functionality and code are shared between the two and should be refactored.
 */
@Slf4j
@Tag("longTest")
public class ProcessLifecycleTest extends SourceTestBase {

    public static final String TARGET_DOCKER_HOSTNAME = "target";
    public static final String SNAPSHOT_NAME = "test_snapshot";
    public static final List<String> INDEX_ALLOWLIST = List.of();
    public static final int OPENSEARCH_PORT = 9200;

    enum FailHow {
        NEVER,
        AT_STARTUP,
        WITH_DELAYS
    }

    @AllArgsConstructor
    @Getter
    private static class RunData {
        Path tempDirSnapshot;
        Path tempDirLucene;
        ToxiProxyWrapper proxyContainer;
    }

    @Test
    public void testExitsZeroThenThreeForSimpleSetup() throws Exception {
        testProcess(3,
            d -> {
                var firstExitCode =
                    runProcessAgainstToxicTarget(d.tempDirSnapshot, d.tempDirLucene, d.proxyContainer, FailHow.NEVER);
                Assertions.assertEquals(0, firstExitCode);
                for (int i=0; i<10; ++i) {
                    var secondExitCode =
                        runProcessAgainstToxicTarget(d.tempDirSnapshot, d.tempDirLucene, d.proxyContainer, FailHow.NEVER);
                    if (secondExitCode != 0) {
                        var lastErrorCode =
                            runProcessAgainstToxicTarget(d.tempDirSnapshot, d.tempDirLucene, d.proxyContainer, FailHow.NEVER);
                        Assertions.assertEquals(secondExitCode, lastErrorCode);
                        return lastErrorCode;
                    }
                }
                Assertions.fail("Ran for many test iterations and didn't get a No Work Available exit code");
                return -1; // won't be evaluated
            });
    }

    @ParameterizedTest
    @CsvSource(value = {
        // This test will go through a proxy that doesn't add any defects and the process will use defaults
        // so that it successfully runs to completion on a small dataset in a short amount of time
        "NEVER, 0",
        // This test is dependent on the toxiproxy being disabled before Migrate Documents is called.
        // The Document Migration process will throw an exception immediately, which will cause an exit.
        "AT_STARTUP, 1",
        // This test is dependent upon the max lease duration that is passed to the command line. It's set
        // to such a short value (1s) that no document migration will exit in that amount of time. For good
        // measure though, the toxiproxy also adds latency to the requests to make it impossible for the
        // migration to complete w/in that 1s.
        "WITH_DELAYS, 2"
    })
    public void testProcessExitsAsExpected(String failAfterString, int expectedExitCode) throws Exception {
        final var failHow = FailHow.valueOf(failAfterString);
        testProcess(expectedExitCode,
            d -> runProcessAgainstToxicTarget(d.tempDirSnapshot, d.tempDirLucene, d.proxyContainer, failHow));
    }

    @SneakyThrows
    private void testProcess(int expectedExitCode, Function<RunData, Integer> processRunner) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var targetImageName = SearchClusterContainer.OS_V2_14_0.getImageName();

        var tempDirSnapshot = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
        var tempDirLucene = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");

        try (
            var network = Network.newNetwork();
            var esSourceContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)
                .withNetwork(network)
                .withNetworkAliases(SOURCE_SERVER_ALIAS);
            var osTargetContainer = new OpensearchContainer<>(targetImageName).withExposedPorts(OPENSEARCH_PORT)
                .withNetwork(network)
                .withNetworkAliases(TARGET_DOCKER_HOSTNAME);
            var proxyContainer = new ToxiProxyWrapper(network)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> esSourceContainer.start()),
                CompletableFuture.runAsync(() -> osTargetContainer.start()),
                CompletableFuture.runAsync(() -> proxyContainer.start(TARGET_DOCKER_HOSTNAME, OPENSEARCH_PORT))
            ).join();

            // Populate the source cluster with data
            var client = new OpenSearchClient(ConnectionContextTestParams.builder()
                .host(esSourceContainer.getUrl())
                .build()
                .toConnectionContext()
            );
            var generator = new WorkloadGenerator(client);
            generator.generate(new WorkloadOptions());

            // Create the snapshot from the source cluster
            var args = new CreateSnapshot.Args();
            args.snapshotName = SNAPSHOT_NAME;
            args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            args.sourceArgs.host = esSourceContainer.getUrl();

            var snapshotCreator = new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext());
            snapshotCreator.run();

            esSourceContainer.copySnapshotData(tempDirSnapshot.toString());

            int actualExitCode = processRunner.apply(new RunData(tempDirSnapshot, tempDirLucene, proxyContainer));
            log.atInfo().setMessage("Process exited with code: {}").addArgument(actualExitCode).log();

            // Check if the exit code is as expected
            Assertions.assertEquals(
                expectedExitCode,
                actualExitCode,
                "The program did not exit with the expected status code."
            );
        } finally {
            deleteTree(tempDirSnapshot);
            deleteTree(tempDirLucene);
        }
    }

    @SneakyThrows
    private static int runProcessAgainstToxicTarget(
        Path tempDirSnapshot,
        Path tempDirLucene,
        ToxiProxyWrapper proxyContainer,
        FailHow failHow)
    {
        String targetAddress = proxyContainer.getProxyUriAsString();
        var tp = proxyContainer.getProxy();
        if (failHow == FailHow.AT_STARTUP) {
            tp.disable();
        } else if (failHow == FailHow.WITH_DELAYS) {
            tp.toxics().latency("latency-toxic", ToxicDirection.DOWNSTREAM, 100);
        }

        int timeoutSeconds = 90;
        ProcessBuilder processBuilder = setupProcess(tempDirSnapshot, tempDirLucene, targetAddress, failHow);

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

        return process.exitValue();
    }


    @NotNull
    private static ProcessBuilder setupProcess(
        Path tempDirSnapshot,
        Path tempDirLucene,
        String targetAddress,
        FailHow failHow
    ) {
        String classpath = System.getProperty("java.class.path");
        String javaHome = System.getProperty("java.home");
        String javaExecutable = javaHome + File.separator + "bin" + File.separator + "java";

        String[] args = {
            "--snapshot-name",
            SNAPSHOT_NAME,
            "--snapshot-local-dir",
            tempDirSnapshot.toString(),
            "--lucene-dir",
            tempDirLucene.toString(),
            "--target-host",
            targetAddress,
            "--index-allowlist",
            "geonames",
            "--documents-per-bulk-request",
            "10",
            "--max-connections",
            "1",
            "--source-version",
            "ES_7_10",
            "--initial-lease-duration",
            failHow == FailHow.NEVER ? "PT10M" : "PT1S" };

        // Kick off the doc migration process
        log.atInfo().setMessage("Running RfsMigrateDocuments with args: {}")
            .addArgument(() -> Arrays.toString(args))
            .log();
        ProcessBuilder processBuilder = new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "org.opensearch.migrations.RfsMigrateDocuments"
        );
        processBuilder.command().addAll(Arrays.asList(args));
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput();
        return processBuilder;
    }

    @NotNull
    private static Process runAndMonitorProcess(ProcessBuilder processBuilder) throws IOException {
        var process = processBuilder.start();

        log.atInfo().setMessage("Process started with ID: {}").addArgument(() -> process.toHandle().pid()).log();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var readerThread = new Thread(() -> {
            String line;
            while (true) {
                try {
                    if ((line = reader.readLine()) == null) break;
                } catch (IOException e) {
                    log.atWarn().setCause(e).setMessage("Couldn't read next line from sub-process").log();
                    return;
                }
                String finalLine = line;
                log.atInfo()
                    .setMessage("from sub-process [{}]: {}")
                    .addArgument(() -> process.toHandle().pid())
                    .addArgument(finalLine)
                    .log();
            }
        });

        // Kill the process and fail if we have to wait too long
        readerThread.start();
        return process;
    }

}
