package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

@Slf4j
@Tag("isolatedTest")
public class CustomTransformationTest extends SourceTestBase {

    public static final String TARGET_DOCKER_HOSTNAME = "target";
    public static final String SNAPSHOT_NAME = "test_snapshot";

    @AllArgsConstructor
    @Getter
    private static class RunData {
        Path tempDirSnapshot;
        Path tempDirLucene;
        SearchClusterContainer targetContainer;
    }

    @Test
    public void testProcessExitsAsExpected() {
        String nameTransformation = createIndexNameTransformation("geonames", "geonames_transformed");
        var expectedSourceMap = new HashMap<String, Integer>();
        expectedSourceMap.put("geonames", 1);
        var expectedTargetMap = new HashMap<String, Integer>();
        expectedTargetMap.put("geonames_transformed", 1);
        // 2 Shards, for each shard, expect three status code 2 and one status code 0
        int shards = 2;
        int migrationProcessesPerShard = 4;
        int continueExitCode = 2;
        int finalExitCodePerShard = 0;
        runTestProcessWithCheckpoint(continueExitCode, (migrationProcessesPerShard - 1) * shards,
                finalExitCodePerShard, shards, expectedSourceMap, expectedTargetMap,
            d -> runProcessAgainstTarget(d.tempDirSnapshot, d.tempDirLucene, d.targetContainer, nameTransformation
            ));
    }

    @SneakyThrows
    private void runTestProcessWithCheckpoint(int initialExitCode, int initialExitCodes,
                                              int eventualExitCode, int eventualExitCodeCount,
                                              Map<String, Integer> expectedSourceDocs,
                                              Map<String, Integer> expectedTargetDocs,
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
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(esSourceContainer::start),
                CompletableFuture.runAsync(osTargetContainer::start)
            ).join();

            var sourceClusterOperations = new ClusterOperations(esSourceContainer.getUrl());

            var shards = 2;
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
            sourceClusterOperations.createDocument("geonames", "111", "{\"author\":\"Tobias Funke\", \"category\": \"cooking\"}");

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
                exitCode = processRunner.apply(new RunData(tempDirSnapshot, tempDirLucene, osTargetContainer));
                runs++;
                if (exitCode == initialExitCode) {
                    initialExitCodeCount++;
                }
                if (exitCode == eventualExitCode) {
                    finalExitCodeCount++;
                }
                log.atInfo().setMessage("Process exited with code: {}").addArgument(exitCode).log();
                // Clean tree for subsequent run
                deleteTree(tempDirLucene);
            } while (finalExitCodeCount < eventualExitCodeCount && runs < initialExitCodes * 2);

            // Assert doc count on the source and target cluster match expected
            validateFinalClusterDocs(
                esSourceContainer,
                osTargetContainer,
                DocumentMigrationTestContext.factory().noOtelTracking(),
                expectedSourceDocs,
                expectedTargetDocs
            );
        } finally {
            deleteTree(tempDirSnapshot);
        }
    }

    private static String createIndexNameTransformation(String existingIndexName, String newIndexName) {
        JSONArray rootArray = new JSONArray();
        JSONObject firstObject = new JSONObject();
        JSONArray jsonConditionalTransformerProvider = new JSONArray();

        // JsonJMESPathPredicateProvider object
        JSONObject jsonJMESPathPredicateProvider = new JSONObject();
        jsonJMESPathPredicateProvider.put("script", String.format("index._index == '%s'", existingIndexName));
        JSONObject jsonJMESPathPredicateWrapper = new JSONObject();
        jsonJMESPathPredicateWrapper.put("JsonJMESPathPredicateProvider", jsonJMESPathPredicateProvider);
        jsonConditionalTransformerProvider.put(jsonJMESPathPredicateWrapper);

        JSONArray transformerList = new JSONArray();

        // First JsonJoltTransformerProvider
        JSONObject firstJoltTransformer = new JSONObject();
        JSONObject firstJoltScript = new JSONObject();
        firstJoltScript.put("operation", "modify-overwrite-beta");
        firstJoltScript.put("spec", new JSONObject().put("index", new JSONObject().put("\\_index", newIndexName)));
        firstJoltTransformer.put("JsonJoltTransformerProvider", new JSONObject().put("script", firstJoltScript));
        transformerList.put(firstJoltTransformer);

        jsonConditionalTransformerProvider.put(transformerList);
        firstObject.put("JsonConditionalTransformerProvider", jsonConditionalTransformerProvider);
        rootArray.put(firstObject);
        return rootArray.toString();
    }

    @SneakyThrows
    private static int runProcessAgainstTarget(
        Path tempDirSnapshot,
        Path tempDirLucene,
        SearchClusterContainer targetContainer,
        String transformations
    )
    {
        String targetAddress = targetContainer.getUrl();

        int timeoutSeconds = 30;
        ProcessBuilder processBuilder = setupProcess(tempDirSnapshot, tempDirLucene, targetAddress, transformations);

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
        String transformations
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
            "--documents-per-bulk-request",
            "5",
            "--max-connections",
            "4",
            "--source-version",
            "ES_7_10",
            "--doc-transformer-config",
            transformations,
        };

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

    private static void validateFinalClusterDocs(
        SearchClusterContainer esSourceContainer,
        SearchClusterContainer osTargetContainer,
        DocumentMigrationTestContext context,
        Map<String, Integer> expectedSourceDocs,
        Map<String, Integer> expectedTargetDocs
    ) {
        var targetClient = new RestClient(ConnectionContextTestParams.builder()
            .host(osTargetContainer.getUrl())
            .build()
            .toConnectionContext()
        );
        var sourceClient = new RestClient(ConnectionContextTestParams.builder()
            .host(esSourceContainer.getUrl())
            .build()
            .toConnectionContext()
        );

        var requests = new SearchClusterRequests(context);
        var sourceMap = requests.getMapOfIndexAndDocCount(sourceClient);
        var refreshResponse = targetClient.get("_refresh", context.createUnboundRequestContext());
        Assertions.assertEquals(200, refreshResponse.statusCode);
        var targetMap = requests.getMapOfIndexAndDocCount(targetClient);

        MatcherAssert.assertThat(sourceMap, Matchers.equalTo(expectedSourceDocs));
        MatcherAssert.assertThat(targetMap, Matchers.equalTo(expectedTargetDocs));
    }

}
