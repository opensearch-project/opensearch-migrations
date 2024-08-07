package com.rfs;

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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.testcontainers.OpensearchContainer;

import com.rfs.common.FileSystemRepo;
import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.framework.PreloadedSearchClusterContainer;
import com.rfs.framework.SearchClusterContainer;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;

/**
 * TODO - the code in this test was lifted from FullTest.java (now named ParallelDocumentMigrationsTest.java).
 * Some of the functionality and code are shared between the two and should be refactored.
 */
@Slf4j
@Tag("longTest")
public class ProcessLifecycleTest extends SourceTestBase {

    public static final String TOXIPROXY_IMAGE_NAME = "ghcr.io/shopify/toxiproxy:2.9.0";
    public static final String TARGET_DOCKER_HOSTNAME = "target";
    public static final String SNAPSHOT_NAME = "test_snapshot";
    public static final List<String> INDEX_ALLOWLIST = List.of();
    public static final int TOXIPROXY_PORT = 8666;
    public static final int OPENSEARCH_PORT = 9200;

    enum FailHow {
        NEVER,
        AT_STARTUP,
        WITH_DELAYS
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
        // to such a short value (1s), that no document migration will exit in that amount of time. For good
        // measure though, the toxiproxy also adds latency to the requests to make it impossible for the
        // migration to complete w/in that 1s.
        "WITH_DELAYS, 2" })
    public void testProcessExitsAsExpected(String failAfterString, int expectedExitCode) throws Exception {
        final var failHow = FailHow.valueOf(failAfterString);
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testMetadataMigrationContext = MetadataMigrationTestContext.factory().noOtelTracking();

        var sourceImageArgs = makeParamsForBase(SearchClusterContainer.ES_V7_10_2);
        var baseSourceImageVersion = (SearchClusterContainer.Version) sourceImageArgs[0];
        var generatorImage = (String) sourceImageArgs[1];
        var generatorArgs = (String[]) sourceImageArgs[2];
        var targetImageName = SearchClusterContainer.OS_V2_14_0.getImageName();

        var tempDirSnapshot = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
        var tempDirLucene = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");

        try (
            var network = Network.newNetwork();
            var esSourceContainer = new PreloadedSearchClusterContainer(
                baseSourceImageVersion,
                SOURCE_SERVER_ALIAS,
                generatorImage,
                generatorArgs
            );
            var osTargetContainer = new OpensearchContainer<>(targetImageName).withExposedPorts(OPENSEARCH_PORT)
                .withNetwork(network)
                .withNetworkAliases(TARGET_DOCKER_HOSTNAME);
            var proxyContainer = new ToxiproxyContainer(TOXIPROXY_IMAGE_NAME).withNetwork(network)
        ) {

            CompletableFuture.allOf(CompletableFuture.supplyAsync(() -> {
                esSourceContainer.start();
                return null;
            }), CompletableFuture.supplyAsync(() -> {
                osTargetContainer.start();
                return null;
            }), CompletableFuture.supplyAsync(() -> {
                proxyContainer.start();
                return null;
            })).join();

            CreateSnapshot.run(
                c -> new FileSystemSnapshotCreator(
                    SNAPSHOT_NAME,
                    c,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                    testSnapshotContext.createSnapshotCreateContext()
                ),
                new OpenSearchClient(ConnectionContextTestParams.builder()
                    .host(esSourceContainer.getUrl())
                    .build()
                    .toConnectionContext()),
                false
            );
            esSourceContainer.copySnapshotData(tempDirSnapshot.toString());

            migrateMetadata(osTargetContainer, tempDirSnapshot, testMetadataMigrationContext);

            int actualExitCode = runProcessAgainstToxicTarget(tempDirSnapshot, tempDirLucene, proxyContainer, failHow);
            log.atInfo().setMessage("Process exited with code: " + actualExitCode).log();

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

    private static void migrateMetadata(
        OpensearchContainer targetContainer,
        Path tempDirSnapshot,
        MetadataMigrationTestContext testMetadataMigrationContext
    ) {
        String targetAddress = "http://"
            + targetContainer.getHost()
            + ":"
            + targetContainer.getMappedPort(OPENSEARCH_PORT);
        var targetClient = new OpenSearchClient(ConnectionContextTestParams.builder()
            .host(targetAddress)
            .build()
            .toConnectionContext());
        var sourceRepo = new FileSystemRepo(tempDirSnapshot);
        migrateMetadata(sourceRepo, targetClient, SNAPSHOT_NAME, INDEX_ALLOWLIST, testMetadataMigrationContext);
    }

    private static int runProcessAgainstToxicTarget(
        Path tempDirSnapshot,
        Path tempDirLucene,
        ToxiproxyContainer proxyContainer,
        FailHow failHow
    ) throws IOException, InterruptedException {
        String targetAddress = setupProxyAndGetAddress(proxyContainer, failHow);

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
    private static String setupProxyAndGetAddress(ToxiproxyContainer proxyContainer, FailHow failHow)
        throws IOException {
        var toxiproxyClient = new ToxiproxyClient(proxyContainer.getHost(), proxyContainer.getControlPort());
        var proxy = toxiproxyClient.createProxy(
            "proxy",
            "0.0.0.0:" + TOXIPROXY_PORT,
            TARGET_DOCKER_HOSTNAME + ":" + OPENSEARCH_PORT
        );
        String targetAddress = "http://"
            + proxyContainer.getHost()
            + ":"
            + proxyContainer.getMappedPort(TOXIPROXY_PORT);
        if (failHow == FailHow.AT_STARTUP) {
            proxy.disable();
        } else if (failHow == FailHow.WITH_DELAYS) {
            proxy.toxics().latency("latency-toxic", ToxicDirection.DOWNSTREAM, 100);
        }
        return targetAddress;
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
            "--max-initial-lease-duration",
            failHow == FailHow.NEVER ? "PT10M" : "PT1S" };

        // Kick off the doc migration process
        log.atInfo().setMessage("Running RfsMigrateDocuments with args: " + Arrays.toString(args)).log();
        ProcessBuilder processBuilder = new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "com.rfs.RfsMigrateDocuments"
        );
        processBuilder.command().addAll(Arrays.asList(args));
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput();
        return processBuilder;
    }

    @NotNull
    private static Process runAndMonitorProcess(ProcessBuilder processBuilder) throws IOException {
        var process = processBuilder.start();

        log.atInfo().setMessage("Process started with ID: " + process.toHandle().pid()).log();

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
                    .setMessage(() -> "from sub-process [" + process.toHandle().pid() + "]: " + finalLine)
                    .log();
            }
        });

        // Kill the process and fail if we have to wait too long
        readerThread.start();
        return process;
    }

}
