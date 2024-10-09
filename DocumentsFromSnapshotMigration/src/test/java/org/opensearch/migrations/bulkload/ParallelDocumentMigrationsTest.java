package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.framework.PreloadedSearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

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

    static final List<SearchClusterContainer.ContainerVersion> SOURCE_IMAGES = List.of(
        SearchClusterContainer.ES_V7_10_2
    );
    static final List<SearchClusterContainer.ContainerVersion> TARGET_IMAGES = List.of(SearchClusterContainer.OS_V2_14_0);

    public static Stream<Arguments> makeDocumentMigrationArgs() {
        List<Object[]> sourceImageArgs = SOURCE_IMAGES.stream()
            .map(SourceTestBase::makeParamsForBase)
            .collect(Collectors.toList());
        var targetImageNames = TARGET_IMAGES.stream()
            .collect(Collectors.toList());
        var numWorkersList = List.of(1);
        var compressionEnabledList = List.of(true);
        return sourceImageArgs.stream()
            .flatMap(
                sourceParams -> targetImageNames.stream()
                    .flatMap(
                        targetImage -> numWorkersList.stream()
                            .flatMap(numWorkers -> compressionEnabledList.stream().map(compression -> Arguments.of(
                                    numWorkers,
                                    targetImage,
                                    sourceParams[0],
                                    sourceParams[1],
                                    sourceParams[2],
                                    compression
                                ))
                            )
                    )
            );
    }

    @ParameterizedTest
    @MethodSource("makeDocumentMigrationArgs")
    public void testDocumentMigration(
        int numWorkers,
        SearchClusterContainer.ContainerVersion targetVersion,
        SearchClusterContainer.ContainerVersion baseSourceImageVersion,
        String generatorImage,
        String[] generatorArgs,
        boolean compressionEnabled
    ) throws Exception {
        var executorService = Executors.newFixedThreadPool(numWorkers);
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory()
            .withAllTracking();

        try (
            var esSourceContainer = new PreloadedSearchClusterContainer(
                baseSourceImageVersion,
                SOURCE_SERVER_ALIAS,
                generatorImage,
                generatorArgs
            );
            SearchClusterContainer osTargetContainer = new SearchClusterContainer(targetVersion);
        ) {
            CompletableFuture.allOf(CompletableFuture.supplyAsync(() -> {
                esSourceContainer.start();
                return null;
            }, executorService), CompletableFuture.supplyAsync(() -> {
                osTargetContainer.start();
                return null;
            }, executorService)).join();

            var args = new CreateSnapshot.Args();
            args.snapshotName = "test_snapshot";
            args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
            args.sourceArgs.host = esSourceContainer.getUrl();

            var ops = new ClusterOperations(esSourceContainer.getUrl());
            System.err.println("geonames:\n" + ops.get("/geonames/_search?size=1").getValue());
            System.err.println("sonested:\n" + ops.get("/sonested/_search?size=1").getValue());
            System.err.println("logs-211998:\n" + ops.get("/logs-211998/_search?size=1").getValue());
            System.err.println("nyc_taxis:\n" + ops.get("/nyc_taxis/_search?size=1").getValue());
            var cat = ops.get("/_cat/indices?v");
            System.err.println("indices:\n" + cat.getValue());

            var snapshotCreator = new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext());
            snapshotCreator.run();


            final List<String> INDEX_ALLOWLIST = List.of();
            var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
            try {
                esSourceContainer.copySnapshotData(tempDir.toString());
                var sourceRepo = new FileSystemRepo(tempDir);
                var workerFutures = new ArrayList<CompletableFuture<Integer>>();
                var runCounter = new AtomicInteger();
                final var clockJitter = new Random(1);

                for (int i = 0; i < numWorkers; ++i) {
                    workerFutures.add(
                        CompletableFuture.supplyAsync(
                            () -> migrateDocumentsSequentially(
                                sourceRepo,
                                args.snapshotName,
                                INDEX_ALLOWLIST,
                                osTargetContainer.getUrl(),
                                runCounter,
                                clockJitter,
                                testDocMigrationContext,
                                baseSourceImageVersion.getVersion(),
                                compressionEnabled
                            ),
                            executorService
                        )
                    );
                }
                var thrownException = Assertions.assertThrows(
                    ExecutionException.class,
                    () -> CompletableFuture.allOf(workerFutures.toArray(CompletableFuture[]::new)).get()
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
                deleteTree(tempDir);
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
        Assertions.assertEquals(numCompleted, shardCount + 1);
    }

}
