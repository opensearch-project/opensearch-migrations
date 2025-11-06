package org.opensearch.migrations.bulkload;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.bulkload.common.DefaultSourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.workcoordination.CoordinateWorkHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger;
import org.opensearch.migrations.bulkload.workcoordination.WorkCoordinatorFactory;
import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.data.IndexOptions;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;
import org.opensearch.migrations.data.workloads.Workloads;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.transform.TransformationLoader;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Network;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

@Tag("isolatedTest")
@Slf4j
public class ContinuousWorkerTest extends SourceTestBase {

    private static final long DEFAULT_TIMEOUT_MINUTES = 6;
    private static final Duration DEFAULT_TEST_TIMEOUT = Duration.ofMinutes(DEFAULT_TIMEOUT_MINUTES);
    private static final String TARGET_DOCKER_HOSTNAME = "target";

    @TempDir
    Path tempDirSnapshot;

    @TempDir
    Path tempDirLucene;

    // Always run in continuous mode
    private static String[] contArgs(String sourceVersion, String... more) {
        var baseArgs = new String[] { "--continuous-mode", "--source-version", sourceVersion };
        var result = new String[baseArgs.length + more.length];
        System.arraycopy(baseArgs, 0, result, 0, baseArgs.length);
        System.arraycopy(more, 0, result, baseArgs.length, more.length);
        return result;
    }

    private static Stream<Arguments> fixedPathOnly() {
        return Stream.of(Arguments.of(
                SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_1
        ));
    }

    @SneakyThrows
    private static void waitUntilAcquiredOrTimeout(Process p, long ms) {
        // Note: This helper reads from stdout only. If relevant output appears on stderr,
        // ensure ProcessBuilder.redirectErrorStream(true) is called before starting the process.
        try (var br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            long deadline = System.currentTimeMillis() + ms;
            String line;
            while (System.currentTimeMillis() < deadline && (line = br.readLine()) != null) {
                if (line.contains("Acquired") || line.contains("Processing shard")) break;
            }
        }
    }

    // Test: Continuous worker exits with code 3 when no migration work remains
    @ParameterizedTest(name = "[no-work-left → exit=3] {0}->{1}")
    @MethodSource("fixedPathOnly")
    @Timeout(value = DEFAULT_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void exitsOnNoWorkLeft_continuousOnly(
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        try (
                var esSource = new SearchClusterContainer(sourceClusterVersion).withAccessToHost(true);
                var network  = Network.newNetwork();
                var osTarget = new SearchClusterContainer(targetClusterVersion).withAccessToHost(true).withNetwork(network)
        ) {
            // Start source and target clusters in parallel
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(esSource::start),
                    CompletableFuture.runAsync(osTarget::start)
            ).join();

            var targetOps = new ClusterOperations(osTarget);
            var healthResponse = targetOps.get("/_cluster/health?wait_for_status=yellow&timeout=30s");
            assertTrue(healthResponse.getValue().contains("yellow") || healthResponse.getValue().contains("green"),
                    "Target cluster not ready");

            createSnapshot(esSource, SNAPSHOT_NAME, testSnapshotContext);
            esSource.copySnapshotData(tempDirSnapshot.toString());

            var additionalArgs = contArgs(
                    sourceClusterVersion.getVersion().toString(),
                    "--index-allowlist", "geonames"
            );
            var pb = setupProcess(tempDirSnapshot, tempDirLucene, osTarget.getUrl(), additionalArgs);
            var process = runAndMonitorProcess(pb);

            try {
                assertTrue(process.waitFor(DEFAULT_TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "worker timed out");
                assertEquals(3, process.exitValue(), "Expected NO_WORK_LEFT (3) when no migration work remains");
            } finally {
                process.destroyForcibly();
            }
        }
    }


    // Test: Continuous worker handles SIGTERM gracefully during active migration work
    @ParameterizedTest(name = "[sigterm] {0}->{1}")
    @MethodSource("fixedPathOnly")
    @Timeout(value = DEFAULT_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void sigtermLeadsToCleanShutdownDuringActiveWork_continuousOnly(
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        try (
                var esSource = new SearchClusterContainer(sourceClusterVersion).withAccessToHost(true);
                var network  = Network.newNetwork();
                var osTarget = new SearchClusterContainer(targetClusterVersion).withAccessToHost(true).withNetwork(network)
        ) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(esSource::start),
                    CompletableFuture.runAsync(osTarget::start)
            ).join();

            var client = new OpenSearchClientFactory(
                    ConnectionContextTestParams.builder().host(esSource.getUrl()).build().toConnectionContext()
            ).determineVersionAndCreate();

            var generator = new WorkloadGenerator(client);
            var opts = new WorkloadOptions();
            opts.setTotalDocs(100);
            opts.setWorkloads(List.of(Workloads.GEONAMES));
            opts.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, 1);
            generator.generate(opts);

            var targetOps = new ClusterOperations(osTarget);
            var healthResponse = targetOps.get("/_cluster/health?wait_for_status=yellow&timeout=30s");
            assertTrue(healthResponse.getValue().contains("yellow") || healthResponse.getValue().contains("green"),
                    "Target cluster not ready");

            createSnapshot(esSource, SNAPSHOT_NAME, testSnapshotContext);
            esSource.copySnapshotData(tempDirSnapshot.toString());

            var additionalArgs = contArgs(
                    sourceClusterVersion.getVersion().toString(),
                    "--index-allowlist", "geonames",
                    "--initial-lease-duration", "PT30s"
            );
            var pb = setupProcess(tempDirSnapshot, tempDirLucene, osTarget.getUrl(), additionalArgs);

            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

            var process = pb.start();

            try {
                waitUntilAcquiredOrTimeout(process, 8000);
                // Send SIGTERM to test graceful shutdown
                process.destroy();

                assertTrue(process.waitFor(DEFAULT_TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                        "worker did not terminate after SIGTERM");
                assertNotEquals(2, process.exitValue(), "Process should not exit via lease-timeout on SIGTERM");
            } finally {
                process.destroyForcibly();
            }

        }
    }


