package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reproduction test for the routing shard colocation bug.
 *
 * When migrating indices, IndexCreator_OS_2_11 does not preserve the source's routing_num_shards
 * in the target index settings. This causes documents with custom routing to land on different
 * shards on the target, breaking routing-based search queries.
 *
 * The shard assignment formula is: shard_id = hash(routing) % routing_num_shards % number_of_shards
 *
 * When routing_num_shards differs between source and target, the intermediate modulo produces
 * different results, causing documents to land on different shards.
 *
 * In ES6: routing_num_shards = number_of_shards (no split support)
 * In ES7+: routing_num_shards defaults to 1024 (to support index splitting)
 * On OS2: default number_of_routing_shards also defaults high (1024 for small shard counts)
 *
 * Scenarios tested:
 * 1. ES6 native (routing_num_shards=5) → OS2 target (default routing_num_shards=1024) → MISMATCH
 * 2. ES7 native (routing_num_shards=1024) → OS2 target (default=1024) → may match by coincidence
 * 3. ES6→ES7 restored (routing_num_shards preserved from ES6=5) → OS2 target (default=1024) → MISMATCH
 */
@Slf4j
@Tag("isolatedTest")
public class RoutingShardColocationTest extends SourceTestBase {

    private static final String INDEX_NAME = "routing_test";
    private static final String SNAPSHOT_NAME = "routing_snap";
    private static final String MIGRATION_SNAPSHOT_NAME = "migration_snap";
    private static final String REPO_NAME = "routing_repo";
    private static final int NUM_SHARDS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private File localDirectory;

