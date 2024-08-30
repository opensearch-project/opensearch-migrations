package com.rfs;


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

import org.apache.lucene.document.Document;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;

import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.rfs.cms.CoordinateWorkHttpClient;
import com.rfs.cms.LeaseExpireTrigger;
import com.rfs.cms.OpenSearchWorkCoordinator;
import com.rfs.common.ClusterVersion;
import com.rfs.common.DefaultSourceRepoAccessor;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.RestClient;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.common.SourceRepo;
import com.rfs.common.SourceResourceProvider;
import com.rfs.common.SourceResourceProviderFactory;
import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.framework.SearchClusterContainer;
import com.rfs.http.SearchClusterRequests;
import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.models.ShardMetadata;
import com.rfs.transformers.TransformFunctions;
import com.rfs.transformers.Transformer;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import com.rfs.worker.DocumentsRunner;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
public class SourceTestBase {
    public static final String GENERATOR_BASE_IMAGE = "migrations/elasticsearch_client_test_console:latest";
    public final static int MAX_SHARD_SIZE_BYTES = 64 * 1024 * 1024;
    public static final String SOURCE_SERVER_ALIAS = "source";
    public final static long TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 3600;

    protected static Object[] makeParamsForBase(SearchClusterContainer.Version baseSourceImage) {
        return new Object[] {
            baseSourceImage,
            GENERATOR_BASE_IMAGE,
            new String[] { "/root/runTestBenchmarks.sh", "--endpoint", "http://" + SOURCE_SERVER_ALIAS + ":9200/" } };
    }

    protected static void migrateMetadata(
        SourceRepo sourceRepo,
        OpenSearchClient targetClient,
        String snapshotName,
        List<String> legacyTemplateAllowlist,
        List<String> componentTemplateAllowlist,
        List<String> indexTemplateAllowlist,
        List<String> indexAllowlist,
        MetadataMigrationTestContext context,
        ClusterVersion sourceVersion
    ) {
        SourceResourceProvider sourceResourceProvider = SourceResourceProviderFactory.getProvider(sourceVersion);
        SnapshotRepo.Provider repoDataProvider = sourceResourceProvider.getSnapshotRepoProvider(sourceRepo);
        GlobalMetadata.Factory metadataFactory = sourceResourceProvider.getGlobalMetadataFactory(repoDataProvider);
        GlobalMetadataCreator_OS_2_11 metadataCreator = new GlobalMetadataCreator_OS_2_11(
            targetClient,
            legacyTemplateAllowlist,
            componentTemplateAllowlist,
            indexTemplateAllowlist,
            context.createMetadataMigrationContext()
        );
        Transformer transformer = TransformFunctions.getTransformer(sourceResourceProvider.getVersion(), ClusterVersion.OS_2_11, 1);
        new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();

        IndexMetadata.Factory indexMetadataFactory = sourceResourceProvider.getIndexMetadataFactory(repoDataProvider);
        IndexCreator_OS_2_11 indexCreator = new IndexCreator_OS_2_11(targetClient);
        new IndexRunner(
            snapshotName,
            indexMetadataFactory,
            indexCreator,
            transformer,
            indexAllowlist,
            context.createIndexContext()
        ).migrateIndices();
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
        ClusterVersion parserVersion
    ) {
        for (int runNumber = 1;; ++runNumber) {
            try {
                var workResult = migrateDocumentsWithOneWorker(
                    sourceRepo,
                    snapshotName,
                    indexAllowlist,
                    targetAddress,
                    clockJitter,
                    testContext,
                    parserVersion
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
                log.atError()
                    .setCause(e)
                    .setMessage(
                        () -> "Caught an exception, "
                            + "but just going to run again with this worker to simulate task/container recycling"
                    )
                    .log();
            }
        }
    }

    public static class FilteredLuceneDocumentsReader extends LuceneDocumentsReader {
        private final UnaryOperator<Document> docTransformer;

        public FilteredLuceneDocumentsReader(Path luceneFilesBasePath, boolean softDeletesPossible, String softDeletesField, UnaryOperator<Document> docTransformer) {
            super(luceneFilesBasePath, softDeletesPossible, softDeletesField);
            this.docTransformer = docTransformer;
        }

        @Override
        public Flux<Document> readDocuments() {
            return super.readDocuments().map(docTransformer::apply);
        }
    }

    static class LeasePastError extends Error {}

    @SneakyThrows
    public static DocumentsRunner.CompletionStatus migrateDocumentsWithOneWorker(
        SourceRepo sourceRepo,
        String snapshotName,
        List<String> indexAllowlist,
        String targetAddress,
        Random clockJitter,
        DocumentMigrationTestContext context,
        ClusterVersion parserVersion
    ) throws RfsMigrateDocuments.NoWorkLeftException {
        var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");
        var shouldThrow = new AtomicBoolean();
        try (var processManager = new LeaseExpireTrigger(workItemId -> {
            log.atDebug().setMessage("Lease expired for " + workItemId + " making next document get throw").log();
            shouldThrow.set(true);
        })) {
            UnaryOperator<Document> terminatingDocumentFilter = d -> {
                if (shouldThrow.get()) {
                    throw new LeasePastError();
                }
                return d;
            };

            SourceResourceProvider sourceResourceProvider = SourceResourceProviderFactory.getProvider(parserVersion);

            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(
                repoAccessor,
                tempDir,
                sourceResourceProvider.getBufferSizeInBytes()
            );

            SnapshotRepo.Provider repoDataProvider = sourceResourceProvider.getSnapshotRepoProvider(sourceRepo);
            IndexMetadata.Factory indexMetadataFactory = sourceResourceProvider.getIndexMetadataFactory(repoDataProvider);
            ShardMetadata.Factory shardMetadataFactory = sourceResourceProvider.getShardMetadataFactory(repoDataProvider);
            final int ms_window = 1000;
            final var nextClockShift = (int) (clockJitter.nextDouble() * ms_window) - (ms_window / 2);
            log.info("nextClockShift=" + nextClockShift);


            Function<Path, LuceneDocumentsReader> readerFactory = path -> new FilteredLuceneDocumentsReader(path, sourceResourceProvider.getSoftDeletesPossible(),
                sourceResourceProvider.getSoftDeletesFieldData(), terminatingDocumentFilter);

            return RfsMigrateDocuments.run(
                readerFactory,
                new DocumentReindexer(new OpenSearchClient(ConnectionContextTestParams.builder()
                    .host(targetAddress)
                    .build()
                    .toConnectionContext()), 1000, Long.MAX_VALUE, 1),
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
                indexMetadataFactory,
                snapshotName,
                indexAllowlist,
                shardMetadataFactory,
                unpackerFactory,
                MAX_SHARD_SIZE_BYTES,
                context
            );
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
