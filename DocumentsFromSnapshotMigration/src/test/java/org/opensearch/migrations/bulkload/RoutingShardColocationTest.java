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
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.utils.FileSystemUtils;

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
 * When migrating indices, IndexCreator_OS_2_11 must preserve the source's routing_num_shards
 * in the target index settings. Without this, documents with custom routing land on different
 * shards on the target, breaking routing-based colocation guarantees.
 *
 * The shard assignment formula is: shard_id = hash(routing) % routing_num_shards % number_of_shards
 *
 * This test proves the bug by:
 * 1. Migrating an ES7 index with number_of_routing_shards=10 to OS2 (with the fix)
 * 2. Verifying the migrated index preserves shard assignments (fix works)
 * 3. Creating a second index on the target WITHOUT number_of_routing_shards (simulating the bug)
 * 4. Showing that documents land on different shards (bug demonstrated)
 */
@Slf4j
@Tag("isolatedTest")
public class RoutingShardColocationTest extends SourceTestBase {

    private static final String INDEX_NAME = "routing_test";
    private static final String BUGGY_INDEX_NAME = "routing_test_buggy";
    private static final String SNAPSHOT_NAME = "routing_snap";
    private static final String MIGRATION_SNAPSHOT_NAME = "migration_snap";
    private static final String REPO_NAME = "routing_repo";
    private static final int NUM_SHARDS = 5;
    private static final int NUM_ROUTING_SHARDS = 10; // Explicit value that differs from OS2 default (640 for 5 shards)
    private static final List<String> ROUTING_VALUES = List.of("route_a", "route_b", "route_c");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private File localDirectory;

    private static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of("ES7_native", SearchClusterContainer.ES_V7_10_2, null)
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
            CompletableFuture.allOf(
                CompletableFuture.runAsync(sourceCluster::start),
                CompletableFuture.runAsync(targetCluster::start)
            ).join();
            createIndexWithDocs(sourceCluster);

