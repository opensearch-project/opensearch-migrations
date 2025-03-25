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

            legacyClusterOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, testData.legacySnapshotRepo);
            legacyClusterOperations.takeSnapshot(testData.legacySnapshotRepo, testData.legacySnapshotName, testData.indexName);
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
            
            var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, testData.snapshotName, testSnapshotContext);

            sourceCluster.copySnapshotData(sourceSnapshotDirectory.toString());

            var sourceRepo = new FileSystemRepo(sourceSnapshotDirectory.toPath());
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
            assertThat("5 shards + 1 nothing to do worker shoul d spin up", result.numRuns, equalTo(6));

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

    private class TestData {
        final String legacySnapshotRepo = "legacy_repo";
        final String legacySnapshotName = "legacy_snapshot";
        final String snapshotName = "snapshot_name";
        final String indexName = "test_index";
        final Map<Integer, Map<String, Object>> documentsAndFields = Map.of(
            1, Map.of("field1", "field1-in-doc1"),
            2, Map.of("field1", "filed1-in-doc2", "field2", 2_12345),
            3, Map.of("field3", 3.12345)
        );
    }
}
