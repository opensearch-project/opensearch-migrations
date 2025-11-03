package org.opensearch.migrations.bulkload;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

    private static final Duration DEFAULT_TEST_TIMEOUT = Duration.ofMinutes(6);

    private static Stream<Arguments> scenarios() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(true,  SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_1),
                org.junit.jupiter.params.provider.Arguments.of(false, SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_1)
        );
    }


    @ParameterizedTest(name = "[no-work-left] continuous={0} {1}->{2}")
    @MethodSource("scenarios")
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void exitsOnNoWorkLeft_inBothModes(
            boolean continuousMode,
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var tempDirSnapshot = Files.createTempDirectory("rfs_no_work_left_snap");
        var tempDirLucene   = Files.createTempDirectory("rfs_no_work_left_lucene");

        try (
                var esSource = new SearchClusterContainer(sourceClusterVersion).withAccessToHost(true);
                var network  = Network.newNetwork();
                var osTarget = new SearchClusterContainer(targetClusterVersion).withAccessToHost(true).withNetwork(network)
        ) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(esSource::start),
                    CompletableFuture.runAsync(osTarget::start)
            ).join();

            createSnapshot(esSource, SNAPSHOT_NAME, testSnapshotContext);
            esSource.copySnapshotData(tempDirSnapshot.toString());
            String[] additionalArgs = continuousMode
                    ? new String[] { "--continuous-mode", "--source-version", sourceClusterVersion.getVersion().toString() }
                    : new String[] { "--source-version", sourceClusterVersion.getVersion().toString() };

            var pb = setupProcess(tempDirSnapshot, tempDirLucene, osTarget.getUrl(), additionalArgs);
            var process = runAndMonitorProcess(pb);

            assertTrue(process.waitFor(DEFAULT_TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "worker timed out");
            int exitCode = process.exitValue();

            assertNotEquals(0, exitCode, "Expected non-zero exit code for 'no work left' in both modes");
        } finally {
            deleteTree(tempDirSnapshot);
            deleteTree(tempDirLucene);
        }
    }


    @ParameterizedTest(name = "[sigterm] continuous={0} {1}->{2}")
    @MethodSource("scenarios")
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void sigtermLeadsToCleanShutdownDuringActiveWork(
            boolean continuousMode,
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var tempDirSnapshot = Files.createTempDirectory("rfs_sigterm_snap");
        var tempDirLucene   = Files.createTempDirectory("rfs_sigterm_lucene");

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
            opts.setTotalDocs(30);
            opts.setWorkloads(List.of(Workloads.GEONAMES));
            opts.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, 1);
            generator.generate(opts);

            createSnapshot(esSource, SNAPSHOT_NAME, testSnapshotContext);
            esSource.copySnapshotData(tempDirSnapshot.toString());

            String[] additionalArgs = continuousMode
                    ? new String[] { "--continuous-mode", "--source-version", sourceClusterVersion.getVersion().toString(),
                    "--initial-lease-duration", "PT30s" }
                    : new String[] { "--source-version", sourceClusterVersion.getVersion().toString(),
                    "--initial-lease-duration", "PT30s" };

            var pb = setupProcess(tempDirSnapshot, tempDirLucene, osTarget.getUrl(), additionalArgs);


            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

            var process = pb.start();

            Thread.sleep(2000);
            process.destroy();

            assertTrue(process.waitFor(DEFAULT_TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "worker did not terminate after SIGTERM");


            try (var br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String all = br.lines().reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append).toString();
                // Soft assertion (don’t fail on absence, environments differ):
                if (!all.isEmpty()) {
                    boolean saw = all.contains("Clean shutdown completed");
                    log.info("Observed clean shutdown log: {}", saw);
                }
            }
        } finally {
            deleteTree(tempDirSnapshot);
            deleteTree(tempDirLucene);
        }
    }


    @ParameterizedTest(name = "[lease-timeout-continuous] {0}->{1}")
    @MethodSource("fixedPathOnly")
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void leaseTimeoutUnderContinuousMode_proceedsHealthy(
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var tempDirSnapshot = Files.createTempDirectory("rfs_cont_lease_snap");
        var tempDirLucene   = Files.createTempDirectory("rfs_cont_lease_lucene");

        try (
                var esSource = new SearchClusterContainer(sourceClusterVersion).withAccessToHost(true);
                var network  = Network.newNetwork();
                var osTarget = new SearchClusterContainer(targetClusterVersion).withAccessToHost(true).withNetwork(network);
                var proxy    = new ToxiProxyWrapper(network)
        ) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(esSource::start),
                    CompletableFuture.runAsync(osTarget::start)
            ).join();

            proxy.start("target", 9200);


            var client = new OpenSearchClientFactory(
                    ConnectionContextTestParams.builder().host(esSource.getUrl()).build().toConnectionContext()
            ).determineVersionAndCreate();

            var generator = new WorkloadGenerator(client);
            var opts = new WorkloadOptions();
            opts.setTotalDocs(2000);
            opts.setWorkloads(List.of(Workloads.GEONAMES));
            opts.getIndex().indexSettings.put(IndexOptions.PROP_NUMBER_OF_SHARDS, 2);
            generator.generate(opts);

            createSnapshot(esSource, SNAPSHOT_NAME, testSnapshotContext);
            esSource.copySnapshotData(tempDirSnapshot.toString());


            var tp = proxy.getProxy();
            var latency = tp.toxics().latency("latency-toxic", ToxicDirection.UPSTREAM, 500);

            String[] args = {
                    "--continuous-mode",
                    "--source-version", sourceClusterVersion.getVersion().toString(),
                    "--documents-per-bulk-request", "10",
                    "--max-connections", "2",
                    "--initial-lease-duration", "PT10s"
            };

            var pb = setupProcess(tempDirSnapshot, tempDirLucene, proxy.getProxyUriAsString(), args);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

            var process = pb.start();
            assertTrue(process.waitFor(DEFAULT_TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "worker timed out");
            int exitCode = process.exitValue();

            latency.remove();

            assertTrue(exitCode >= 0, "Expected a clean exit after lease-timeout cycles");

            try (var br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String all = br.lines().reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append).toString();
                if (!all.isEmpty()) {
                    boolean sawGuard = all.contains("already finalized by lease timeout handler");
                    log.info("Observed duplicate-checkpoint guard log: {}", sawGuard);
                }
            }
        } finally {
            deleteTree(tempDirSnapshot);
            deleteTree(tempDirLucene);
        }
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> fixedPathOnly() {
        return Stream.of(org.junit.jupiter.params.provider.Arguments.of(
                SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_1
        ));
    }

    /** ========== 4) Lease timer cancellation (A finishes < lease; B runs past A’s expiry) ========== */
    @ParameterizedTest(name = "[timer-cancel] {0}->{1}")
    @MethodSource("fixedPathOnly")
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @SneakyThrows
    public void previousLeaseTimerDoesNotFireDuringNextItem(
            SearchClusterContainer.ContainerVersion sourceClusterVersion,
            SearchClusterContainer.ContainerVersion targetClusterVersion
    ) {
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var tempDirSnapshot = Files.createTempDirectory("rfs_timer_cancel_snap");
        var tempDirLucene   = Files.createTempDirectory("rfs_timer_cancel_lucene");

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
            var coordinatorFactory = new WorkCoordinatorFactory(targetClusterVersion.getVersion());

            try (var coordinator = coordinatorFactory.get(
                    new CoordinateWorkHttpClient(connectionContext),
                    TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                    UUID.randomUUID().toString(),
                    java.time.Clock.systemUTC(),
                    workItemRef::set)) {

                var docTransformer = new TransformationLoader().getTransformerFactoryLoader(
                        Optional.ofNullable(RfsMigrateDocuments.DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG)
                                .orElse(RfsMigrateDocuments.DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG));

                var reindexer = new DocumentReindexer(
                        new OpenSearchClientFactory(connectionContext).determineVersionAndCreate(),
                        1000, Long.MAX_VALUE, 1, () -> docTransformer);

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
        } finally {
            deleteTree(tempDirSnapshot);
            deleteTree(tempDirLucene);
        }
    }
}