    // Test: Worker handles lease timeout gracefully with network latency in continuous mode
    @ParameterizedTest(name = "[lease-timeout-continuous: phase1=exit=2, phase2=exit=0|3] {0}->{1}")
    @MethodSource("fixedPathOnly")
    @Timeout(value = DEFAULT_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void leaseTimeoutUnderContinuousMode_thenSuccessorRunCompletes(
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        try (
            var esSource = new SearchClusterContainer(sourceClusterVersion).withAccessToHost(true);
            var network  = Network.newNetwork();
            var osTarget = new SearchClusterContainer(targetClusterVersion)
                    .withAccessToHost(true)
                    .withNetwork(network)
                    .withNetworkAliases(TARGET_DOCKER_HOSTNAME);
            var proxy    = new ToxiProxyWrapper(network)
        ) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(esSource::start),
                    CompletableFuture.runAsync(osTarget::start)
            ).join();

            // Start proxy to simulate network latency
            proxy.start(TARGET_DOCKER_HOSTNAME, 9200);

            var targetOps = new ClusterOperations(osTarget);
            var healthResponse = targetOps.get("/_cluster/health?wait_for_status=yellow&timeout=30s");
            assertTrue(healthResponse.getValue().contains("yellow") || healthResponse.getValue().contains("green"), "Target cluster not ready");

            var sourceClusterOperations = new ClusterOperations(esSource);
            sourceClusterOperations.createIndex("geonames", "{\"settings\":{\"number_of_shards\":2}}");

            var client = new OpenSearchClientFactory(
                    ConnectionContextTestParams.builder().host(esSource.getUrl()).build().toConnectionContext()
            ).determineVersionAndCreate();

            var generator = new WorkloadGenerator(client);
            var opts = new WorkloadOptions();
            opts.setTotalDocs(3000);
            opts.setWorkloads(List.of(Workloads.GEONAMES));
            generator.generate(opts);

            createSnapshot(esSource, SNAPSHOT_NAME, testSnapshotContext);
            esSource.copySnapshotData(tempDirSnapshot.toString());

            var tp = proxy.getProxy();
            var upLatency   = tp.toxics().latency("latency-up",   ToxicDirection.UPSTREAM,   2000);
            var downLatency = tp.toxics().latency("latency-down", ToxicDirection.DOWNSTREAM, 2000);

            try {
                // Phase 1: force a timeout (expect exit code 2)
                var timeoutArgs = contArgs(sourceClusterVersion.getVersion().toString(),
                        "--index-allowlist", "geonames",
                        "--documents-per-bulk-request", "1000",
                        "--max-connections", "1",
                        "--initial-lease-duration", "PT3s");

                var pbTimeout = setupProcess(tempDirSnapshot, tempDirLucene, proxy.getProxyUriAsString(), timeoutArgs);
                var processTimeout = runAndMonitorProcess(pbTimeout);

                try {
                    assertTrue(processTimeout.waitFor(DEFAULT_TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                            "worker (timeout phase) did not terminate in allotted time");
                    assertEquals(2, processTimeout.exitValue(),
                            "Expected lease-timeout exit (2) in continuous mode under harsh latency");
                } finally {
                    processTimeout.destroyForcibly();
                }
            } finally {
                upLatency.remove();
                downLatency.remove();
            }

            // Phase 2: normal run which completes migration
            var completeArgs = contArgs(sourceClusterVersion.getVersion().toString(),
                    "--index-allowlist", "geonames",
                    "--documents-per-bulk-request", "200",
                    "--max-connections", "2",
                    "--initial-lease-duration", "PT30S");

            var pbComplete = setupProcess(tempDirSnapshot, tempDirLucene, osTarget.getUrl(), completeArgs);
            var processComplete = runAndMonitorProcess(pbComplete);

            try {
                assertTrue(processComplete.waitFor(DEFAULT_TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                        "worker (completion phase) did not terminate in allotted time");

                int exitCodeComplete = processComplete.exitValue();
                assertTrue(exitCodeComplete == 0 || exitCodeComplete == 3,
                        "Expected 0 (success) or 3 (no-work-left) on completion phase, got: " + exitCodeComplete);
            } finally {
                processComplete.destroyForcibly();
            }

            // Now the target should have all docs
            checkClusterMigrationOnFinished(esSource, osTarget,
                    DocumentMigrationTestContext.factory().noOtelTracking());
        }
    }

    // Test: Previous work item lease timer is properly canceled when processing next item
    @ParameterizedTest(name = "[timer-cancel] {0}->{1}")
    @MethodSource("fixedPathOnly")
    @Timeout(value = DEFAULT_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void previousLeaseTimerDoesNotFireDuringNextItem(
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        try (
                var sourceCluster = new SearchClusterContainer(sourceClusterVersion).withAccessToHost(true);
                var network       = Network.newNetwork();
                var targetCluster = new SearchClusterContainer(targetClusterVersion).withAccessToHost(true).withNetwork(network)
        ) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(sourceCluster::start),
                    CompletableFuture.runAsync(targetCluster::start)
            ).join();

            var client = new OpenSearchClientFactory(
                    ConnectionContextTestParams.builder().host(sourceCluster.getUrl()).build().toConnectionContext()
            ).determineVersionAndCreate();

            var generator = new WorkloadGenerator(client);
            var opts = new WorkloadOptions();
            opts.setTotalDocs(60);
            opts.setWorkloads(List.of(Workloads.GEONAMES));
            opts.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, 2);
            generator.generate(opts);

            var args = new CreateSnapshot.Args();
            args.snapshotName = SNAPSHOT_NAME;
            args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            args.sourceArgs.host = sourceCluster.getUrl();
            new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext()).run();

