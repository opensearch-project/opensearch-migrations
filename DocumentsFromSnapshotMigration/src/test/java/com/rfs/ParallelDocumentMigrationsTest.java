package com.rfs;

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

import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import com.rfs.common.FileSystemRepo;
import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.framework.PreloadedSearchClusterContainer;
import com.rfs.framework.SearchClusterContainer;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

@Tag("longTest")
@Slf4j
public class ParallelDocumentMigrationsTest extends SourceTestBase {
    final static long TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 3600;
    final static List<SearchClusterContainer.Version> SOURCE_IMAGES = List.of(
        SearchClusterContainer.ES_V7_10_2,
        SearchClusterContainer.ES_V7_17
    );
    final static List<SearchClusterContainer.Version> TARGET_IMAGES = List.of(SearchClusterContainer.OS_V2_14_0);
    public static final int MAX_SHARD_SIZE_BYTES = 64 * 1024 * 1024;

    public static Stream<Arguments> makeDocumentMigrationArgs() {
        List<Object[]> sourceImageArgs = SOURCE_IMAGES.stream()
            .map(name -> makeParamsForBase(name))
            .collect(Collectors.toList());
        var targetImageNames = TARGET_IMAGES.stream()
            .collect(Collectors.toList());
        var numWorkersList = List.of(1, 3, 40);
        return sourceImageArgs.stream()
            .flatMap(
                sourceParams -> targetImageNames.stream()
                    .flatMap(
                        targetImage -> numWorkersList.stream()
                            .map(
                                numWorkers -> Arguments.of(
                                    numWorkers,
                                    targetImage,
                                    sourceParams[0],
                                    sourceParams[1],
                                    sourceParams[2]
                                )
                            )
                    )
            );
    }

    @ParameterizedTest
    @MethodSource("makeDocumentMigrationArgs")
    public void testDocumentMigration(
        int numWorkers,
        SearchClusterContainer.Version targetVersion,
        SearchClusterContainer.Version baseSourceImageVersion,
        String generatorImage,
        String[] generatorArgs
    ) throws Exception {
        var executorService = Executors.newFixedThreadPool(numWorkers);
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testMetadataMigrationContext = MetadataMigrationTestContext.factory().noOtelTracking();
        final var workCoordinationContext = WorkCoordinationTestContext.factory().withAllTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory(workCoordinationContext)
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

            final var SNAPSHOT_NAME = "test_snapshot";
            final List<String> INDEX_ALLOWLIST = List.of();
            CreateSnapshot.run(
                c -> new FileSystemSnapshotCreator(
                    SNAPSHOT_NAME,
                    c,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                    testSnapshotContext.createSnapshotCreateContext()
                ),
                new OpenSearchClient(ConnectionContextTestParams.builder()
                    .host(esSourceContainer.getUrl())
                    .build()
                    .toConnectionContext()),
                false
            );
            var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
            try {
                esSourceContainer.copySnapshotData(tempDir.toString());

                var targetClient = new OpenSearchClient(ConnectionContextTestParams.builder()
                    .host(esSourceContainer.getUrl())
                    .build()
                    .toConnectionContext());
                var sourceRepo = new FileSystemRepo(tempDir);
                migrateMetadata(sourceRepo, targetClient, SNAPSHOT_NAME, List.of(), List.of(), List.of(), INDEX_ALLOWLIST, testMetadataMigrationContext, baseSourceImageVersion.getSourceVersion());

                var workerFutures = new ArrayList<CompletableFuture<Integer>>();
                var runCounter = new AtomicInteger();
                final var clockJitter = new Random(1);

                for (int i = 0; i < numWorkers; ++i) {
                    workerFutures.add(
                        CompletableFuture.supplyAsync(
                            () -> migrateDocumentsSequentially(
                                sourceRepo,
                                SNAPSHOT_NAME,
                                INDEX_ALLOWLIST,
                                osTargetContainer.getUrl(),
                                runCounter,
                                clockJitter,
                                testDocMigrationContext,
                                baseSourceImageVersion.getSourceVersion()
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
        var workMetrics = rootContext.getWorkCoordinationContext().inMemoryInstrumentationBundle.getFinishedMetrics();
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
