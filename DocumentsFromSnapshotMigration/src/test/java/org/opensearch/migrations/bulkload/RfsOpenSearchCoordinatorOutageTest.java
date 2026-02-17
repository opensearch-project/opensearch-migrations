package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DefaultSourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.workcoordination.CoordinateWorkHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.WorkCoordinatorFactory;
import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
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
    //   t=330s : Hard deadline for the outage scheduler thread
    private static final int COORDINATOR_DISABLE_AFTER_SECONDS = 30;
    private static final int COORDINATOR_REENABLE_AFTER_SECONDS = 120;
    private static final int TOTAL_TIMEOUT_SECONDS = 330;

    // Polling config for checking coordinator working state after RFS completes
    private static final int COORDINATOR_STATE_OBSERVATION_TIMEOUT_SECONDS = 5;
    private static final int COORDINATOR_STATE_POLL_MILLIS = 200;

    @TempDir
    Path tempRootDir;

    @Test
    @SneakyThrows
    void allDocsMigratedButCoordinatorUnavailableAtCompletion() {
        var result = runCoordinatorOutageScenario(false, 0);

        // (1/4) ASSERT : Coordinator outage executed and disabled connectivity as scheduled
        Assertions.assertTrue(result.outageInjected(), "Coordinator outage was not injected at scheduled time");

        // (2/4) ASSERT that TARGET cluster has ALL docs from SOURCE cluster
        Assertions.assertEquals(result.expectedDocs(), result.finalDocs(), "All docs should be on target");

        // (3/4) ASSERT from COORDINATOR that at least one work item should remain incomplete
        Assertions.assertTrue(result.coordinatorHasIncompleteWorkItems(),
            "Expected coordinator working state to contain incomplete work items after outage");

        // (4/4) ASSERT : RFS threw an exception because coordinator became unavailable during finalize path
        Assertions.assertNotNull(result.thrownException(),
            "Expected exception when coordinator becomes unavailable");
    }

    @Test
    @Disabled("Known limitation: current coordinator retry window is shorter than long restart duration")
    @SneakyThrows
    void allDocsMigratedButCoordinatorLongRestartsAtCompletion() {
        var result = runCoordinatorOutageScenario(true, COORDINATOR_REENABLE_AFTER_SECONDS);

        // (1/5) ASSERT : Coordinator outage thread executed and disabled connectivity as scheduled
        Assertions.assertTrue(result.outageInjected(), "Coordinator outage was not injected at scheduled time");

        // (2/5) ASSERT that COORDINATOR was RE-ENABLED as scheduled
        Assertions.assertTrue(result.coordinatorReEnabled(),
            "Coordinator was not re-enabled after long restart window");

        // (3/5) ASSERT that TARGET cluster has ALL docs from SOURCE cluster
        Assertions.assertEquals(result.expectedDocs(), result.finalDocs(), "All docs should be on target");

        // (4/5) ASSERT that RFS completed without exception
        Assertions.assertNull(result.thrownException(),
            "Expected RFS to recover and complete cleanly after coordinator returns");

        // (5/5) ASSERT that every entry in COORDINATOR State has a `completedAt` field
        Assertions.assertFalse(result.coordinatorHasIncompleteWorkItems(),
            "Expected coordinator working state to have no incomplete work items after recovery");
    }

    @SneakyThrows
    private ScenarioResult runCoordinatorOutageScenario(boolean reEnableCoordinator, int reEnableAfterSeconds) {
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

            // === ACTION : Start outage scheduler
            var testStartNanos = System.nanoTime();
            var scenarioTimeoutSeconds = TOTAL_TIMEOUT_SECONDS + (reEnableCoordinator ? reEnableAfterSeconds : 0);
            var totalDeadlineNanos = testStartNanos + TimeUnit.SECONDS.toNanos(scenarioTimeoutSeconds);
            var outageInjected = new AtomicBoolean(false);
            var coordinatorReEnabled = new AtomicBoolean(false);
            var outageThreadException = new AtomicReference<Throwable>();

            var outageThread = new Thread(() -> {
                try {
                    manageCoordinatorOutageWindow(
                        coordinatorProxy, testStartNanos, totalDeadlineNanos, outageInjected,
                        coordinatorReEnabled, reEnableCoordinator, reEnableAfterSeconds);
                } catch (Throwable t) {
                    outageThreadException.set(t);
                    log.atError().setCause(t).setMessage("Outage scheduler thread failed").log();
                }
            }, "rfs-coordinator-outage-scheduler");
            outageThread.start();

            // === ACTION : Run RFS in-process with separate coordinator and target connections
            final var thrownException = new AtomicReference<Exception>();
            try {
                runRfsInProcess(
                    tempDirSnapshot, tempDirLucene,
                    targetProxy.getProxyUriAsString(),
                    coordinatorProxy.getProxyUriAsString(),
                    testContext);
            } catch (Exception e) {
                thrownException.set(e);
                log.atInfo().setCause(e).setMessage("RFS threw exception (expected in outage scenario)").log();
            }

            // === ACTION : Wait for outage scheduler thread to finish after RFS exits
            outageThread.join(TimeUnit.SECONDS.toMillis(reEnableCoordinator ? reEnableAfterSeconds + 10 : 5));
            Assertions.assertFalse(outageThread.isAlive(),
                "Outage scheduler thread did not finish. Outage may not have been injected");
            if (outageThreadException.get() != null) {
                Assertions.fail("Outage scheduler thread failed", outageThreadException.get());
            }

            var finalDocs = getDocCountFromCluster(osTargetContainer.getUrl(), INDEX_NAME, false);
            var coordinatorHasIncompleteWorkItems = waitForCoordinatorIncompleteWorkItems(
                osCoordinatorContainer.getUrl(), true);

            log.atInfo().setMessage("Test summary: exception={}, outageInjected={}, coordinatorReEnabled={}, docs={}/{}")
                .addArgument(() -> thrownException.get() != null ? thrownException.get().getClass().getSimpleName() : "none")
                .addArgument(outageInjected.get())
                .addArgument(coordinatorReEnabled.get())
                .addArgument(finalDocs)
                .addArgument(expectedDocs)
                .log();

            return new ScenarioResult(
                thrownException.get(),
                outageInjected.get(),
                coordinatorReEnabled.get(),
                expectedDocs,
                finalDocs,
                coordinatorHasIncompleteWorkItems);
        } finally {
            FileSystemUtils.deleteDirectories(tempDirSnapshot.toString(), tempDirLucene.toString());
        }
    }

    @SneakyThrows
    private void runRfsInProcess(Path snapshotDir, Path luceneDir,
                                 String targetAddress, String coordinatorAddress,
                                 DocumentMigrationTestContext testContext)
        throws RfsMigrateDocuments.NoWorkLeftException
    {
        var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(SOURCE_VERSION, true);
        var sourceRepo = new FileSystemRepo(snapshotDir, fileFinder);
        var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(SOURCE_VERSION, sourceRepo, false);
        var repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
        var unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor, luceneDir);
        var readerFactory = new LuceneIndexReader.Factory(sourceResourceProvider);

        var docTransformer = new TransformationLoader().getTransformerFactoryLoader(
            RfsMigrateDocuments.DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG);

        // Target connection
        var targetConnectionContext = ConnectionContextTestParams.builder()
            .host(targetAddress).build().toConnectionContext();
        var clientFactory = new OpenSearchClientFactory(targetConnectionContext);
        var reindexer = new DocumentReindexer(
            clientFactory.determineVersionAndCreate(), 1, Long.MAX_VALUE, 1,
            () -> docTransformer, false);

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
            RfsMigrateDocuments.run(
                readerFactory,
                reindexer,
                progressCursor,
                workCoordinator,
                Duration.ofMinutes(99),
                new org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger(workItemId -> {}),
                sourceResourceProvider.getIndexMetadata(),
                SNAPSHOT_NAME,
                null,
                null,
                List.of(INDEX_NAME),
                sourceResourceProvider.getShardMetadata(),
                unpackerFactory,
                MAX_SHARD_SIZE_BYTES,
                testContext,
                new AtomicReference<>(),
                new WorkItemTimeProvider());
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
    private void manageCoordinatorOutageWindow(ToxiProxyWrapper coordinatorProxy, long testStartNanos,
                                               long totalDeadlineNanos, AtomicBoolean outageInjected,
                                               AtomicBoolean coordinatorReEnabled,
                                               boolean reEnableCoordinator, int reEnableAfterSeconds) {
        // Wait until elapsed test time reaches configured outage trigger
        while (System.nanoTime() - testStartNanos < TimeUnit.SECONDS.toNanos(COORDINATOR_DISABLE_AFTER_SECONDS)) {
            if (System.nanoTime() >= totalDeadlineNanos) {
                Assertions.fail("Total timeout reached before scheduled coordinator outage action");
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }

        // Disable coordinator connectivity through the proxy
        coordinatorProxy.disable();

        // Verify coordinator is actually unreachable after disable
        assertClusterReachability(coordinatorProxy.getProxyUriAsString(), "coordinator", false);

        // Record that outage was injected for test assertions
        outageInjected.set(true);
        log.atInfo().setMessage("Coordinator disabled at ~{}s")
            .addArgument(COORDINATOR_DISABLE_AFTER_SECONDS)
            .log();

        if (!reEnableCoordinator) {
            return;
        }

        // Wait additional restart window, then bring coordinator back.
        while (System.nanoTime() - testStartNanos <
            TimeUnit.SECONDS.toNanos(COORDINATOR_DISABLE_AFTER_SECONDS + reEnableAfterSeconds)) {
            if (System.nanoTime() >= totalDeadlineNanos) {
                Assertions.fail("Total timeout reached before scheduled coordinator re-enable action");
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }

        coordinatorProxy.enable();
        assertClusterReachability(coordinatorProxy.getProxyUriAsString(), "coordinator", true);
        coordinatorReEnabled.set(true);
        log.atInfo().setMessage("Coordinator re-enabled after {}s restart window")
            .addArgument(reEnableAfterSeconds)
            .log();
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

    @SneakyThrows
    private static boolean waitForCoordinatorIncompleteWorkItems(String coordinatorHost, boolean expectedIncomplete) {
        var client = new RestClient(ConnectionContextTestParams.builder()
            .host(coordinatorHost).build().toConnectionContext());
        var coordinatorIndexName = OpenSearchWorkCoordinator.getFinalIndexName(SESSION_NAME);
        var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(COORDINATOR_STATE_OBSERVATION_TIMEOUT_SECONDS);

        while (System.nanoTime() < deadlineNanos) {
            var refreshResponse = client.get(coordinatorIndexName + "/_refresh", null);
            if (refreshResponse.statusCode == 200) {
                var searchResponse = client.get(coordinatorIndexName + "/_search", null);
                if (searchResponse.statusCode == 200) {
                    var hasIncomplete = hasIncompleteWorkItems(searchResponse.body);
                    if (hasIncomplete == expectedIncomplete) {
                        return hasIncomplete;
                    }
                }
            }
            TimeUnit.MILLISECONDS.sleep(COORDINATOR_STATE_POLL_MILLIS);
        }
        Assertions.fail("Timed out waiting for coordinator working state to reach expected incomplete="
            + expectedIncomplete + " within " + COORDINATOR_STATE_OBSERVATION_TIMEOUT_SECONDS + "s");
        return false; // unreachable
    }

    private static boolean hasIncompleteWorkItems(String searchBody) {
        if (searchBody == null || searchBody.isBlank()) {
            return false;
        }
        try {
            var hits = OBJECT_MAPPER.readTree(searchBody).path("hits").path("hits");
            for (var hit : hits) {
                var source = hit.path("_source");
                if (source.path("completedAt").isMissingNode() || source.path("completedAt").isNull()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Failed parsing coordinator working state response", e);
        }
    }

    private record ScenarioResult(Exception thrownException, boolean outageInjected, boolean coordinatorReEnabled,
                                  long expectedDocs, long finalDocs,
                                  boolean coordinatorHasIncompleteWorkItems) {}
}