            sourceCluster.copySnapshotData(tempDirSnapshot.toString());

            SourceRepo sourceRepo = new FileSystemRepo(
                    tempDirSnapshot,
                    ClusterProviderRegistry.getSnapshotFileFinder(sourceClusterVersion.getVersion(), false)
            );
            var provider = ClusterProviderRegistry.getSnapshotReader(
                    sourceClusterVersion.getVersion(), sourceRepo, false);

            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(
                    repoAccessor, tempDirLucene, provider.getBufferSizeInBytes());

            Duration initialLease = Duration.ofSeconds(5);
            AtomicBoolean oldLeaseCallbackFired = new AtomicBoolean(false);

            var readerFactory = spy(new LuceneIndexReader.Factory(provider));
            when(readerFactory.getReader(any())).thenAnswer(inv -> {
                var real = (LuceneIndexReader) inv.callRealMethod();
                var spyReader = spy(real);
                when(spyReader.readDocuments(any())).thenAnswer(inv2 -> {
                    Flux<RfsLuceneDocument> flux = (Flux<RfsLuceneDocument>) inv2.callRealMethod();
                    return flux.map(doc -> {
                        if (oldLeaseCallbackFired.get()) {
                            throw new AssertionError("Old work item lease timer fired during next item!");
                        }
                        return doc;
                    });
                });
                return spyReader;
            });

            var connectionContext = ConnectionContextTestParams.builder()
                    .host(targetCluster.getUrl()).build().toConnectionContext();

