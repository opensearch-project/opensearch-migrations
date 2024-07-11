package com.rfs;

import com.rfs.cms.ApacheHttpClient;
import com.rfs.cms.OpenSearchWorkCoordinator;
import com.rfs.cms.LeaseExpireTrigger;
import com.rfs.common.ClusterVersion;
import com.rfs.common.ConnectionDetails;
import com.rfs.common.DefaultSourceRepoAccessor;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.GlobalMetadata;
import com.rfs.common.IndexMetadata;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.RestClient;
import com.rfs.common.ShardMetadata;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.common.SourceRepo;
import com.rfs.framework.SearchClusterContainer;
import com.rfs.http.SearchClusterRequests;
import com.rfs.framework.PreloadedSearchClusterContainer;
import com.rfs.version_es_7_10.ElasticsearchConstants_ES_7_10;
import com.rfs.version_es_7_10.GlobalMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.worker.DocumentsRunner;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;
import org.opensearch.testcontainers.OpensearchContainer;
import org.slf4j.event.Level;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Tag("longTest")
@Slf4j
public class FullTest extends SourceTestBase {
    public static final String GENERATOR_BASE_IMAGE = "migrations/elasticsearch_client_test_console:latest";
    final static long TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 3600;
    final static List<SearchClusterContainer.Version> SOURCE_IMAGES = List.of(
        SearchClusterContainer.ES_V7_10_2,
        SearchClusterContainer.ES_V7_17
    );
    final static List<SearchClusterContainer.Version> TARGET_IMAGES = List.of(
        SearchClusterContainer.OS_V1_3_16,
        SearchClusterContainer.OS_V2_14_0
    );
    public static final int MAX_SHARD_SIZE_BYTES = 64 * 1024 * 1024;

    public static Stream<Arguments> makeDocumentMigrationArgs() {
        List<Object[]> sourceImageArgs = SOURCE_IMAGES.stream().map(name -> makeParamsForBase(name)).collect(Collectors.toList());
        var targetImageNames = TARGET_IMAGES.stream().map(SearchClusterContainer.Version::getImageName).collect(Collectors.toList());
        var numWorkersList = List.of(40);
        return sourceImageArgs.stream()
                .flatMap(sourceParams->
                        targetImageNames.stream().flatMap(targetImage->
                                numWorkersList.stream()
                                        .map(numWorkers->Arguments.of(numWorkers, targetImage,
                                                sourceParams[0], sourceParams[1], sourceParams[2]))));
    }

