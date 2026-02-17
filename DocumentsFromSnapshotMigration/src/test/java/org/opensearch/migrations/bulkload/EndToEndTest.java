package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;

@Tag("isolatedTest")
public class EndToEndTest extends SourceTestBase {
    @TempDir
    private File localDirectory;

    private static final String ES5_SINGLE_TYPE_INDEX = "es5_single_type";

    private static Stream<Arguments> scenarios() {
        return SupportedClusters.supportedPairs(true).stream()
                .map(migrationPair -> Arguments.of(migrationPair.source(), migrationPair.target()));
    }

    @ParameterizedTest(name = "Source {0} to Target {1}")
    @MethodSource(value = "scenarios")
    public void migrationDocuments(
        final SearchClusterContainer.ContainerVersion sourceVersion,
        final SearchClusterContainer.ContainerVersion targetVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            migrationDocumentsWithClusters(sourceCluster, targetCluster);
        }
    }

    private static Stream<Arguments> extendedScenarios() {
        return SupportedClusters.extendedSources().stream().map(s -> Arguments.of(s));
    }

   @ParameterizedTest(name = "Source {0} to Target OS 2.19")
   @MethodSource(value = "extendedScenarios")
    public void extendedMigrationDocuments(
            final SearchClusterContainer.ContainerVersion sourceVersion) {
        try (
                final var sourceCluster = new SearchClusterContainer(sourceVersion);
                final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            migrationDocumentsWithClusters(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void migrationDocumentsWithClusters(
        final SearchClusterContainer sourceCluster,
        final SearchClusterContainer targetCluster
    ) {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();

        var sourceVersion = sourceCluster.getContainerVersion().getVersion();
        String cacheKey = sourceVersion + "-endToEnd";
        Path snapshotDir = localDirectory.toPath();
        boolean cached = fixtureCache.restoreIfCached(cacheKey, snapshotDir);

        try {
            var indexName = "blog_2023";
            var numberOfShards = 3;
            boolean supportsSoftDeletes = VersionMatchers.equalOrGreaterThanES_6_5.test(sourceVersion);
            boolean supportsCompletion = sourceSupportsCompletionFields(sourceVersion);
            boolean isEs5SingleType = VersionMatchers.equalOrGreaterThanES_5_5.test(sourceVersion)
                && VersionMatchers.isES_5_X.test(sourceVersion);
            String snapshotName = "my_snap";

            if (!cached) {
                Startables.deepStart(sourceCluster, targetCluster).join();
                var sourceClusterOperations = new ClusterOperations(sourceCluster);

                String body = String.format(
                    "{" +
                    "  \"settings\": {" +
                    "    \"number_of_shards\": %d," +
                    "    \"number_of_replicas\": 0," +
                    (supportsSoftDeletes
                            ? "    \"index.soft_deletes.enabled\": true,"
                            : "") +
                    "    \"refresh_interval\": -1" +
                    "  }" +
                    "}",
                    numberOfShards
                );
                sourceClusterOperations.createIndex(indexName, body);
                new ClusterOperations(targetCluster).createIndex(indexName, body);

                if (supportsCompletion) {
                    String completionIndex = "completion_index";
                    sourceClusterOperations.createIndexWithCompletionField(completionIndex, numberOfShards);
                    new ClusterOperations(targetCluster).createIndexWithCompletionField(completionIndex, numberOfShards);
                    String completionDoc = "{\"completion\": \"bananas\"}";
                    String docType = sourceClusterOperations.defaultDocType();
                    sourceClusterOperations.createDocument(completionIndex, "1", completionDoc, null, docType);
                    sourceClusterOperations.post("/_refresh", null);
                    new ClusterOperations(targetCluster).post("/_refresh", null);
                }

                String largeDoc = generateLargeDocJson(2);
                sourceClusterOperations.createDocument(indexName, "large1", largeDoc, "3", null);
                sourceClusterOperations.createDocument(indexName, "large2", largeDoc, "3", null);
                sourceClusterOperations.createDocument(indexName, "222", "{\"score\": 42}");
                sourceClusterOperations.createDocument(indexName, "223", "{\"score\": 55, \"active\": true}", "1", null);
                sourceClusterOperations.createDocument(indexName, "224", "{\"score\": 60, \"active\": true}", "1", null);
                sourceClusterOperations.createDocument(indexName, "225", "{\"score\": 77, \"active\": false}", "2", null);

                sourceClusterOperations.post("/" + indexName + "/_refresh", null);
                sourceClusterOperations.createDocument(indexName, "toBeDeleted", "{\"score\": 99, \"active\": true}", "1", null);
                sourceClusterOperations.createDocument(indexName, "remaining", "{\"score\": 88, \"active\": false}", "1", null);
                sourceClusterOperations.post("/" + indexName + "/_refresh", null);
                sourceClusterOperations.deleteDocument(indexName, "toBeDeleted", "1", null);
                sourceClusterOperations.post("/" + indexName + "/_refresh", null);

                if (isEs5SingleType) {
                    sourceClusterOperations.createEs5SingleTypeIndexWithDocs(ES5_SINGLE_TYPE_INDEX);
                }

                var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                        .host(sourceCluster.getUrl())
                        .insecure(true)
                        .build()
                        .toConnectionContext());
                var sourceClient = sourceClientFactory.determineVersionAndCreate();
                var snapshotCreator = new FileSystemSnapshotCreator(
                    snapshotName, "my_snap_repo", sourceClient,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, List.of(),
                    snapshotContext.createSnapshotCreateContext()
                );
                SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
                sourceCluster.copySnapshotData(localDirectory.toString());
                fixtureCache.store(cacheKey, snapshotDir);
            } else {
                // Cache hit — only start target
                targetCluster.start();

                String body = String.format(
                    "{" +
                    "  \"settings\": {" +
                    "    \"number_of_shards\": %d," +
                    "    \"number_of_replicas\": 0," +
                    (supportsSoftDeletes
                            ? "    \"index.soft_deletes.enabled\": true,"
                            : "") +
                    "    \"refresh_interval\": -1" +
                    "  }" +
                    "}",
                    numberOfShards
                );
                var targetClusterOperations = new ClusterOperations(targetCluster);
                targetClusterOperations.createIndex(indexName, body);

                if (supportsCompletion) {
                    targetClusterOperations.createIndexWithCompletionField("completion_index", numberOfShards);
                    targetClusterOperations.post("/_refresh", null);
                }
            }

            var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(sourceVersion, true);
            var sourceRepo = new FileSystemRepo(snapshotDir, fileFinder);

            var runCounter = new AtomicInteger();
            var clockJitter = new Random(1);

            var transformationConfig = VersionMatchers.isES_5_X.or(VersionMatchers.isES_6_X)
                        .test(targetCluster.getContainerVersion().getVersion()) ?
                    "[{\"NoopTransformerProvider\":{}}]" : null;

            var expectedTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
                    sourceRepo,
                    snapshotName,
                    List.of(),
                    targetCluster,
                    runCounter,
                    clockJitter,
                    testDocMigrationContext,
                    sourceVersion,
                    targetCluster.getContainerVersion().getVersion(),
                    transformationConfig
            ));

            int totalShards = numberOfShards;
            if (supportsCompletion) {
                totalShards += numberOfShards;
            }
            if (isEs5SingleType) {
                totalShards += 1;
            }
            Assertions.assertEquals(totalShards + 1, expectedTerminationException.numRuns);

            if (!cached) {
                checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);
                boolean isSourceES1x = VersionMatchers.isES_1_X.test(sourceVersion);
                boolean isTargetES1x = VersionMatchers.isES_1_X.test(targetCluster.getContainerVersion().getVersion());

                if (supportsCompletion) {
                    validateCompletionDoc(new ClusterOperations(targetCluster));
                }

                checkDocsWithRouting(sourceCluster, testDocMigrationContext, !isSourceES1x);
                checkDocsWithRouting(targetCluster, testDocMigrationContext, !isTargetES1x);

                verifyEs5SingleTypeIndex(new ClusterOperations(sourceCluster), new ClusterOperations(targetCluster));
            } else {
                // Cached — verify target side only (refresh needed since checkClusterMigrationOnFinished is skipped)
                var targetClient = new RestClient(ConnectionContextTestParams.builder()
                    .host(targetCluster.getUrl())
                    .build()
                    .toConnectionContext());
                targetClient.get("_refresh", testDocMigrationContext.createUnboundRequestContext());

                var targetClusterOperations = new ClusterOperations(targetCluster);
                boolean isTargetES1x = VersionMatchers.isES_1_X.test(targetCluster.getContainerVersion().getVersion());

                if (supportsCompletion) {
                    validateCompletionDoc(targetClusterOperations);
                }

                checkDocsWithRouting(targetCluster, testDocMigrationContext, !isTargetES1x);
            }
        } finally {
            FileSystemUtils.deleteDirectories(localDirectory.toString());
        }
    }

    @SneakyThrows
    private void verifyEs5SingleTypeIndex(
            ClusterOperations sourceClusterOperations,
            ClusterOperations targetClusterOperations) {

        if (!sourceClusterOperations.shouldTestEs5SingleType()) {
            return;
        }
        targetClusterOperations.post("/_refresh", null);
        var res = targetClusterOperations.get("/" + ES5_SINGLE_TYPE_INDEX + "/_search");
        String body = res.getValue();
        Assertions.assertTrue(body.contains("Doc One"),
                "Expected migrated single_type index to contain 'Doc One'");
        Assertions.assertTrue(body.contains("Doc Two"),
                "Expected migrated single_type index to contain 'Doc Two'");
    }

    private boolean sourceSupportsCompletionFields(Version sourceVersion) {
        return !UnboundVersionMatchers.isBelowES_2_X.test(sourceVersion);
    }

    @SneakyThrows
    private void validateCompletionDoc(ClusterOperations targetClusterOperations) {
        targetClusterOperations.post("/_refresh", null);
        String docType = targetClusterOperations.defaultDocType();
        var res = targetClusterOperations.get("/completion_index/" + docType + "/1");
        ObjectMapper mapper = ObjectMapperFactory.createDefaultMapper();
        JsonNode doc = mapper.readTree(res.getValue());
        JsonNode sourceNode = doc.path("_source").path("completion");
        Assertions.assertTrue(sourceNode.isTextual() || sourceNode.isArray(),
                "Expected 'completion' field to be present and textual or array");
    }

    private String generateLargeDocJson(int sizeInMB) {
        int targetBytes = sizeInMB * 1024 * 1024;

        // Each number + comma is about 8 bytes: 7 digits + 1 comma
        int bytesPerEntry = 8;
        int numEntries = targetBytes / bytesPerEntry;
        StringBuilder sb = new StringBuilder(targetBytes + 100);
        sb.append("{\"numbers\":[");
        for (int i = 0; i < numEntries; i++) {
            sb.append("1000000");  // fixed 7-digit number
            if (i < numEntries - 1) {
                sb.append(",");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private void checkDocsWithRouting(
        SearchClusterContainer clusterContainer,
        DocumentMigrationTestContext context,
        boolean validateRoutingFieldOnResponse) {
        var clusterClient = new RestClient(ConnectionContextTestParams.builder()
            .host(clusterContainer.getUrl())
            .build()
            .toConnectionContext()
        );

        // Check that search by routing works as expected.
        var requests = new SearchClusterRequests(context);
        var hits = requests.searchIndexByQueryString(clusterClient, "blog_2023", "active:true", "1");

        Assertions.assertTrue(hits.isArray());
        Assertions.assertEquals(2, hits.size());

        if (validateRoutingFieldOnResponse) {
            for (JsonNode hit : hits) {
                String routing = hit.path("_routing").asText();
                Assertions.assertEquals("1", routing);
            }
        }
    }
}
