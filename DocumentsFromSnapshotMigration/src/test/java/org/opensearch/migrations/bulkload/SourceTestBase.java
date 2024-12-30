package org.opensearch.migrations.bulkload;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DefaultSourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.LuceneDocumentsReader;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.workcoordination.CoordinateWorkHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.DocumentsRunner;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.transform.TransformationLoader;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import reactor.core.publisher.Flux;

@Slf4j
public class SourceTestBase {
    public static final String GENERATOR_BASE_IMAGE = "migrations/elasticsearch_client_test_console:latest";
    public static final int MAX_SHARD_SIZE_BYTES = 64 * 1024 * 1024;
    public static final String SOURCE_SERVER_ALIAS = "source";
    public static final long TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 3600;

    protected static Object[] makeParamsForBase(SearchClusterContainer.ContainerVersion baseSourceImage) {
        return new Object[]{
            baseSourceImage,
            GENERATOR_BASE_IMAGE,
            new String[]{"/root/runTestBenchmarks.sh", "--endpoint", "http://" + SOURCE_SERVER_ALIAS + ":9200/"}};
    }

    @AllArgsConstructor
    public static class ExpectedMigrationWorkTerminationException extends RuntimeException {
        public final RfsMigrateDocuments.NoWorkLeftException exception;
        public final int numRuns;
    }

    public static void checkClusterMigrationOnFinished(
        SearchClusterContainer esSourceContainer,
        SearchClusterContainer osTargetContainer,
        DocumentMigrationTestContext context
    ) {
        var targetClient = new RestClient(ConnectionContextTestParams.builder()
            .host(osTargetContainer.getUrl())
            .build()
            .toConnectionContext()
        );
        var sourceClient = new RestClient(ConnectionContextTestParams.builder()
            .host(esSourceContainer.getUrl())
            .build()
            .toConnectionContext()
        );

        var requests = new SearchClusterRequests(context);
        var sourceMap = requests.getMapOfIndexAndDocCount(sourceClient);
        var refreshResponse = targetClient.get("_refresh", context.createUnboundRequestContext());
        Assertions.assertEquals(200, refreshResponse.statusCode);
        var targetMap = requests.getMapOfIndexAndDocCount(targetClient);

        MatcherAssert.assertThat(targetMap, Matchers.equalTo(sourceMap));
    }

    public static int migrateDocumentsSequentially(
        FileSystemRepo sourceRepo,
        String snapshotName,
        List<String> indexAllowlist,
        String targetAddress,
        AtomicInteger runCounter,
        Random clockJitter,
        DocumentMigrationTestContext testContext,
        Version version,
        boolean compressionEnabled
    ) {
        for (int runNumber = 1; ; ++runNumber) {
            try {
                var workResult = migrateDocumentsWithOneWorker(
                    sourceRepo,
                    snapshotName,
                    indexAllowlist,
                    targetAddress,
                    clockJitter,
                    testContext,
                    version,
                    compressionEnabled
                );
                if (workResult == DocumentsRunner.CompletionStatus.NOTHING_DONE) {
                    return runNumber;
                } else {
                    runCounter.incrementAndGet();
                }
            } catch (RfsMigrateDocuments.NoWorkLeftException e) {
                log.info(
                    "No work at all was found.  "
                        + "Presuming that work was complete and that all worker processes should terminate"
                );
                throw new ExpectedMigrationWorkTerminationException(e, runNumber);
            } catch (Exception e) {
                log.atError().setCause(e).setMessage("Caught an exception, " +
                    "but just going to run again with this worker to simulate task/container recycling").log();
            }
        }
    }

    public static class FilteredLuceneDocumentsReader extends LuceneDocumentsReader {
        private final UnaryOperator<RfsLuceneDocument> docTransformer;

        public FilteredLuceneDocumentsReader(Path luceneFilesBasePath, boolean softDeletesPossible, String softDeletesField, UnaryOperator<RfsLuceneDocument> docTransformer) {
            super(luceneFilesBasePath, softDeletesPossible, softDeletesField);
            this.docTransformer = docTransformer;
        }

        @Override
        public Flux<RfsLuceneDocument> readDocuments(int startSegmentIndex, int startDoc) {
            return super.readDocuments(startSegmentIndex, startDoc).map(docTransformer::apply);
        }
    }

    static class LeasePastError extends Error {
    }

    @SneakyThrows
    public static DocumentsRunner.CompletionStatus migrateDocumentsWithOneWorker(
        SourceRepo sourceRepo,
        String snapshotName,
        List<String> indexAllowlist,
        String targetAddress,
        Random clockJitter,
        DocumentMigrationTestContext context,
        Version version,
        boolean compressionEnabled
    ) throws RfsMigrateDocuments.NoWorkLeftException {
        var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");
        var shouldThrow = new AtomicBoolean();
        try (var processManager = new LeaseExpireTrigger(workItemId -> {
            log.atDebug().setMessage("Lease expired for {} making next document get throw")
                .addArgument(workItemId).log();
            shouldThrow.set(true);
        })) {
            UnaryOperator<RfsLuceneDocument> terminatingDocumentFilter = d -> {
                if (shouldThrow.get()) {
                    throw new LeasePastError();
                }
                return d;
            };

            var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(version, sourceRepo);

            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(
                repoAccessor,
                tempDir,
                sourceResourceProvider.getBufferSizeInBytes()
            );

            final int ms_window = 1000;
            final var nextClockShift = (int) (clockJitter.nextDouble() * ms_window) - (ms_window / 2);
            log.info("nextClockShift=" + nextClockShift);

            Function<Path, LuceneDocumentsReader> readerFactory = path -> new FilteredLuceneDocumentsReader(path, sourceResourceProvider.getSoftDeletesPossible(),
                sourceResourceProvider.getSoftDeletesFieldData(), terminatingDocumentFilter);

            var defaultDocTransformer = new TransformationLoader().getTransformerFactoryLoader(RfsMigrateDocuments.DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG);

            try (var workCoordinator = new OpenSearchWorkCoordinator(
                new CoordinateWorkHttpClient(ConnectionContextTestParams.builder()
                    .host(targetAddress)
                    .build()
                    .toConnectionContext()),
                TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                UUID.randomUUID().toString(),
                Clock.offset(Clock.systemUTC(), Duration.ofMillis(nextClockShift))
            )) {
                return RfsMigrateDocuments.run(
                    readerFactory,
                    new DocumentReindexer(new OpenSearchClient(ConnectionContextTestParams.builder()
                        .host(targetAddress)
                        .compressionEnabled(compressionEnabled)
                        .build()
                        .toConnectionContext()), 1000, Long.MAX_VALUE, 1, defaultDocTransformer),
                    new OpenSearchWorkCoordinator(
                        new CoordinateWorkHttpClient(ConnectionContextTestParams.builder()
                            .host(targetAddress)
                            .build()
                            .toConnectionContext()),
                        TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                        UUID.randomUUID().toString(),
                        Clock.offset(Clock.systemUTC(), Duration.ofMillis(nextClockShift))
                    ),
                    Duration.ofMinutes(10),
                    processManager,
                    sourceResourceProvider.getIndexMetadata(),
                    snapshotName,
                    indexAllowlist,
                    sourceResourceProvider.getShardMetadata(),
                    unpackerFactory,
                    MAX_SHARD_SIZE_BYTES,
                    context);
            }
        } finally {
            deleteTree(tempDir);
        }
    }

    public static void deleteTree(Path path) throws IOException {
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
            });
        }
    }
}
