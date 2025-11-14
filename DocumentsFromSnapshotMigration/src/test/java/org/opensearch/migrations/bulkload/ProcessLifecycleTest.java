package org.opensearch.migrations.bulkload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.utils.FileSystemUtils;
import org.opensearch.testcontainers.OpensearchContainer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.netty.handler.codec.http.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

@Slf4j
@Tag("isolatedTest")
public class ProcessLifecycleTest extends SourceTestBase {

    public static final String TARGET_DOCKER_HOSTNAME = "target";
    public static final int OPENSEARCH_PORT = 9200;
    public static final int RECEIVED_SIGTERM_EXIT_CODE = 143;

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

    // The following test expects to get an exit code of 0 (TBD_GOES_HERE) at least two times, and then an
    // exit code of 3 (NO_WORK_LEFT) within a maximum of 21 iterations.
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

        var targetImageName = SearchClusterContainer.OS_LATEST.getImageName();

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
                CompletableFuture.runAsync(esSourceContainer::start),
                CompletableFuture.runAsync(osTargetContainer::start),
                CompletableFuture.runAsync(() -> proxyContainer.start(TARGET_DOCKER_HOSTNAME, OPENSEARCH_PORT))
            ).join();

            // Populate the source cluster with data
            var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(esSourceContainer.getUrl())
                    .build()
                    .toConnectionContext()
            );
            var client = clientFactory.determineVersionAndCreate();
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
            FileSystemUtils.deleteDirectories(tempDirSnapshot.toString(), tempDirLucene.toString());
        }
    }

    @SneakyThrows
    private static int runProcessAgainstToxicTarget(
        Path tempDirSnapshot,
        Path tempDirLucene,
        ToxiProxyWrapper proxyContainer,
        FailHow failHow
    ) {
        String targetAddress = proxyContainer.getProxyUriAsString();
        var tp = proxyContainer.getProxy();
        if (failHow == FailHow.AT_STARTUP) {
            tp.disable();
        } else if (failHow == FailHow.WITH_DELAYS) {
            tp.toxics().latency("latency-toxic", ToxicDirection.DOWNSTREAM, 100);
        }

        int timeoutSeconds = 90;
        String initialLeaseDuration = failHow == FailHow.NEVER ? "PT10M" : "PT1S";

        String[] additionalArgs = {
            "--documents-per-bulk-request", "10",
            "--max-connections", "1",
            "--initial-lease-duration", initialLeaseDuration,
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

        return process.exitValue();
    }

    @SneakyThrows
    private static ProcessBuilder setupProcessWithSlowProxy(RunData d) {
        var tp = d.proxyContainer.getProxy();
        tp.toxics().latency("latency-toxic", ToxicDirection.DOWNSTREAM, 250);
        return setupProcess(
                d.tempDirSnapshot,
                d.tempDirLucene,
                d.proxyContainer.getProxyUriAsString(),
                new String[] {"--documents-per-bulk-request", "4", "--max-connections", "1"}
        );
    }

    @Test
    void exitCleanlyFromSigtermAfterUpdatingWorkItem() {
        testProcess(RECEIVED_SIGTERM_EXIT_CODE, d -> {
            // The geonames shards are each 195 documents, and we need to guarantee that we're in the middle
            // of a shard when the sigterm is sent.
            // The slow proxy operates with up to 4 bulk requests per second, with 4 documents each, for a total
            // rate of 16 docs/second, meaning it can finish at most 160 documents in 10 seconds (it will be less
            // because it also has to acquire a lease and download the shard).
            var processBuilder = setupProcessWithSlowProxy(d);
            Process process = null;
            try {
                process = runAndMonitorProcess(processBuilder);
                process.waitFor(10, TimeUnit.SECONDS);
                process.destroy();
                // Give it 30 seconds and then force kill if it hasn't stopped yet.
                process.waitFor(30, TimeUnit.SECONDS);
                Assertions.assertFalse(process.isAlive());
                process.destroyForcibly();
            } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
            }

            // Check that there is a .migrations_working_state index on the target, and it has the expected values.
            var client = new RestClient(ConnectionContextTestParams.builder()
                    .host(d.proxyContainer.getProxyUriAsString())
                    .build()
                    .toConnectionContext());
            Assertions.assertEquals(200, client.get(".migrations_working_state", null).statusCode);
            var fullWorkingStateResponse = client.asyncRequest(HttpMethod.GET, ".migrations_working_state/_search", "{\"query\": {\"match_all\": {}}, \"size\": 1000}", null, null).block();
            Assertions.assertNotNull(fullWorkingStateResponse);
            Assertions.assertNotNull(fullWorkingStateResponse.body);
            Assertions.assertEquals(200, fullWorkingStateResponse.statusCode);
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                    var workingState = objectMapper.readValue(fullWorkingStateResponse.body, ObjectNode.class);
                    // Check that at least one item in the workItemsList has a `successor_items` field.
                    var workItemsList = workingState.get("hits").get("hits");
                    Assertions.assertFalse(workItemsList.isEmpty());
                    var successorItemsList = new ArrayList<Boolean>();
                    workItemsList.forEach(workItem -> successorItemsList.add(workItem.get("_source").has("successor_items")));
                    Assertions.assertTrue(successorItemsList.contains(true));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            return process.exitValue();
        });
    }

    @Test
    void exitCleanlyFromSigtermBeforeReindexingHasStarted() {
        // This test is very similar to the one above, but does a much quicker sigterm in order to "catch" it before
        // reindexing has started, and also does a much quicker check that it's actually terminated cleanly (it is able
        // to shut down almost instantly because it doesn't need to make any network calls).
        testProcess(RECEIVED_SIGTERM_EXIT_CODE, d -> {
            var processBuilder = setupProcessWithSlowProxy(d);
            Process process = null;
            try {
                process = runAndMonitorProcess(processBuilder);
                process.waitFor(2, TimeUnit.SECONDS);
                process.destroy();
                // Give it 1 second before checking if it has shutdown.
                process.waitFor(1, TimeUnit.SECONDS);
                Assertions.assertFalse(process.isAlive());
                // If not, forcibly kill it.
                process.destroyForcibly();
            } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
            }
            return process.exitValue();
        });
    }

}