    @ParameterizedTest
    @MethodSource("makeDocumentMigrationArgs")
    public void testDocumentMigration(int numWorkers,
                                      String targetImageName,
                                      SearchClusterContainer.Version baseSourceImageVersion,
                                      String generatorImage,
                                      String[] generatorArgs)
            throws Exception
    {
        var executorService = Executors.newFixedThreadPool(numWorkers);
        final var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testMetadataMigrationContext = MetadataMigrationTestContext.factory().noOtelTracking();
        final var workCoordinationContext = WorkCoordinationTestContext.factory().withAllTracking();
        final var testDocMigrationContext =
                DocumentMigrationTestContext.factory(workCoordinationContext).withAllTracking();

        try (var esSourceContainer = new PreloadedSearchClusterContainer(baseSourceImageVersion,
                SOURCE_SERVER_ALIAS, generatorImage, generatorArgs);
             OpensearchContainer<?> osTargetContainer =
                     new OpensearchContainer<>(targetImageName)) {
            CompletableFuture.allOf(
                CompletableFuture.supplyAsync(()->{ esSourceContainer.start(); return null; }, executorService),
                CompletableFuture.supplyAsync(()->{ osTargetContainer.start(); return null; }, executorService))
                    .join();

            final var SNAPSHOT_NAME = "test_snapshot";
            final List<String> INDEX_ALLOWLIST = List.of();
            CreateSnapshot.run(
                    c -> new FileSystemSnapshotCreator(SNAPSHOT_NAME, c, SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                            testSnapshotContext.createSnapshotCreateContext()),
                    new OpenSearchClient(esSourceContainer.getUrl(), null),
                    false);
            var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
            try {
                esSourceContainer.copySnapshotData(tempDir.toString());

                var targetClient = new OpenSearchClient(osTargetContainer.getHttpHostAddress(), null);
                var sourceRepo = new FileSystemRepo(tempDir);
                migrateMetadata(sourceRepo, targetClient, SNAPSHOT_NAME, INDEX_ALLOWLIST, testMetadataMigrationContext);

                var workerFutures = new ArrayList<CompletableFuture<Void>>();
                var runCounter = new AtomicInteger();
                final var clockJitter = new Random(1);

                for (int i = 0; i < numWorkers; ++i) {
                    workerFutures.add(CompletableFuture.supplyAsync(() ->
                            migrateDocumentsSequentially(sourceRepo, SNAPSHOT_NAME, INDEX_ALLOWLIST,
                                    osTargetContainer.getHttpHostAddress(), runCounter, clockJitter, testDocMigrationContext),
                            executorService));
                }
                var thrownException = Assertions.assertThrows(ExecutionException.class, () ->
                        CompletableFuture.allOf(workerFutures.toArray(CompletableFuture[]::new)).get());
                var exceptionResults =
                        workerFutures.stream().map(cf -> {
                            try {
                                return cf.handle((v, t) ->
                                        Optional.ofNullable(t).map(Throwable::getCause).orElse(null))
                                        .get();
                            } catch (Exception e) {
                                throw Lombok.sneakyThrow(e);
                            }
                        }).filter(Objects::nonNull).collect(Collectors.toList());
                exceptionResults.forEach(e ->
                        log.atLevel(e instanceof RfsMigrateDocuments.NoWorkLeftException ? Level.INFO : Level.ERROR)
                                .setMessage(() -> "First exception for run").setCause(thrownException.getCause()).log());
                exceptionResults.forEach(e -> Assertions.assertInstanceOf(RfsMigrateDocuments.NoWorkLeftException.class, e));

                // for now, lets make sure that we got all of the
                Assertions.assertInstanceOf(RfsMigrateDocuments.NoWorkLeftException.class, thrownException.getCause(),
                        "expected at least one worker to notice that all work was completed.");
                checkClusterMigrationOnFinished(esSourceContainer, osTargetContainer, testDocMigrationContext);
                var totalCompletedWorkRuns = runCounter.get();
                Assertions.assertTrue(totalCompletedWorkRuns >= numWorkers,
                        "Expected to make more runs (" + totalCompletedWorkRuns + ") than the number of workers " +
                                "(" + numWorkers + ").  Increase the number of shards so that there is more work to do.");

                verifyWorkMetrics(testDocMigrationContext, numWorkers);
            } finally {
                deleteTree(tempDir);
            }
        } finally {
            executorService.shutdown();
        }
    }

    private void verifyWorkMetrics(DocumentMigrationTestContext rootContext, int numWorkers) {
        var workMetrics = rootContext.getWorkCoordinationContext().inMemoryInstrumentationBundle.getFinishedMetrics();
        var migrationMetrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();

        log.atInfo().setMessage(() -> "workMetrics: " + workMetrics).log();
        log.atInfo().setMessage(() -> "migrationMetrics: " + migrationMetrics).log();

        long shardCount = migrationMetrics.stream().filter(md->md.getName().startsWith("addShardWorkItem"))
                .reduce((a,b)->b).get().getLongSumData().getPoints().stream().reduce((a,b)->b).get().getValue();
        Assertions.assertTrue(shardCount > 0);
        long numWorkItemsCreated = workMetrics.stream().filter(md->md.getName().startsWith("createUnassignedWorkCount"))
                .reduce((a,b)->b).get().getLongSumData().getPoints().stream().reduce((a,b)->b).get().getValue();
        Assertions.assertEquals(numWorkItemsCreated, shardCount);
        long numItemsAssigned = workMetrics.stream().filter(md->md.getName().startsWith("nextWorkAssigned"))
                .reduce((a,b)->b).get().getLongSumData().getPoints().stream().reduce((a,b)->b).get().getValue();
        Assertions.assertEquals(numItemsAssigned, shardCount);
        long numCompleted = workMetrics.stream().filter(md->md.getName().startsWith("completeWorkCount"))
                .reduce((a,b)->b).get().getLongSumData().getPoints().stream().reduce((a,b)->b).get().getValue();
        Assertions.assertEquals(numCompleted, shardCount+1);
    }

