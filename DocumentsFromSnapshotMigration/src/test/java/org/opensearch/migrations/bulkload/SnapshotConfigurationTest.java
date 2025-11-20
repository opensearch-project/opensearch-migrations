package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.utils.FileSystemUtils;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;

/**
 * Tests document migration with different combinations of snapshot compression and global state settings.
 * Verifies that documents are correctly migrated regardless of snapshot configuration.
 */
@Tag("isolatedTest")
@Slf4j
public class SnapshotConfigurationTest extends SourceTestBase {

    @TempDir
    private File localDirectory;

    private enum SnapshotConfiguration {
        COMPRESSED_WITH_GLOBAL_STATE(true, true),
        COMPRESSED_WITHOUT_GLOBAL_STATE(true, false),
        // Validated in existing tests
        //  UNCOMPRESSED_WITH_GLOBAL_STATE(false, true),
        UNCOMPRESSED_WITHOUT_GLOBAL_STATE(false, false);

        private final boolean compressionEnabled;
        private final boolean includeGlobalState;

        SnapshotConfiguration(boolean compressionEnabled, boolean includeGlobalState) {
            this.compressionEnabled = compressionEnabled;
            this.includeGlobalState = includeGlobalState;
        }

        public boolean isCompressionEnabled() {
            return compressionEnabled;
        }

        public boolean isIncludeGlobalState() {
            return includeGlobalState;
        }
    }

    private static Stream<Arguments> snapshotConfigurationScenarios() {
        var targetVersion = SearchClusterContainer.OS_V2_19_1;
        return SupportedClusters.supportedSources(true).stream()
            .filter(
                    // Compressed ES 1 snapshots use currently not supported LZF compression
                    source -> VersionMatchers.isES_1_X.negate().test(source.getVersion())
            )
            .flatMap(sourceVersion ->
                Stream.of(SnapshotConfiguration.values())
                    .map(config -> Arguments.of(sourceVersion, targetVersion, config))
            );
    }

    @ParameterizedTest(name = "From {0} to {1}, Config: {2}")
    @MethodSource(value = "snapshotConfigurationScenarios")
    public void testSnapshotConfiguration(
        SearchClusterContainer.ContainerVersion sourceVersion,
        SearchClusterContainer.ContainerVersion targetVersion,
        SnapshotConfiguration config
    ) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            executeSnapshotConfigurationTest(sourceCluster, targetCluster, config);
        }
    }

    @SneakyThrows
    private void executeSnapshotConfigurationTest(
        SearchClusterContainer sourceCluster,
        SearchClusterContainer targetCluster,
        SnapshotConfiguration config
    ) {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();

        try {
            // === ACTION: Set up the source/target clusters ===
            Startables.deepStart(sourceCluster, targetCluster).join();

            var indexName = "test_index";
            var numberOfShards = 2;
            var sourceClusterOperations = new ClusterOperations(sourceCluster);
            var targetClusterOperations = new ClusterOperations(targetCluster);

            // Create index with explicit shard configuration
            var sourceVersion = sourceCluster.getContainerVersion().getVersion();
            boolean supportsSoftDeletes = VersionMatchers.equalOrGreaterThanES_6_5.test(sourceVersion);
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

            // === ACTION: Create test documents ===
            sourceClusterOperations.createDocument(indexName, "doc1", "{\"field\": \"value1\"}");
            sourceClusterOperations.createDocument(indexName, "doc2", "{\"field\": \"value2\"}");
            sourceClusterOperations.createDocument(indexName, "doc3", "{\"field\": \"value3\"}");
            sourceClusterOperations.createDocument(indexName, "doc4", "{\"field\": \"value4\", \"number\": 42}");
            sourceClusterOperations.createDocument(indexName, "doc5", "{\"field\": \"value5\", \"number\": 100}");

            // Refresh to ensure documents are searchable
            sourceClusterOperations.post("/" + indexName + "/_refresh", null);

            // === ACTION: Take a snapshot with specific configuration ===
            var snapshotName = "test_snapshot";
            
            log.info("Creating snapshot with compression={}, includeGlobalState={}", 
                config.isCompressionEnabled(), config.isIncludeGlobalState());
            
            createSnapshot(
                sourceCluster,
                snapshotName,
                snapshotContext,
                config.isCompressionEnabled(),
                config.isIncludeGlobalState()
            );
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

            log.info("Starting document migration for snapshot configuration: {}", config);

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

            // Verify migration completed successfully
            Assertions.assertEquals(numberOfShards + 1, expectedTerminationException.numRuns);

            log.info("Document migration completed successfully for configuration: {}", config);

            // === VERIFICATION: Check that all documents were migrated correctly ===
            checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);
            
            log.info("Verification passed: All documents migrated correctly with configuration: {}", config);
        } finally {
            if (localDirectory != null && localDirectory.exists()) {
                FileSystemUtils.deleteDirectories(localDirectory.toString());
            }
        }
    }
}
