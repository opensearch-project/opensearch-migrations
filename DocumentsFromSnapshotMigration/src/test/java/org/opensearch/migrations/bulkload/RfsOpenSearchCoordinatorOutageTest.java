package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.workcoordination.CoordinateWorkHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.WorkCoordinatorFactory;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

/**
 * Covers two coordinator-outage scenarios after data migration starts:
 * - Coordinator becomes unavailable and stays down (current behavior: exception thrown, docs preserved)
 * - Coordinator goes down, then comes back later (currently disabled, expects clean completion)
 *
 * Runs RFS via {@link RfsMigrateDocuments#run} with a dedicated coordinator cluster
 * separate from the target cluster. ToxiProxy injects outages on the coordinator connection at
 * scheduled times while the target data path remains unaffected.
 */
@Tag("isolatedTest")
@Slf4j
public class RfsOpenSearchCoordinatorOutageTest extends SourceTestBase {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int SHARDS = 1;
    private static final int TOTAL_DOCS = 60;
    private static final String INDEX_NAME = "geonames";
    private static final String SESSION_NAME = "rfs-coordinator-outage";

    private static final Version SOURCE_VERSION = SearchClusterContainer.ES_V7_10_2.getVersion();
    private static final Version COORDINATOR_VERSION = SearchClusterContainer.OS_V3_0_0.getVersion();

    // With 1 doc/bulk, 1 connection, and 1000ms upstream latency on the target proxy,
    // each bulk request takes ~1s round-trip, so 60 docs take ~60s to migrate.
    private static final int TARGET_PROXY_UPSTREAM_LATENCY_MILLIS = 1_000;

    // Timeline:
    //   t=0s   : RFS starts (lease acquisition, shard setup, doc migration begins)
    //   t=30s  : Coordinator disabled — mid-migration, ~30 docs already sent
    //   t=60s  : All 60 docs reach target, RFS tries to finalize on coordinator (fails)
    //   t=150s : (test 2 only) Coordinator re-enabled after 120s restart window
    //   Test 1 uses COORDINATOR_NEVER_REENABLE_SECONDS so coordinator stays down permanently
    private static final int COORDINATOR_DISABLE_AFTER_SECONDS = 30;
    private static final int COORDINATOR_REENABLE_AFTER_SECONDS = 120;
    private static final int COORDINATOR_NEVER_REENABLE_SECONDS = 3600; // effectively never within test lifetime

    @TempDir
    Path tempRootDir;

    @Test
    @SneakyThrows
    void allDocsMigratedButCoordinatorUnavailableAtCompletion() {
        runCoordinatorOutageScenario(COORDINATOR_NEVER_REENABLE_SECONDS, r -> {
            // ASSERT : RFS failed specifically due to coordinator retry exhaustion
            Assertions.assertInstanceOf(OpenSearchWorkCoordinator.RetriesExceededException.class,
                r.thrownException(),
                "Expected RetriesExceededException when coordinator becomes unavailable");
        });
    }

    @Test
    @Disabled("Known limitation: current coordinator retry window is shorter than long restart duration")
    @SneakyThrows
    void allDocsMigratedButCoordinatorLongRestartsAtCompletion() {
        runCoordinatorOutageScenario(COORDINATOR_REENABLE_AFTER_SECONDS, r -> {
            // ASSERT timeline: coordinator re-enabled before RFS finished
            Assertions.assertTrue(r.coordinatorReEnabled(), "Coordinator was not re-enabled");
            Assertions.assertTrue(r.coordinatorReEnabledAt() < r.rfsEndedAt(),
                "Coordinator should have been re-enabled before RFS finished");

            // ASSERT that RFS completed without exception
            Assertions.assertNull(r.thrownException(),
                "Expected RFS to recover and complete cleanly after coordinator returns");
        });
    }

    @SneakyThrows
    private ScenarioResult runCoordinatorOutageScenario(int reEnableAfterSeconds,
                                                        java.util.function.Consumer<ScenarioResult> scenarioAssertions) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testContext = DocumentMigrationTestContext.factory().noOtelTracking();
        var tempDirSnapshot = Files.createDirectory(tempRootDir.resolve("rfsCoordinatorOutage_snapshot"));
        var tempDirLucene = Files.createDirectory(tempRootDir.resolve("rfsCoordinatorOutage_lucene"));