    /**
     * Scenario 1: Index created natively on ES6 (routing_num_shards = number_of_shards = 5)
     * Scenario 2: Index created natively on ES7 (routing_num_shards = 1024, number_of_shards = 5)
     * Scenario 3: Index created on ES6, snapshot restored to ES7 (routing_num_shards preserved = 5)
     */
    private static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of("ES6_native", SearchClusterContainer.ES_V6_8_23, null),
            Arguments.of("ES7_native", SearchClusterContainer.ES_V7_10_2, null),
            Arguments.of("ES6_restored_to_ES7", SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.ES_V6_8_23)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void routingShardColocationBug(
        String scenarioName,
        SearchClusterContainer.ContainerVersion sourceVersion,
        SearchClusterContainer.ContainerVersion originalVersion
    ) {
        try (
            var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4);
            var sourceCluster = new SearchClusterContainer(sourceVersion)
        ) {
            if (originalVersion != null) {
                // Scenario 3: Create index on original (ES6), snapshot, restore to source (ES7)
                try (var originalCluster = new SearchClusterContainer(originalVersion)) {
                    originalCluster.start();
                    sourceCluster.start();
                    targetCluster.start();
                    setupRestoredIndex(originalCluster, sourceCluster);
                }
            } else {
                CompletableFuture.allOf(
                    CompletableFuture.runAsync(sourceCluster::start),
                    CompletableFuture.runAsync(targetCluster::start)
                ).join();
                createIndexWithDocs(sourceCluster);
            }

            verifyRoutingOnSource(sourceCluster);
            migrateAndVerify(sourceCluster, targetCluster);
        }
    }

    /**
     * Creates a multi-shard index and inserts documents with explicit routing values.
     * With 5 shards and different routing values, documents should be distributed across shards.
     * If routing_num_shards is wrong on the target, routing-based search will miss documents.
     */
    @SneakyThrows
    private void createIndexWithDocs(SearchClusterContainer cluster) {
        var ops = new ClusterOperations(cluster);
        var body = String.format(
            "{\"settings\":{\"number_of_shards\":%d,\"number_of_replicas\":0}}", NUM_SHARDS);
        ops.createIndex(INDEX_NAME, body);

        // Insert docs with different routing values
        // These routing values are chosen so that with routing_num_shards=5 vs 1024,
        // the shard assignment (hash % routing_num_shards % num_shards) will differ
        ops.createDocument(INDEX_NAME, "1", "{\"name\":\"alice\",\"group\":\"a\"}", "route_a", null);
        ops.createDocument(INDEX_NAME, "2", "{\"name\":\"bob\",\"group\":\"a\"}", "route_a", null);
        ops.createDocument(INDEX_NAME, "3", "{\"name\":\"charlie\",\"group\":\"b\"}", "route_b", null);
        ops.createDocument(INDEX_NAME, "4", "{\"name\":\"diana\",\"group\":\"b\"}", "route_b", null);
        ops.createDocument(INDEX_NAME, "5", "{\"name\":\"eve\",\"group\":\"c\"}", "route_c", null);
        ops.createDocument(INDEX_NAME, "6", "{\"name\":\"frank\",\"group\":\"c\"}", "route_c", null);
        ops.refresh(INDEX_NAME);
    }

    /**
     * Creates index on the original cluster (ES6), snapshots it, restores to the source cluster (ES7).
     * This preserves the ES6 routing_num_shards value (= number_of_shards) in the ES7 metadata.
     */
    @SneakyThrows
    private void setupRestoredIndex(SearchClusterContainer originalCluster, SearchClusterContainer sourceCluster) {
        var originalOps = new ClusterOperations(originalCluster);

        // Create index on ES6
        var body = String.format(
            "{\"settings\":{\"number_of_shards\":%d,\"number_of_replicas\":0}}", NUM_SHARDS);
        originalOps.createIndex(INDEX_NAME, body);
        originalOps.createDocument(INDEX_NAME, "1", "{\"name\":\"alice\",\"group\":\"a\"}", "route_a", null);
        originalOps.createDocument(INDEX_NAME, "2", "{\"name\":\"bob\",\"group\":\"a\"}", "route_a", null);
        originalOps.createDocument(INDEX_NAME, "3", "{\"name\":\"charlie\",\"group\":\"b\"}", "route_b", null);
        originalOps.createDocument(INDEX_NAME, "4", "{\"name\":\"diana\",\"group\":\"b\"}", "route_b", null);
        originalOps.createDocument(INDEX_NAME, "5", "{\"name\":\"eve\",\"group\":\"c\"}", "route_c", null);
        originalOps.createDocument(INDEX_NAME, "6", "{\"name\":\"frank\",\"group\":\"c\"}", "route_c", null);
        originalOps.refresh(INDEX_NAME);

        // Snapshot on ES6
        originalOps.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, REPO_NAME);
        originalOps.takeSnapshot(REPO_NAME, SNAPSHOT_NAME, INDEX_NAME);

        // Copy snapshot data to temp dir, then into ES7 container with correct permissions
        var tempSnapshotDir = java.nio.file.Files.createTempDirectory("es6_snapshot");
        originalCluster.copySnapshotData(tempSnapshotDir.toString());
        sourceCluster.putSnapshotData(tempSnapshotDir.toString());

        var sourceOps = new ClusterOperations(sourceCluster);
        sourceOps.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, REPO_NAME);
        sourceOps.restoreSnapshot(REPO_NAME, SNAPSHOT_NAME);
        sourceOps.deleteSnapshot(REPO_NAME, SNAPSHOT_NAME);
        sourceOps.refresh(INDEX_NAME);

        FileSystemUtils.deleteDirectories(tempSnapshotDir.toString());
    }

    /**
     * Verifies that routing-based search works correctly on the source cluster.
     */
    @SneakyThrows
    private void verifyRoutingOnSource(SearchClusterContainer cluster) {
        var ops = new ClusterOperations(cluster);
        ops.refresh(INDEX_NAME);

        Assertions.assertEquals(6, ops.getDocCount(INDEX_NAME), "Source should have 6 documents");

        var hitsA = searchWithRouting(cluster, INDEX_NAME, "group:a", "route_a");
        Assertions.assertEquals(2, hitsA.size(),
            "Source: searching with routing=route_a for group:a should return 2 docs");

        var hitsB = searchWithRouting(cluster, INDEX_NAME, "group:b", "route_b");
        Assertions.assertEquals(2, hitsB.size(),
            "Source: searching with routing=route_b for group:b should return 2 docs");

        var hitsC = searchWithRouting(cluster, INDEX_NAME, "group:c", "route_c");
        Assertions.assertEquals(2, hitsC.size(),
            "Source: searching with routing=route_c for group:c should return 2 docs");
    }

    /**
     * Migrates metadata + documents from source to target and verifies routing behavior.
     */
    @SneakyThrows
    private void migrateAndVerify(SearchClusterContainer sourceCluster, SearchClusterContainer targetCluster) {
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();

        try {
            // Take snapshot on source
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl())
                .insecure(true)
                .build()
                .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var snapshotCreator = new FileSystemSnapshotCreator(
                MIGRATION_SNAPSHOT_NAME,
                REPO_NAME,
                sourceClient,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            sourceCluster.copySnapshotData(localDirectory.toString());

            var sourceVersion = sourceCluster.getContainerVersion().getVersion();
            var targetVersion = targetCluster.getContainerVersion().getVersion();
            var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(sourceVersion, true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);

            // Migrate metadata
            migrateMetadata(sourceVersion, localDirectory.getAbsolutePath(), targetCluster);

            // Log the routing_num_shards before document migration
            var sourceRoutingShards = getRoutingNumShards(sourceCluster);
            var targetRoutingShards = getRoutingNumShards(targetCluster);
            log.info("Source routing_num_shards={}, Target routing_num_shards={}",
                sourceRoutingShards, targetRoutingShards);

            // Migrate documents
            var runCounter = new AtomicInteger();
            var clockJitter = new Random(1);
            waitForRfsCompletion(() -> migrateDocumentsSequentially(
                sourceRepo,
                MIGRATION_SNAPSHOT_NAME,
                List.of(),
                targetCluster,
                runCounter,
                clockJitter,
                testDocMigrationContext,
                sourceVersion,
                targetVersion,
                null
            ));

            // Verify target has all documents
            var targetOps = new ClusterOperations(targetCluster);
            targetOps.refresh(INDEX_NAME);
            Assertions.assertEquals(6, targetOps.getDocCount(INDEX_NAME),
                "Target should have 6 documents total");

            // Core assertion: routing_num_shards must match between source and target.
            // This will FAIL with the current code, demonstrating the bug.
            Assertions.assertEquals(sourceRoutingShards, targetRoutingShards,
                "number_of_routing_shards mismatch between source (" + sourceRoutingShards +
                ") and target (" + targetRoutingShards + "). " +
                "This causes documents with custom routing to land on different shards.");

            // Verify routing-based search on target matches source behavior
            var targetHitsA = searchWithRouting(targetCluster, INDEX_NAME, "group:a", "route_a");
            Assertions.assertEquals(2, targetHitsA.size(),
                "Target: routing=route_a for group:a should return 2 docs");

            var targetHitsB = searchWithRouting(targetCluster, INDEX_NAME, "group:b", "route_b");
            Assertions.assertEquals(2, targetHitsB.size(),
                "Target: routing=route_b for group:b should return 2 docs");

            var targetHitsC = searchWithRouting(targetCluster, INDEX_NAME, "group:c", "route_c");
            Assertions.assertEquals(2, targetHitsC.size(),
                "Target: routing=route_c for group:c should return 2 docs");
        } finally {
            FileSystemUtils.deleteDirectories(localDirectory.toString());
        }
    }

    @SneakyThrows
    private void migrateMetadata(Version sourceVersion, String localDirPath, SearchClusterContainer targetCluster) {
        var arguments = new MigrateOrEvaluateArgs();
        arguments.fileSystemRepoPath = localDirPath;
        arguments.snapshotName = MIGRATION_SNAPSHOT_NAME;
        arguments.sourceVersion = sourceVersion;
        arguments.targetArgs.host = targetCluster.getUrl();

        var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
        new MetadataMigration().migrate(arguments).execute(metadataContext);
    }

    @SneakyThrows
    private JsonNode searchWithRouting(SearchClusterContainer cluster, String index, String query, String routing) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var client = new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl())
            .build()
            .toConnectionContext());
        return new SearchClusterRequests(context).searchIndexByQueryString(client, index, query, routing);
    }

    @SneakyThrows
    private int getRoutingNumShards(SearchClusterContainer cluster) {
        var ops = new ClusterOperations(cluster);
        var response = ops.get("/" + INDEX_NAME + "/_settings?include_defaults=true&flat_settings=true");
        var json = MAPPER.readTree(response.getValue());
        var indexNode = json.path(INDEX_NAME);
        // Check explicit settings first, then defaults
        var settings = indexNode.path("settings");
        var routingShards = settings.path("index.number_of_routing_shards");
        if (!routingShards.isMissingNode()) {
            return routingShards.asInt();
        }
        return indexNode.path("defaults").path("index.number_of_routing_shards").asInt();
    }
}
