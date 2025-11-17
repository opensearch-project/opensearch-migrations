package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.utils.FileSystemUtils;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;


@Slf4j
@Tag("isolatedTest")
public class CustomRfsTransformationTest extends SourceTestBase {

    public static final String TARGET_DOCKER_HOSTNAME = "target";
    public static final String SNAPSHOT_NAME = "test_snapshot";

    @Test
    public void testCustomTransformationProducesDesiredTargetClusterState() {
        String nameTransformation = createIndexNameTransformation("geonames", "geonames_transformed");
        var expectedSourceMap = new HashMap<String, Integer>();
        expectedSourceMap.put("geonames", 1);
        var expectedTargetMap = new HashMap<String, Integer>();
        expectedTargetMap.put("geonames_transformed", 1);
        String[] transformationArgs = {
            "--doc-transformer-config",
            nameTransformation,
        };
        int totalSourceShards = 1;
        Consumer<ClusterOperations> loadDataIntoSource = cluster -> {
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
                totalSourceShards
            );
            cluster.createIndex("geonames", body);
            cluster.createDocument("geonames", "111", "{\"author\":\"Tobias Funke\", \"category\": \"cooking\"}");
        };
        runTestProcess(
            transformationArgs,
            expectedSourceMap,
            expectedTargetMap,
            loadDataIntoSource,
            totalSourceShards,
            SourceTestBase::runProcessAgainstTarget
        );
    }

    @SneakyThrows
    private void runTestProcess(
        String[] transformationArgs,
        Map<String, Integer> expectedSourceDocs,
        Map<String, Integer> expectedTargetDocs,
        Consumer<ClusterOperations> preloadDataOperations,
        Integer numberOfShards,
        Function<String[], Integer> processRunner)
    {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var tempDirSnapshot = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
        var tempDirLucene = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");

        try (
            var esSourceContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)
                    .withAccessToHost(true);
            var network = Network.newNetwork();
            var osTargetContainer = new SearchClusterContainer(SearchClusterContainer.OS_LATEST)
                    .withAccessToHost(true)
                    .withNetwork(network)
                    .withNetworkAliases(TARGET_DOCKER_HOSTNAME);
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(esSourceContainer::start),
                CompletableFuture.runAsync(osTargetContainer::start)
            ).join();

            var sourceClusterOperations = new ClusterOperations(esSourceContainer);
            preloadDataOperations.accept(sourceClusterOperations);

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
                osTargetContainer.getUrl(),
                "--documents-per-bulk-request",
                "5",
                "--max-connections",
                "4",
                "--source-version",
                "ES_7_10"
            };
            String[] completeArgs = Stream.concat(Arrays.stream(processArgs), Arrays.stream(transformationArgs)).toArray(String[]::new);

            // Perform RFS process for each shard
            for(int i = 0; i < numberOfShards; i++) {
                int exitCode = processRunner.apply(completeArgs);
                log.atInfo().setMessage("Process exited with code: {}").addArgument(exitCode).log();
            }

            // Assert doc count on the source and target cluster match expected
            validateFinalClusterDocs(
                esSourceContainer,
                osTargetContainer,
                DocumentMigrationTestContext.factory().noOtelTracking(),
                expectedSourceDocs,
                expectedTargetDocs
            );
        } finally {
            FileSystemUtils.deleteDirectories(tempDirSnapshot.toString(), tempDirLucene.toString());
        }
    }

    // Create a simple Jolt transform which matches documents of a given index name in a snapshot and changes that
    // index name to a desired index name when migrated to the target cluster
    private static String createIndexNameTransformation(String existingIndexName, String newIndexName) {
        return "[\n" +
                "  {\n" +
                "    \"TypeMappingSanitizationTransformerProvider\": {\n" +
                "      \"regexMappings\": [\n" +
                "         {\n" +
                "            \"sourceIndexPattern\": \"" + existingIndexName + "\",\n" +
                "            \"sourceTypePattern\": \".*\",\n" +
                "            \"targetIndexPattern\": \"" + newIndexName + "\"\n" +
                "         }\n" +
                "      ],\n" +
                "      \"sourceProperties\": {\n" +
                "        \"version\": {\n" +
                "          \"major\": 7,\n" +
                "          \"minor\": 10\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "]";
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