        try (
            var network = Network.newNetwork();
            var esSourceContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)
                .withAccessToHost(true).withNetwork(network);
            var osTargetContainer = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
                .withAccessToHost(true).withNetwork(network).withNetworkAliases("target");
            var osCoordinatorContainer = new SearchClusterContainer(SearchClusterContainer.OS_V3_0_0)
                .withAccessToHost(true).withNetwork(network).withNetworkAliases("coordinator");
            var targetProxy = new ToxiProxyWrapper(network);
            var coordinatorProxy = new ToxiProxyWrapper(network)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(esSourceContainer::start),
                CompletableFuture.runAsync(osTargetContainer::start),
                CompletableFuture.runAsync(osCoordinatorContainer::start)
            ).join();

            // === ACTION : Place a toxi proxy in front of the TARGET cluster
            targetProxy.start("target", 9200);
            targetProxy.getProxy().toxics().latency("target-upstream", ToxicDirection.UPSTREAM,
                TARGET_PROXY_UPSTREAM_LATENCY_MILLIS);

            // ASSERT that TARGET cluster is healthy and reachable
            assertClusterReachability(targetProxy.getProxyUriAsString(), "target", true);

            // === ACTION : Place a toxi proxy in front of the COORDINATOR cluster
            coordinatorProxy.start("coordinator", 9200);

            // ASSERT that COORDINATOR cluster is healthy and reachable
            assertClusterReachability(coordinatorProxy.getProxyUriAsString(), "coordinator", true);

            // === ACTION : Ingest data on SOURCE cluster and take a snapshot
            var sourceClusterOperations = new ClusterOperations(esSourceContainer);
            setupAndSnapshotSourceCluster(
                sourceClusterOperations, esSourceContainer, SHARDS, INDEX_NAME, TOTAL_DOCS,
                tempDirSnapshot, testSnapshotContext);

            // ASSERT source snapshot has expected number of docs
            var expectedDocs = getDocCountFromCluster(esSourceContainer.getUrl(), INDEX_NAME, true);
            Assertions.assertEquals(TOTAL_DOCS, expectedDocs, "Expected source doc count to match configured TOTAL_DOCS");

            // Schedule coordinator outage injection, tracking timestamps with nanoTime for monotonic ordering
            var outageInjectedAt = new AtomicLong(0);
            var coordinatorReEnabledAt = new AtomicLong(0);
            var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "coordinator-outage-scheduler");
                t.setDaemon(true);
                return t;
            });
            try {
                scheduler.schedule(() -> {
                    coordinatorProxy.disable();
                    outageInjectedAt.set(System.nanoTime());
                    log.atInfo().setMessage("Coordinator disabled at ~{}s")
                        .addArgument(COORDINATOR_DISABLE_AFTER_SECONDS).log();
                    scheduler.schedule(() -> {
                        coordinatorProxy.enable();
                        coordinatorReEnabledAt.set(System.nanoTime());
                        log.atInfo().setMessage("Coordinator re-enabled after {}s restart window")
                            .addArgument(reEnableAfterSeconds).log();
                    }, reEnableAfterSeconds, TimeUnit.SECONDS);
                }, COORDINATOR_DISABLE_AFTER_SECONDS, TimeUnit.SECONDS);

                // Run RFS
                var rfsStartedAt = System.nanoTime();
                final var thrownException = new AtomicReference<Exception>();
                try {
                    runRfs(
                        tempDirSnapshot, tempDirLucene,
                        targetProxy.getProxyUriAsString(),
                        coordinatorProxy.getProxyUriAsString(),
                        testContext);
                } catch (Exception e) {
                    thrownException.set(e);
                    log.atInfo().setCause(e).setMessage("RFS threw exception").log();
                }
                var rfsEndedAt = System.nanoTime();

                // ASSERT timeline: outage was injected during RFS execution
                Assertions.assertTrue(outageInjectedAt.get() > 0, "Coordinator outage was not injected");
                Assertions.assertTrue(rfsStartedAt < outageInjectedAt.get(),
                    "RFS should have started before outage was injected");
                Assertions.assertTrue(outageInjectedAt.get() < rfsEndedAt,
                    "Outage should have been injected before RFS finished");

                // ASSERT all docs migrated to target
                var finalDocs = getDocCountFromCluster(osTargetContainer.getUrl(), INDEX_NAME, false);
                Assertions.assertEquals(expectedDocs, finalDocs, "All docs should be on target");

                // ASSERT coordinator work items reflect outcome (query coordinator directly, bypassing proxy)
                var coordinatorRecovered = coordinatorReEnabledAt.get() > 0;
                assertCoordinatorWorkItemState(osCoordinatorContainer.getUrl(), !coordinatorRecovered);

                var result = new ScenarioResult(
                    thrownException.get(),
                    outageInjectedAt.get(),
                    coordinatorReEnabledAt.get(),
                    rfsStartedAt,
                    rfsEndedAt,
                    expectedDocs,
                    finalDocs);

                log.atInfo().setMessage("Test summary: exception={}, outageAt={}ns, rfsDuration={}ns, docs={}/{}")
                    .addArgument(() -> thrownException.get() != null ? thrownException.get().getClass().getSimpleName() : "none")
                    .addArgument(outageInjectedAt.get() - rfsStartedAt)
                    .addArgument(rfsEndedAt - rfsStartedAt)
                    .addArgument(finalDocs)
                    .addArgument(expectedDocs)
                    .log();

                // Scenario specific assertions
                scenarioAssertions.accept(result);
                return result;
            } finally {
                scheduler.shutdownNow();
            }
        } finally {
            FileSystemUtils.deleteDirectories(tempDirSnapshot.toString(), tempDirLucene.toString());
        }
    }

    @SneakyThrows
    private void runRfs(Path snapshotDir, Path luceneDir,
                        String targetAddress, String coordinatorAddress,
                        DocumentMigrationTestContext testContext)
        throws RfsMigrateDocuments.NoWorkLeftException
    {
        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(SOURCE_VERSION, true);
        var sourceRepo = new FileSystemRepo(snapshotDir, fileFinder);
        var sourceResourceProvider = SnapshotReaderRegistry.getSnapshotReader(SOURCE_VERSION, sourceRepo, false);
        var extractor = SnapshotExtractor.create(SOURCE_VERSION, sourceResourceProvider, sourceRepo);

        var docTransformer = new TransformationLoader().getTransformerFactoryLoader(
            RfsMigrateDocuments.DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG);

        // Target connection
        var targetConnectionContext = ConnectionContextTestParams.builder()
            .host(targetAddress).build().toConnectionContext();
        var clientFactory = new OpenSearchClientFactory(targetConnectionContext);

        // Coordinator connection (separate from target)
        var coordinatorConnectionContext = ConnectionContextTestParams.builder()
            .host(coordinatorAddress).build().toConnectionContext();
        var coordinatorFactory = new WorkCoordinatorFactory(COORDINATOR_VERSION, SESSION_NAME);
        var workItemRef = new AtomicReference<IWorkCoordinator.WorkItemAndDuration>();

        try (var workCoordinator = coordinatorFactory.get(
            new CoordinateWorkHttpClient(coordinatorConnectionContext),
            TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
            UUID.randomUUID().toString(),
            Clock.systemUTC(),
            workItemRef::set
        )) {
            var progressCursor = new AtomicReference<WorkItemCursor>();
            RfsMigrateDocuments.runWithPipeline(
                extractor,
                clientFactory.determineVersionAndCreate(),
                SNAPSHOT_NAME,
                luceneDir,
                () -> docTransformer,
                false,
                DocumentExceptionAllowlist.empty(),
                1,              // 1 doc per bulk to slow migration (matches test timing assumptions)
                Long.MAX_VALUE,
                progressCursor,
                workCoordinator,
                Duration.ofMinutes(99),
                new org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger(workItemId -> {}),
                sourceResourceProvider.getIndexMetadata(),
                List.of(INDEX_NAME),
                testContext,
                new AtomicReference<>(),
                null,
                null);
        }
    }

    @SneakyThrows
    private void setupAndSnapshotSourceCluster(ClusterOperations sourceClusterOperations,
                                               SearchClusterContainer sourceCluster,
                                               int shardCount,
                                               String indexName,
                                               int numberOfDocs,
                                               Path snapshotDir,
                                               SnapshotTestContext testSnapshotContext) {
        sourceClusterOperations.createIndex(indexName,
            "{\"settings\":{\"index\":{\"number_of_shards\":" + shardCount + ",\"number_of_replicas\":0}}}");
        for (int i = 1; i <= numberOfDocs; i++) {
            sourceClusterOperations.createDocument(indexName, String.valueOf(i),
                "{\"name\":\"doc-" + i + "\",\"score\":" + i + "}");
        }
        sourceClusterOperations.post("/_refresh", null);

        var snapshotArgs = new CreateSnapshot.Args();
        snapshotArgs.snapshotName = SNAPSHOT_NAME;
        snapshotArgs.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
        snapshotArgs.sourceArgs.host = sourceCluster.getUrl();
        new CreateSnapshot(snapshotArgs, testSnapshotContext.createSnapshotCreateContext()).run();
        sourceCluster.copySnapshotData(snapshotDir.toString());
    }

    @SneakyThrows
    private void assertClusterReachability(String host, String clusterLabel, boolean expectedReachable) {
        var client = new RestClient(ConnectionContextTestParams.builder().host(host).build().toConnectionContext());
        try {
            var response = client.get("_cluster/health", null);
            if (expectedReachable) {
                Assertions.assertEquals(200, response.statusCode,
                    "Expected " + clusterLabel + " cluster health endpoint to be reachable");
                log.atInfo().setMessage("Verified {} cluster is reachable (status={})")
                    .addArgument(clusterLabel)
                    .addArgument(response.statusCode)
                    .log();
            } else {
                Assertions.assertNotEquals(200, response.statusCode,
                    "Expected " + clusterLabel + " cluster to be unreachable");
                log.atInfo().setMessage("Verified {} cluster is unreachable (status={})")
                    .addArgument(clusterLabel)
                    .addArgument(response.statusCode)
                    .log();
            }
        } catch (Exception e) {
            if (!expectedReachable) {
                log.atInfo().setMessage("Verified {} cluster is unreachable (exception: {})")
                    .addArgument(clusterLabel)
                    .addArgument(e.getClass().getSimpleName())
                    .log();
                return;
            }
            Assertions.fail("Expected " + clusterLabel + " cluster to be reachable: " + e.getMessage(), e);
        }
    }

    private static long getDocCountFromCluster(String host, String index, boolean failIfMissing) {
        var client = new RestClient(ConnectionContextTestParams.builder().host(host).build().toConnectionContext());
        var refresh = client.get(index + "/_refresh", null);
        if (refresh.statusCode == 404 && !failIfMissing) return 0;
        Assertions.assertEquals(200, refresh.statusCode, "Refresh failed for " + index);
        var count = client.get(index + "/_count", null);
        if (count.statusCode == 404 && !failIfMissing) return 0;
        Assertions.assertEquals(200, count.statusCode, "Count failed for " + index);
        try {
            return OBJECT_MAPPER.readTree(count.body).path("count").asLong();
        } catch (Exception e) {
            throw new RuntimeException("Failed parsing count response", e);
        }
    }

    /**
     * Asserts coordinator work item completion state after RFS exits.
     * If coordinator stayed down, expect incomplete work items; if re-enabled, expect all completed.
     */
    @SneakyThrows
    private static void assertCoordinatorWorkItemState(String coordinatorHost, boolean expectedIncomplete) {
        var client = new RestClient(ConnectionContextTestParams.builder()
            .host(coordinatorHost).build().toConnectionContext());
        var coordinatorIndexName = OpenSearchWorkCoordinator.getFinalIndexName(SESSION_NAME);
        var incompleteQuery = "{\"query\":{\"bool\":{\"must_not\":{\"exists\":{\"field\":\"completedAt\"}}}}}";

        // ASSERT coordinator index is refreshable
        Assertions.assertEquals(200, client.get(coordinatorIndexName + "/_refresh", null).statusCode,
            "Failed to refresh coordinator index");
        var countResponse = client.post(coordinatorIndexName + "/_count", incompleteQuery, null);
        // ASSERT coordinator index is queryable
        Assertions.assertEquals(200, countResponse.statusCode, "Failed to query coordinator index");

        var incompleteCount = OBJECT_MAPPER.readTree(countResponse.body).path("count").asLong();
        // ASSERT work item completion state matches expected outcome
        Assertions.assertEquals(expectedIncomplete, incompleteCount > 0,
            "Expected incomplete work items=" + expectedIncomplete + " but found " + incompleteCount + " incomplete");
    }

    private record ScenarioResult(Exception thrownException, long outageInjectedAt, long coordinatorReEnabledAt,
                                  long rfsStartedAt, long rfsEndedAt,
                                  long expectedDocs, long finalDocs) {
        boolean outageInjected() { return outageInjectedAt > 0; }
        boolean coordinatorReEnabled() { return coordinatorReEnabledAt > 0; }
    }
}