    private void checkClusterMigrationOnFinished(SearchClusterContainer esSourceContainer,
                                                 OpensearchContainer<?> osTargetContainer,
                                                 DocumentMigrationTestContext context) {
        var targetClient = new RestClient(new ConnectionDetails(osTargetContainer.getHttpHostAddress(), null, null));
        var sourceClient = new RestClient(new ConnectionDetails(esSourceContainer.getUrl(), null, null));

        var requests = new SearchClusterRequests(context);
        var sourceMap = requests.getMapOfIndexAndDocCount(sourceClient);
        var refreshResponse = targetClient.get("_refresh", context.createUnboundRequestContext());
        Assertions.assertEquals(200, refreshResponse.code);
        var targetMap = requests.getMapOfIndexAndDocCount(targetClient);

        MatcherAssert.assertThat(targetMap, Matchers.equalTo(sourceMap));
    }

    @SneakyThrows
    private Void migrateDocumentsSequentially(FileSystemRepo sourceRepo,
                                              String snapshotName,
                                              List<String> indexAllowlist,
                                              String targetAddress,
                                              AtomicInteger runCounter,
                                              Random clockJitter,
                                              DocumentMigrationTestContext testContext) {
        for (int runNumber=0; ; ++runNumber) {
            try {
                var workResult = migrateDocumentsWithOneWorker(sourceRepo, snapshotName, indexAllowlist, targetAddress,
                        clockJitter, testContext);
                if (workResult == DocumentsRunner.CompletionStatus.NOTHING_DONE) {
                    return null;
                } else {
                    runCounter.incrementAndGet();
                }
            } catch (RfsMigrateDocuments.NoWorkLeftException e) {
                log.info("No work at all was found.  " +
                        "Presuming that work was complete and that all worker processes should terminate");
                throw e;
            } catch (Exception e) {
                log.atError().setCause(e).setMessage(()->"Caught an exception, " +
                        "but just going to run again with this worker to simulate task/container recycling").log();
            }
        }
    }

    private static class FilteredLuceneDocumentsReader extends LuceneDocumentsReader {
        private final UnaryOperator<Document> docTransformer;

        public FilteredLuceneDocumentsReader(Path luceneFilesBasePath, UnaryOperator<Document> docTransformer) {
            super(luceneFilesBasePath);
            this.docTransformer = docTransformer;
        }

        @Override
        public Flux<Document> readDocuments() {
            return super.readDocuments().map(docTransformer::apply);
        }
    }

    static class LeasePastError extends Error { }

    @SneakyThrows
    private DocumentsRunner.CompletionStatus migrateDocumentsWithOneWorker(SourceRepo sourceRepo,
                                                                           String snapshotName,
                                                                           List<String> indexAllowlist,
                                                                           String targetAddress,
                                                                           Random clockJitter,
                                                                           DocumentMigrationTestContext context)
        throws RfsMigrateDocuments.NoWorkLeftException
    {
        var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");
        var shouldThrow = new AtomicBoolean();
        try (var processManager = new LeaseExpireTrigger(workItemId->{
            log.atDebug().setMessage("Lease expired for " + workItemId + " making next document get throw").log();
            shouldThrow.set(true);
        })) {
            UnaryOperator<Document> terminatingDocumentFilter = d -> {
                if (shouldThrow.get()) {
                    throw new LeasePastError();
                }
                return d;
            };

            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor,
                    tempDir, ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES);

            SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);
            final int ms_window = 1000;
            final var nextClockShift = (int)(clockJitter.nextDouble() * ms_window)-(ms_window/2);
            log.info("nextClockShift="+nextClockShift);

            return RfsMigrateDocuments.run(path -> new FilteredLuceneDocumentsReader(path, terminatingDocumentFilter),
                    new DocumentReindexer(new OpenSearchClient(targetAddress, null)),
                    new OpenSearchWorkCoordinator(
                            new ApacheHttpClient(new URI(targetAddress)),
//                            new ReactorHttpClient(new ConnectionDetails(osTargetContainer.getHttpHostAddress(),
//                                    null, null)),
                            TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS, UUID.randomUUID().toString(),
                            Clock.offset(Clock.systemUTC(), Duration.ofMillis(nextClockShift))),
                    Duration.ofMinutes(10),
                    processManager,
                    indexMetadataFactory,
                    snapshotName,
                    indexAllowlist,
                    shardMetadataFactory,
                    unpackerFactory,
                    MAX_SHARD_SIZE_BYTES,
                    context);
        } finally {
            deleteTree(tempDir);
        }
    }

}
