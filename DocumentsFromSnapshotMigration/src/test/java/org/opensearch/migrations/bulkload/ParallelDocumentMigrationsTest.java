package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.utils.FileSystemUtils;

import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;


@Tag("isolatedTest")
@Slf4j
public class ParallelDocumentMigrationsTest extends SourceTestBase {

    public static Stream<Arguments> makeDocumentMigrationArgs() {
        var numWorkersList = List.of(1, 3, 40);
        return numWorkersList.stream()
            .map(numWorkers -> Arguments.of(numWorkers, SearchClusterContainer.OS_LATEST)
            );
    }

    @ParameterizedTest
    @MethodSource("makeDocumentMigrationArgs")
    public void testDocumentMigration(
        int numWorkers,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        var executorService = Executors.newFixedThreadPool(numWorkers);
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory()
            .withAllTracking();

        // The source container version doesn't impact the test focus to stress work coordination store with many worker instances.
        final var sourceVersion = SearchClusterContainer.ES_V7_10_2;
        try (
            var esSourceContainer = new SearchClusterContainer(sourceVersion);
            var osTargetContainer = new SearchClusterContainer(targetVersion);
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(esSourceContainer::start, executorService),
                CompletableFuture.runAsync(osTargetContainer::start, executorService)
            ).join();

            // Populate the source cluster with data
            var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(esSourceContainer.getUrl())
                    .build()
                    .toConnectionContext());
            var client = clientFactory.determineVersionAndCreate();
            var generator = new WorkloadGenerator(client);
            generator.generate(new WorkloadOptions());

            // Create the snapshot from the source cluster
            createSnapshot(esSourceContainer, "test_snapshot", testSnapshotContext);

            final List<String> INDEX_ALLOWLIST = List.of();
            var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
            try {
                esSourceContainer.copySnapshotData(tempDir.toString());
                var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(
                        sourceVersion.getVersion(), true);
                var sourceRepo = new FileSystemRepo(tempDir, fileFinder);
                var workerFutures = new ArrayList<CompletableFuture<Integer>>();
                var runCounter = new AtomicInteger();
                final var clockJitter = new Random(1);

                for (int i = 0; i < numWorkers; ++i) {
                    workerFutures.add(
                        CompletableFuture.supplyAsync(
                            () -> migrateDocumentsSequentially(
                                sourceRepo,
                                "test_snapshot",
                                INDEX_ALLOWLIST,
                                osTargetContainer,
                                runCounter,
                                clockJitter,
                                testDocMigrationContext,
                                sourceVersion.getVersion(),
                                targetVersion.getVersion(),
                                null
                            ),
                            executorService
                        )
                    );
                }
                var thrownException = Assertions.assertThrows(
                    ExecutionException.class,
                    () -> CompletableFuture.allOf(workerFutures.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS)
                );
                var numTotalRuns = workerFutures.stream().mapToInt(cf -> {
                    try {
                        return cf.get();
                    } catch (ExecutionException e) {
                        var child = e.getCause();
                        if (child instanceof ExpectedMigrationWorkTerminationException) {
                            return ((ExpectedMigrationWorkTerminationException) child).numRuns;
                        } else {
                            throw Lombok.sneakyThrow(child);
                        }
                    } catch (InterruptedException e) {
                        throw Lombok.sneakyThrow(e);
                    }
                }).sum();

                Assertions.assertInstanceOf(
                    ExpectedMigrationWorkTerminationException.class,
                    thrownException.getCause(),
                    "expected at least one worker to notice that all work was completed."
                );
                checkClusterMigrationOnFinished(esSourceContainer, osTargetContainer, testDocMigrationContext);
                var totalCompletedWorkRuns = runCounter.get();
                Assertions.assertTrue(
                    totalCompletedWorkRuns >= numWorkers,
                    "Expected to make more runs ("
                        + totalCompletedWorkRuns
                        + ") than the number of workers "
                        + "("
                        + numWorkers
                        + ").  Increase the number of shards so that there is more work to do."
                );

                verifyWorkMetrics(testDocMigrationContext, numWorkers, numTotalRuns);
            } finally {
                FileSystemUtils.deleteDirectories(tempDir.toString());
            }
        } finally {
            executorService.shutdown();
        }
    }

    private void verifyWorkMetrics(DocumentMigrationTestContext rootContext, int numWorkers, int numRuns) {
        var workMetrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        var migrationMetrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();

        verifyCoordinatorBehavior(workMetrics, numRuns);
        verifyWorkItemCounts(migrationMetrics, workMetrics);
    }

    void assertLessThan(long a, long b) {
        Assertions.assertTrue(a < b, "expected " + a + " to be < " + b);
    }

    private void verifyCoordinatorBehavior(Collection<MetricData> metrics, int numRuns) {
        assertLessThan(getMetricValueOrZero(metrics, "workCoordinationInitializationRetries"), numRuns);
        assertLessThan(getMetricValueOrZero(metrics, "noNextWorkAvailableCount"), numRuns);
    }

    private static long getMetricValueOrZero(Collection<MetricData> metrics, String s) {
        return metrics.stream()
            .filter(md -> md.getName().equals(s))
            .reduce((a, b) -> b)
            .flatMap(md -> md.getLongSumData().getPoints().stream().reduce((a, b) -> b).map(LongPointData::getValue))
            .orElse(0L);
    }

    private static void verifyWorkItemCounts(
        Collection<MetricData> migrationMetrics,
        Collection<MetricData> workMetrics
    ) {
        long shardCount = getMetricValueOrZero(migrationMetrics, "addShardWorkItemCount");
        Assertions.assertTrue(shardCount > 0);
        long numWorkItemsCreated = getMetricValueOrZero(workMetrics, "createUnassignedWorkCount");
        Assertions.assertEquals(numWorkItemsCreated, shardCount);
        long numItemsAssigned = getMetricValueOrZero(workMetrics, "nextWorkAssignedCount");
        Assertions.assertEquals(numItemsAssigned, shardCount);
        long numCompleted = getMetricValueOrZero(workMetrics, "completeWorkCount");
        Assertions.assertEquals(shardCount + 1, numCompleted);
    }

}
