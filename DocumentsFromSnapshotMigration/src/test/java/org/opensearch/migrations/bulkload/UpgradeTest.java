package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.MetadataCommands;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mockitoSession;

@Tag("isolatedTest")
public class UpgradeTest extends SourceTestBase {

    private static final String SNAPSHOT_NAME = "snapshot_for_rfs";
    @TempDir
    private File legacySnapshotDirectory;
    @TempDir
    private File sourceSnapshotDirectory;

    private static Stream<Arguments> scenarios() {
        var scenarios = Stream.<Arguments>builder();
        scenarios.add(Arguments.of(SearchClusterContainer.ES_V2_4_6, SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_V2_14_0));
        // scenarios.add(Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V2_14_0));
        // scenarios.add(Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_14_0));
        return scenarios.build();
    }

    @ParameterizedTest(name = "Legacy {0} snapshot upgrade to {1} migrate onto target {2}")
    @MethodSource(value = "scenarios")
    public void migrateFromUpgrade(
        final SearchClusterContainer.ContainerVersion legacyVersion,
        final SearchClusterContainer.ContainerVersion sourceVersion,
        final SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        var snapshotRepo = "legacy_repo";
        var snapshotName = "legacy_snapshot";
        var originalIndexName = "test_index";
        try (
            final var legacyCluster = new SearchClusterContainer(legacyVersion)
        ) {
            legacyCluster.start();

            var legacyClusterOperations = new ClusterOperations(legacyCluster);

            // Create index and add documents on the source cluster
            createMultiTypeIndex(originalIndexName, legacyClusterOperations);

            legacyClusterOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, snapshotRepo);
            legacyClusterOperations.takeSnapshot(snapshotRepo, snapshotName, originalIndexName);
            legacyCluster.copySnapshotData(legacySnapshotDirectory.toString());
        }

        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            sourceCluster.start();
            sourceCluster.putSnapshotData(legacySnapshotDirectory.toString());

            var upgradedSourceOperations = new ClusterOperations(sourceCluster);

            // Register snapshot repository and restore snapshot to 'upgrade' the cluster
            upgradedSourceOperations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, snapshotRepo);
            upgradedSourceOperations.restoreSnapshot(snapshotRepo, snapshotName);

            // Create the snapshot from the source cluster
            var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, SNAPSHOT_NAME, testSnapshotContext);
            sourceCluster.copySnapshotData(sourceSnapshotDirectory.toString());

            targetCluster.start();
            var sourceRepo = new FileSystemRepo(sourceSnapshotDirectory.toPath());
            var counter = new AtomicInteger();
            var clockJitter = new Random(1);
            var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();
            var result = waitForRfsCompletion(() -> migrateDocumentsSequentially(sourceRepo,
                                          SNAPSHOT_NAME,
                                          null,
                                          targetCluster,
                                          counter,
                                          clockJitter,
                                          testDocMigrationContext,
                                          sourceCluster.getContainerVersion().getVersion()));
            assertThat(result.numRuns, equalTo(3));
        }
    }

    private void createMultiTypeIndex(String originalIndexName, ClusterOperations indexCreatedOperations) {
        indexCreatedOperations.createIndex(originalIndexName);
        indexCreatedOperations.createDocument(originalIndexName, "1", "{\"field1\":\"My Name\"}", null, "type1");
        indexCreatedOperations.createDocument(originalIndexName, "2", "{\"field1\":\"string\", \"field2\":123}", null, "type2");
        indexCreatedOperations.createDocument(originalIndexName, "3", "{\"field3\":1.1}", null, "type3");
    }
}