            verifyRoutingOnSource(sourceCluster);
            migrateAndVerify(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void createIndexWithDocs(SearchClusterContainer cluster) {
        var ops = new ClusterOperations(cluster);
        var body = String.format(
            "{\"settings\":{\"number_of_shards\":%d,\"number_of_replicas\":0,\"number_of_routing_shards\":%d}}",
            NUM_SHARDS, NUM_ROUTING_SHARDS);
        ops.createIndex(INDEX_NAME, body);

        ops.createDocument(INDEX_NAME, "1", "{\"name\":\"alice\",\"group\":\"a\"}", "route_a", null);
        ops.createDocument(INDEX_NAME, "2", "{\"name\":\"bob\",\"group\":\"a\"}", "route_a", null);
        ops.createDocument(INDEX_NAME, "3", "{\"name\":\"charlie\",\"group\":\"b\"}", "route_b", null);
        ops.createDocument(INDEX_NAME, "4", "{\"name\":\"diana\",\"group\":\"b\"}", "route_b", null);
        ops.createDocument(INDEX_NAME, "5", "{\"name\":\"eve\",\"group\":\"c\"}", "route_c", null);
        ops.createDocument(INDEX_NAME, "6", "{\"name\":\"frank\",\"group\":\"c\"}", "route_c", null);
        ops.refresh(INDEX_NAME);
    }

    @SneakyThrows
    private void verifyRoutingOnSource(SearchClusterContainer cluster) {
        var ops = new ClusterOperations(cluster);
        Assertions.assertEquals(6, ops.getDocCount(INDEX_NAME), "Source should have 6 documents");
    }

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

            // Migrate metadata (this uses IndexCreator_OS_2_11 which now copies routing_num_shards)
            migrateMetadata(sourceVersion, localDirectory.getAbsolutePath(), targetCluster);

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

            var targetOps = new ClusterOperations(targetCluster);
            targetOps.refresh(INDEX_NAME);
            Assertions.assertEquals(6, targetOps.getDocCount(INDEX_NAME),
                "Target should have 6 documents total");

            // --- Phase 1: Verify the fix works (migrated index preserves routing_num_shards) ---
            log.info("=== Phase 1: Verifying fix — migrated index should preserve shard assignments ===");
            var sourceRoutingShards = getRoutingNumShards(sourceCluster);
            var targetRoutingShards = getRoutingNumShards(targetCluster);
            log.info("Source routing_num_shards={}, Target routing_num_shards={}", sourceRoutingShards, targetRoutingShards);
            Assertions.assertEquals(sourceRoutingShards, targetRoutingShards,
                "Fix verification: routing_num_shards should match between source and target");

            for (var routing : ROUTING_VALUES) {
                var sourceShard = getShardForRouting(sourceCluster, INDEX_NAME, routing);
                var targetShard = getShardForRouting(targetCluster, INDEX_NAME, routing);
                log.info("Fix OK — routing '{}': source shard={}, target shard={}", routing, sourceShard, targetShard);
                Assertions.assertEquals(sourceShard, targetShard,
                    "Fix verification: shard for routing='" + routing + "' should match");
            }

            // --- Phase 2: Simulate the bug (create index WITHOUT number_of_routing_shards) ---
            log.info("=== Phase 2: Simulating bug — index WITHOUT number_of_routing_shards ===");
            var buggyBody = String.format(
                "{\"settings\":{\"number_of_shards\":%d,\"number_of_replicas\":0}}", NUM_SHARDS);
            targetOps.createIndex(BUGGY_INDEX_NAME, buggyBody);

            // Re-index the same docs with the same routing values
            targetOps.createDocument(BUGGY_INDEX_NAME, "1", "{\"name\":\"alice\",\"group\":\"a\"}", "route_a", null);
            targetOps.createDocument(BUGGY_INDEX_NAME, "2", "{\"name\":\"bob\",\"group\":\"a\"}", "route_a", null);
            targetOps.createDocument(BUGGY_INDEX_NAME, "3", "{\"name\":\"charlie\",\"group\":\"b\"}", "route_b", null);
            targetOps.createDocument(BUGGY_INDEX_NAME, "4", "{\"name\":\"diana\",\"group\":\"b\"}", "route_b", null);
            targetOps.createDocument(BUGGY_INDEX_NAME, "5", "{\"name\":\"eve\",\"group\":\"c\"}", "route_c", null);
            targetOps.createDocument(BUGGY_INDEX_NAME, "6", "{\"name\":\"frank\",\"group\":\"c\"}", "route_c", null);
            targetOps.refresh(BUGGY_INDEX_NAME);

            var buggyRoutingShards = getRoutingNumShards(targetCluster, BUGGY_INDEX_NAME);
            log.info("Buggy index routing_num_shards={} (source was {})", buggyRoutingShards, sourceRoutingShards);
            Assertions.assertNotEquals(sourceRoutingShards, buggyRoutingShards,
                "Bug simulation: buggy index should have DIFFERENT routing_num_shards than source");

            // At least one routing value should map to a different shard
            boolean anyMismatch = false;
            for (var routing : ROUTING_VALUES) {
                var sourceShard = getShardForRouting(sourceCluster, INDEX_NAME, routing);
                var buggyShard = getShardForRouting(targetCluster, BUGGY_INDEX_NAME, routing);
                log.info("Bug demo — routing '{}': source shard={}, buggy target shard={} {}",
                    routing, sourceShard, buggyShard,
                    sourceShard != buggyShard ? "← MISMATCH (bug!)" : "(same by coincidence)");
                if (sourceShard != buggyShard) {
                    anyMismatch = true;
                }
            }
            Assertions.assertTrue(anyMismatch,
                "Bug simulation: at least one routing value should map to a different shard " +
                "when number_of_routing_shards is not preserved. " +
                "Source routing_num_shards=" + sourceRoutingShards +
                ", buggy target routing_num_shards=" + buggyRoutingShards);

            log.info("=== Test passed: bug demonstrated AND fix verified ===");
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
    private int getRoutingNumShards(SearchClusterContainer cluster) {
        return getRoutingNumShards(cluster, INDEX_NAME);
    }

    @SneakyThrows
    private int getRoutingNumShards(SearchClusterContainer cluster, String indexName) {
        var ops = new ClusterOperations(cluster);
        var response = ops.get("/_cluster/state/metadata/" + indexName);
        var json = MAPPER.readTree(response.getValue());
        return json.path("metadata").path("indices").path(indexName).path("routing_num_shards").asInt();
    }

    @SneakyThrows
    private int getShardForRouting(SearchClusterContainer cluster, String indexName, String routing) {
        var ops = new ClusterOperations(cluster);
        var response = ops.get("/" + indexName + "/_search_shards?routing=" + routing);
        var json = MAPPER.readTree(response.getValue());
        return json.path("shards").get(0).get(0).path("shard").asInt();
    }
}
