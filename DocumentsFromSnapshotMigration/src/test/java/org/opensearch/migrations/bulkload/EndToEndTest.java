package org.opensearch.migrations.bulkload;

import java.io.File;
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
                final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_1)
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

        try {
            // === ACTION: Set up the source/target clusters ===
            Startables.deepStart(sourceCluster, targetCluster).join();

            var indexName = "blog_2023";
            var numberOfShards = 3;
            var sourceClusterOperations = new ClusterOperations(sourceCluster);
            var targetClusterOperations = new ClusterOperations(targetCluster);

            // Number of default shards is different across different versions on ES/OS.
            // So we explicitly set it.
            var sourceVersion = sourceCluster.getContainerVersion().getVersion();
            boolean supportsSoftDeletes = VersionMatchers.equalOrGreaterThanES_6_5.test(sourceVersion);
            boolean supportsCompletion = sourceSupportsCompletionFields(sourceVersion);
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
            targetClusterOperations.createIndex(indexName, body);

            // Create and verify a 'completion' index only for ES 2.x and above
            if (supportsCompletion) {
                String completionIndex = "completion_index";
                sourceClusterOperations.createIndexWithCompletionField(completionIndex, numberOfShards);
                targetClusterOperations.createIndexWithCompletionField(completionIndex, numberOfShards);
                String completionDoc =
                "{" +
                "    \"completion\": \"bananas\" " +
                "}";
                String docType = sourceClusterOperations.defaultDocType();
                sourceClusterOperations.createDocument(completionIndex, "1", completionDoc, null, docType);
                sourceClusterOperations.post("/_refresh", null);
                targetClusterOperations.post("/_refresh", null);
            }

            // === ACTION: Create two large documents (2MB each) ===
            String largeDoc = generateLargeDocJson(2);
            sourceClusterOperations.createDocument(indexName, "large1", largeDoc, "3", null);
            sourceClusterOperations.createDocument(indexName, "large2", largeDoc, "3", null);

            // === ACTION: Create some searchable documents ===
            sourceClusterOperations.createDocument(indexName, "222", "{\"score\": 42}");
            sourceClusterOperations.createDocument(indexName, "223", "{\"score\": 55, \"active\": true}", "1", null);
            sourceClusterOperations.createDocument(indexName, "224", "{\"score\": 60, \"active\": true}", "1", null);
            sourceClusterOperations.createDocument(indexName, "225", "{\"score\": 77, \"active\": false}", "2", null);


            // To create deleted docs in a segment that persists on the snapshot, refresh, then create two docs on a shard, then after a refresh, delete one.
            sourceClusterOperations.post("/" + indexName + "/_refresh", null);
            sourceClusterOperations.createDocument(indexName, "toBeDeleted", "{\"score\": 99, \"active\": true}", "1", null);
            sourceClusterOperations.createDocument(indexName, "remaining", "{\"score\": 88, \"active\": false}", "1", null);
            sourceClusterOperations.post("/" + indexName + "/_refresh", null);
            sourceClusterOperations.deleteDocument(indexName, "toBeDeleted" , "1", null);
            sourceClusterOperations.post("/" + indexName + "/_refresh", null);

            // For ES 5.x sources (5.5 and 5.6 only)
            if (sourceClusterOperations.shouldTestEs5SingleType()) {
                sourceClusterOperations.createEs5SingleTypeIndexWithDocs(ES5_SINGLE_TYPE_INDEX);
            }

            // === ACTION: Take a snapshot ===
            var snapshotName = "my_snap";
            var snapshotRepoName = "my_snap_repo";
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(sourceCluster.getUrl())
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var snapshotCreator = new FileSystemSnapshotCreator(
                snapshotName,
                snapshotRepoName,
                sourceClient,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            sourceCluster.copySnapshotData(localDirectory.toString());
            var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(
                    sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);

            // === ACTION: Migrate the documents ===
            var runCounter = new AtomicInteger();
            var clockJitter = new Random(1);

            var transformationConfig = VersionMatchers.isES_5_X.or(VersionMatchers.isES_6_X)
                        .test(targetCluster.getContainerVersion().getVersion()) ?
                    "[{\"NoopTransformerProvider\":{}}]" // skip transformations including doc type removal
                    : null;

            // ExpectedMigrationWorkTerminationException is thrown on completion.
            var expectedTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
                    sourceRepo,
                    snapshotName,
                    List.of(),
                    targetCluster,
                    runCounter,
                    clockJitter,
                    testDocMigrationContext,
                    sourceCluster.getContainerVersion().getVersion(),
                    targetCluster.getContainerVersion().getVersion(),
                    transformationConfig
            ));

            int totalShards = numberOfShards; // blog_2023 index
            if (supportsCompletion) {
                totalShards += numberOfShards; // completion_index
            }
            if (sourceClusterOperations.shouldTestEs5SingleType()) {
                totalShards += 1; // es5_single_type index
            }
            Assertions.assertEquals(totalShards + 1, expectedTerminationException.numRuns);

            // Check that the docs were migrated
            checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);
            boolean isSourceES1x = VersionMatchers.isES_1_X.test(sourceCluster.getContainerVersion().getVersion());
            boolean isTargetES1x = VersionMatchers.isES_1_X.test(targetCluster.getContainerVersion().getVersion());

            if (supportsCompletion) {
                validateCompletionDoc(targetClusterOperations);
            }

            // Check that that docs were migrated with routing, routing field not returned on es1 so skip validation
            checkDocsWithRouting(sourceCluster, testDocMigrationContext, !isSourceES1x);
            checkDocsWithRouting(targetCluster, testDocMigrationContext, !isTargetES1x);

            // Check that docs were migrated for shouldTestEs5SingleType cases
            verifyEs5SingleTypeIndex(sourceClusterOperations, targetClusterOperations);
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