            var workItemRef = new AtomicReference<IWorkCoordinator.WorkItemAndDuration>();
            var progressCursor = new AtomicReference<WorkItemCursor>();
            var coordinatorFactory = new WorkCoordinatorFactory(targetClusterVersion.getVersion(), "");

            try (var coordinator = coordinatorFactory.get(
                    new CoordinateWorkHttpClient(connectionContext),
                    RfsMigrateDocuments.TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                    UUID.randomUUID().toString(),
                    java.time.Clock.systemUTC(),
                    workItemRef::set)) {

                var docTransformer = new TransformationLoader()
                    .getTransformerFactoryLoader(RfsMigrateDocuments.DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG);

                var reindexer = new DocumentReindexer(
                        new OpenSearchClientFactory(connectionContext).determineVersionAndCreate(),
                        1000, Long.MAX_VALUE, 1, () -> docTransformer);

                // Note: not try-with-resources on purpose. We want the trigger alive for the duration of run(...) only.
                // run(...) handles cancel/reset between items.
                var processManager = new LeaseExpireTrigger(
                        wid -> {
                            log.info("Lease expired callback for {}", wid);
                            oldLeaseCallbackFired.set(true);
                        }
                );

                CompletionStatus status = RfsMigrateDocuments.run(
                        readerFactory,
                        reindexer,
                        progressCursor,
                        coordinator,
                        initialLease,
                        processManager,
                        provider.getIndexMetadata(),
                        SNAPSHOT_NAME,
                        null,
                        null,
                        List.of("geonames"),
                        provider.getShardMetadata(),
                        unpackerFactory,
                        MAX_SHARD_SIZE_BYTES,
                        DocumentMigrationTestContext.factory().noOtelTracking(),
                        new AtomicReference<>(),
                        new WorkItemTimeProvider()
                );

                assertNotEquals(CompletionStatus.NOTHING_DONE, status, "Expected processing to occur");

                Thread.sleep(1500);

                assertFalse(oldLeaseCallbackFired.get(),
                        "Previous work-item lease timer must be canceled; it fired during the next item!");
            }

