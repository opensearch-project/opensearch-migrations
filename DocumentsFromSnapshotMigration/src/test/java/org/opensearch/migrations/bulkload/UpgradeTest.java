package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag("isolatedTest")
@Slf4j
public class UpgradeTest extends SourceTestBase {

    @TempDir
    private File legacySnapshotDirectory;
    @TempDir
    private File sourceSnapshotDirectory;

    private static Stream<Arguments> scenarios() {
        var scenarios = Stream.<Arguments>builder();
        scenarios.add(Arguments.of(SearchClusterContainer.ES_V1_7_6, SearchClusterContainer.ES_V2_4_6, SearchClusterContainer.OS_LATEST));
        scenarios.add(Arguments.of(SearchClusterContainer.ES_V2_4_6, SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_LATEST));
        scenarios.add(Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_LATEST));
        scenarios.add(Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_LATEST));
        return scenarios.build();
    }

    @ParameterizedTest(name = "Legacy {0} snapshot upgrade to {1} migrate onto target {2}")
    @MethodSource(value = "scenarios")
    public void migrateFromUpgrade(
        final SearchClusterContainer.ContainerVersion legacyVersion,
        final SearchClusterContainer.ContainerVersion sourceVersion,
        final SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        var testData = new TestData();
        try (
            final var legacyCluster = new SearchClusterContainer(legacyVersion)
        ) {
            legacyCluster.start();

            var legacyClusterOperations = new ClusterOperations(legacyCluster);

            createMultiTypeIndex(testData, legacyClusterOperations);

            // Only create the single-type test index on ES 5.6.16
            if (legacyVersion == SearchClusterContainer.ES_V5_6_16) {
                createSingleTypeIndex(testData, legacyClusterOperations);
                verifySingleTypeIndexOnES5(testData, legacyClusterOperations);
            }

            legacyClusterOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, testData.legacySnapshotRepo);

            // For ES 5.6.16 include both indices
            String indicesToSnapshot = testData.indexName;
            if (legacyVersion == SearchClusterContainer.ES_V5_6_16) {
                indicesToSnapshot = testData.indexName + "," + testData.singleTypeIndexName;
            }
            legacyClusterOperations.takeSnapshot(testData.legacySnapshotRepo, testData.legacySnapshotName, indicesToSnapshot);
            legacyCluster.copySnapshotData(legacySnapshotDirectory.toString());
        }

        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(sourceCluster::start),
                CompletableFuture.runAsync(targetCluster::start)
            ).join();
            sourceCluster.putSnapshotData(legacySnapshotDirectory.toString());

            var sourceOperations = new ClusterOperations(sourceCluster);

            sourceOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, testData.legacySnapshotRepo);
            sourceOperations.restoreSnapshot(testData.legacySnapshotRepo, testData.legacySnapshotName);
            sourceOperations.deleteSnapshot(testData.legacySnapshotRepo, testData.legacySnapshotName);

            // Validate that both indices exist and are queryable after ES 5 → ES 6 restore
            if (legacyVersion == SearchClusterContainer.ES_V5_6_16) {
                verifySingleTypeIndexOnES6(testData, sourceOperations);
            }
            
            var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, testData.snapshotName, testSnapshotContext);

            sourceCluster.copySnapshotData(sourceSnapshotDirectory.toString());

            var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(
                    sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(sourceSnapshotDirectory.toPath(), fileFinder);
            var counter = new AtomicInteger();
            var clockJitter = new Random(1);
            var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();
            var result = waitForRfsCompletion(() -> migrateDocumentsSequentially(sourceRepo,
                                          testData.snapshotName,
                                          null,
                                          targetCluster,
                                          counter,
                                          clockJitter,
                                          testDocMigrationContext,
                                          sourceVersion.getVersion(),
                                          targetVersion.getVersion(),
                        null));
            int expectedWorkers = (legacyVersion == SearchClusterContainer.ES_V5_6_16) ? 11 : 6;
            assertThat("Expected workers should spin up", result.numRuns, equalTo(expectedWorkers));

            var targetOperations = new ClusterOperations(targetCluster);
            targetOperations.get("/_refresh");
            var allDocs = targetOperations.get("/" + testData.indexName + "*/_search");
            var searchResponseBody = allDocs.getValue();

            testData.documentsAndFields.forEach((docId, fields) -> {
                fields.entrySet().stream().forEach(e -> {
                    assertThat("For doc:" + docId + " expecting field", searchResponseBody, containsString(e.getKey()));
                    assertThat("For doc:" + docId + " expecting value", searchResponseBody, containsString(e.getValue().toString()));
                });
            });

            // Validate the single-type index migrated correctly to OpenSearch
            if (legacyVersion == SearchClusterContainer.ES_V5_6_16) {
                verifySingleTypeIndexOnOpenSearch(testData, targetOperations);
            }
        }
    }

    private void createMultiTypeIndex(TestData testData, ClusterOperations operations) {
        operations.createIndex(testData.indexName);
        testData.documentsAndFields.forEach((docId, fields) -> {
            var docBody = "{" + fields.entrySet().stream()
                .map(e -> {
                    final String valueStr;
                    if (e.getValue() instanceof String) {
                        valueStr = "\"" + e.getValue() + "\"";
                    } else {
                        valueStr = e.getValue().toString();
                    }
                    return "\"" + e.getKey() + "\":" + valueStr;
                })
                .collect(Collectors.joining(",")) + "}";
            var docType = "type" + docId;
            operations.createDocument(testData.indexName, docId.toString(), docBody, null, docType);
        });
    }

    /**
     * Create an ES 5 single-type index that will later be restored into ES 6.
     * This index uses `index.mapping.single_type=true` to exercise the Lucene _id encoding
     * edge case that previously caused a NullPointerException during migration.
     */
    private void createSingleTypeIndex(TestData testData, ClusterOperations operations) {
        var createIndexJson = """
        {
          "settings": %s
        }
        """.formatted(CUSTOM_INDEX_SETTINGS);

        operations.createIndex(testData.singleTypeIndexName, createIndexJson);

        testData.singleTypeDocuments.forEach((docId, fields) -> {
            var docBody = "{" + fields.entrySet().stream()
                    .map(e -> {
                        final String valueStr = (e.getValue() instanceof String)
                                ? "\"" + e.getValue() + "\""
                                : e.getValue().toString();
                        return "\"" + e.getKey() + "\":" + valueStr;
                    })
                    .collect(Collectors.joining(",")) + "}";

            // Use doc type for ES 5.x compatibility
            operations.createDocument(testData.singleTypeIndexName, docId, docBody, null, "doc");
        });
    }
 
    private class TestData {
        final String legacySnapshotRepo = "legacy_repo";
        final String legacySnapshotName = "legacy_snapshot";
        final String snapshotName = "snapshot_name";
        final String indexName = "test_index";
        final String singleTypeIndexName = "es5_single_type_index";
        final Map<Integer, Map<String, Object>> documentsAndFields = Map.of(
            1, Map.of("field1", "field1-in-doc1"),
            2, Map.of("field1", "filed1-in-doc2", "field2", 2_12345),
            3, Map.of("field3", 3.12345)
        );
        final Map<String, Map<String, Object>> singleTypeDocuments = Map.of(
            "doc1", Map.of("title", "Document One"),
            "doc2", Map.of("title", "Document Two")
        );
    }

    // Minimal settings to exercise `index.mapping.single_type=true` behavior in ES 5 → ES 6 upgrade
    private static final String CUSTOM_INDEX_SETTINGS = """
        {
          "index": {
            "mapping": {
              "single_type": "true"
            },
            "number_of_shards": "5",
            "number_of_replicas": "1"
          }
        }
        """;

    private void verifySingleTypeIndexOnES5(TestData testData, ClusterOperations operations) {
        log.info("Verifying single-type index on ES 5.6.16");
        operations.get("/_refresh");
        var customDocs = operations.get("/" + testData.singleTypeIndexName + "/_search");
        var body = customDocs.getValue();

        testData.singleTypeDocuments.forEach((docId, fields) -> {
            fields.forEach((fieldName, value) -> {
                assertThat("ES5 - For doc:" + docId + " expecting field", body, containsString(fieldName));
                assertThat("ES5 - For doc:" + docId + " expecting value", body, containsString(value.toString()));
            });
        });

        var settings = operations.get("/" + testData.singleTypeIndexName + "/_settings");
        var settingsBody = settings.getValue();
        assertThat("ES5 - Should have single_type setting", settingsBody, containsString("single_type"));
        log.info("ES 5.6.16 single-type verification completed");
    }

    private void verifySingleTypeIndexOnES6(TestData testData, ClusterOperations operations) {
        log.info("Verifying single-type index on ES 6.8.23 after restoration");
        var allIndices = operations.get("/_cat/indices");
        var indicesBody = allIndices.getValue();
        assertThat("ES6 - Should have base index", indicesBody, containsString(testData.indexName));
        assertThat("ES6 - Should have single-type index", indicesBody, containsString(testData.singleTypeIndexName));

        var customDocs = operations.get("/" + testData.singleTypeIndexName + "/_search");
        var body = customDocs.getValue();
        testData.singleTypeDocuments.forEach((docId, fields) -> {
            fields.forEach((fieldName, value) -> {
                assertThat("ES6 - For doc:" + docId + " expecting field", body, containsString(fieldName));
                assertThat("ES6 - For doc:" + docId + " expecting value", body, containsString(value.toString()));
            });
        });

        var settings = operations.get("/" + testData.singleTypeIndexName + "/_settings");
        var settingsBody = settings.getValue();
        assertThat("ES6 - Should preserve single_type setting", settingsBody, containsString("single_type"));
        log.info("ES 6.8.23 single-type verification completed");
    }

    private void verifySingleTypeIndexOnOpenSearch(TestData testData, ClusterOperations operations) {
        log.info("Verifying single-type index migration to OpenSearch");
        var allIndices = operations.get("/_cat/indices");
        var indicesBody = allIndices.getValue();
        assertThat("OS - Should have base index", indicesBody, containsString(testData.indexName));
        assertThat("OS - Should have single-type index", indicesBody, containsString(testData.singleTypeIndexName));

        var customDocs = operations.get("/" + testData.singleTypeIndexName + "*/_search");
        var body = customDocs.getValue();
        testData.singleTypeDocuments.forEach((docId, fields) -> {
            fields.forEach((fieldName, value) -> {
                assertThat("OS - For doc:" + docId + " expecting field", body, containsString(fieldName));
                assertThat("OS - For doc:" + docId + " expecting value", body, containsString(value.toString()));
            });
        });

        var mapping = operations.get("/" + testData.singleTypeIndexName + "/_mapping");
        var mappingBody = mapping.getValue();
        assertThat("OS - Should preserve title field", mappingBody, containsString("title"));
        log.info("OpenSearch single-type migration verification completed");
    }
}