            var requests = new SearchClusterRequests(DocumentMigrationTestContext.factory().noOtelTracking());
            var targetMap = requests.getMapOfIndexAndDocCount(new RestClient(
                    ConnectionContextTestParams.builder().host(targetCluster.getUrl()).build().toConnectionContext()));
            assertTrue(targetMap.containsKey("geonames"));
            assertTrue(targetMap.get("geonames") > 0);
        }
    }

    @ParameterizedTest(name = "[cont-multi-iter → exit=3] {0}->{1}")
    @MethodSource("fixedPathOnly")
    @Timeout(value = DEFAULT_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void continuousWorkerProcessesMultipleShards_thenExitsNoWorkLeft(
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        try (
                var esSource = new SearchClusterContainer(sourceClusterVersion).withAccessToHost(true);
                var network  = Network.newNetwork();
                var osTarget = new SearchClusterContainer(targetClusterVersion).withAccessToHost(true).withNetwork(network)
        ) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(esSource::start),
                    CompletableFuture.runAsync(osTarget::start)
            ).join();

            // Create multi-shard data set (2 shards) with a modest doc count
            var client = new OpenSearchClientFactory(
                    ConnectionContextTestParams.builder().host(esSource.getUrl()).build().toConnectionContext()
            ).determineVersionAndCreate();

            var generator = new WorkloadGenerator(client);
            var opts = new WorkloadOptions();
            opts.setTotalDocs(1200);
            opts.setWorkloads(List.of(Workloads.GEONAMES));
            opts.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, 2);
            generator.generate(opts);

            var snapshotArgs = new CreateSnapshot.Args();
            snapshotArgs.snapshotName = SNAPSHOT_NAME;
            snapshotArgs.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            snapshotArgs.sourceArgs.host = esSource.getUrl();
            new CreateSnapshot(snapshotArgs, testSnapshotContext.createSnapshotCreateContext()).run();

            esSource.copySnapshotData(tempDirSnapshot.toString());

            // Run CLI in continuous mode, it should iterate through all shards and then exit(3)
            var cliArgs = contArgs(sourceClusterVersion.getVersion().toString(),
                    "--index-allowlist", "geonames",
                    "--initial-lease-duration", "PT30S");
            var pb = setupProcess(tempDirSnapshot, tempDirLucene, osTarget.getUrl(), cliArgs);
            var process = runAndMonitorProcess(pb);

            try {
                assertTrue(process.waitFor(DEFAULT_TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "worker timed out");
                assertEquals(3, process.exitValue(), "Continuous worker should exit with NO_WORK_LEFT (3)");
            } finally {
                process.destroyForcibly();
            }

            // Validate data landed
            checkClusterMigrationOnFinished(esSource, osTarget,
                    DocumentMigrationTestContext.factory().noOtelTracking());
        }
    }

    @ParameterizedTest(name = "[run() no indices → NoWorkLeftException] {0}->{1}")
    @MethodSource("fixedPathOnly")
    @Timeout(value = DEFAULT_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void runReturnsNothingDone_whenShardYieldsNoDocuments(
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();

        try (
                var sourceCluster = new SearchClusterContainer(sourceClusterVersion).withAccessToHost(true);
                var network       = Network.newNetwork();
                var targetCluster = new SearchClusterContainer(targetClusterVersion).withAccessToHost(true).withNetwork(network)
        ) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(sourceCluster::start),
                    CompletableFuture.runAsync(targetCluster::start)
            ).join();

            // Create some data (content doesn't matter, we'll exclude it via allowlist)
            var client = new OpenSearchClientFactory(
                    ConnectionContextTestParams.builder().host(sourceCluster.getUrl()).build().toConnectionContext()
            ).determineVersionAndCreate();
            var opts = new WorkloadOptions();
            opts.setTotalDocs(100);
            opts.setWorkloads(List.of(Workloads.GEONAMES));
            opts.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, 1);
            new WorkloadGenerator(client).generate(opts);

            var snapshotArgs = new CreateSnapshot.Args();
            snapshotArgs.snapshotName = SNAPSHOT_NAME;
            snapshotArgs.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            snapshotArgs.sourceArgs.host = sourceCluster.getUrl();
            new CreateSnapshot(snapshotArgs, testSnapshotContext.createSnapshotCreateContext()).run();

            sourceCluster.copySnapshotData(tempDirSnapshot.toString());

            SourceRepo sourceRepo = new FileSystemRepo(
                    tempDirSnapshot,
                    ClusterProviderRegistry.getSnapshotFileFinder(sourceClusterVersion.getVersion(), false)
            );
            var provider = ClusterProviderRegistry.getSnapshotReader(
                    sourceClusterVersion.getVersion(), sourceRepo, false);

            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(
                    repoAccessor, tempDirLucene, provider.getBufferSizeInBytes());

            var readerFactory = new LuceneIndexReader.Factory(provider);
            var connectionContext = ConnectionContextTestParams.builder()
                    .host(targetCluster.getUrl()).build().toConnectionContext();
            var workItemRef = new AtomicReference<IWorkCoordinator.WorkItemAndDuration>();
            var progressCursor = new AtomicReference<WorkItemCursor>();
            var coordinatorFactory = new WorkCoordinatorFactory(targetClusterVersion.getVersion(), "");

            try (var coordinator = coordinatorFactory.get(
                    new CoordinateWorkHttpClient(connectionContext),
                    RfsMigrateDocuments.TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                    UUID.randomUUID().toString(),
                    java.time.Clock.systemUTC(),
                    workItemRef::set)) {

                var reindexer = new DocumentReindexer(
                        new OpenSearchClientFactory(connectionContext).determineVersionAndCreate(),
                        1000, Long.MAX_VALUE, 1,
                        () -> new TransformationLoader()
                                .getTransformerFactoryLoader(RfsMigrateDocuments.DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG));

                // Short lease for symmetry, not relevant to the assertion
                Duration initialLease = Duration.ofSeconds(10);
                var leaseTrigger = new LeaseExpireTrigger(wid -> {
                });

                // Use an allowlist that matches nothing -> no eligible work items -> NoWorkLeftException
                assertThrows(RfsMigrateDocuments.NoWorkLeftException.class, () -> {
                    RfsMigrateDocuments.run(
                            readerFactory,
                            reindexer,
                            progressCursor,
                            coordinator,
                            initialLease,
                            leaseTrigger,
                            provider.getIndexMetadata(),
                            SNAPSHOT_NAME,
                            null,                  // previousSnapshotName
                            null,                  // deltaMode
                            List.of("__no_such_index__"),
                            provider.getShardMetadata(),
                            unpackerFactory,
                            MAX_SHARD_SIZE_BYTES,
                            DocumentMigrationTestContext.factory().noOtelTracking(),
                            new AtomicReference<>(),
                            new WorkItemTimeProvider()
                    );
                }, "Expected NoWorkLeftException when no indices match the allowlist");
            }
        }
    }
}
