package com.rfs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.lucene.document.Document;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.opensearch.testcontainers.OpensearchContainer;

import com.rfs.cms.ApacheHttpClient;
import com.rfs.cms.LeaseExpireTrigger;
import com.rfs.cms.OpenSearchWorkCoordinator;
import com.rfs.common.ClusterVersion;
import com.rfs.common.ConnectionDetails;
import com.rfs.common.DefaultSourceRepoAccessor;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.RestClient;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.common.SourceRepo;
import com.rfs.framework.PreloadedSearchClusterContainer;
import com.rfs.framework.SearchClusterContainer;
import com.rfs.http.SearchClusterRequests;
import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.models.ShardMetadata;
import com.rfs.transformers.TransformFunctions;
import com.rfs.transformers.Transformer;
import com.rfs.version_es_7_10.ElasticsearchConstants_ES_7_10;
import com.rfs.version_es_7_10.GlobalMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import com.rfs.worker.DocumentsRunner;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import reactor.core.publisher.Flux;

@Tag("longTest")
@Slf4j
public class FullTest {
    public static final String GENERATOR_BASE_IMAGE = "migrations/elasticsearch_client_test_console:latest";
    final static long TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 3600;
    final static Pattern CAT_INDICES_INDEX_COUNT_PATTERN = Pattern.compile(
        "(?:\\S+\\s+){2}(\\S+)\\s+(?:\\S+\\s+){3}(\\S+)"
    );
    final static List<SearchClusterContainer.Version> SOURCE_IMAGES = List.of(
        SearchClusterContainer.ES_V7_10_2,
        SearchClusterContainer.ES_V7_17
    );
    final static List<SearchClusterContainer.Version> TARGET_IMAGES = List.of(SearchClusterContainer.OS_V2_14_0);
    public static final String SOURCE_SERVER_ALIAS = "source";
    public static final int MAX_SHARD_SIZE_BYTES = 64 * 1024 * 1024;

    public static Stream<Arguments> makeDocumentMigrationArgs() {
        List<Object[]> sourceImageArgs = SOURCE_IMAGES.stream()
            .map(name -> makeParamsForBase(name))
            .collect(Collectors.toList());
        var targetImageNames = TARGET_IMAGES.stream()
            .map(SearchClusterContainer.Version::getImageName)
            .collect(Collectors.toList());
        var numWorkers = List.of(1, 3, 40);
        return sourceImageArgs.stream()
            .flatMap(
                a -> targetImageNames.stream()
                    .flatMap(b -> numWorkers.stream().map(c -> Arguments.of(a[0], a[1], a[2], b, c)))
            );
    }

    private static Object[] makeParamsForBase(SearchClusterContainer.Version baseSourceImage) {
        return new Object[] {
            baseSourceImage,
            GENERATOR_BASE_IMAGE,
            new String[] { "/root/runTestBenchmarks.sh", "--endpoint", "http://" + SOURCE_SERVER_ALIAS + ":9200/" } };
    }

    @ParameterizedTest
    @MethodSource("makeDocumentMigrationArgs")
    public void testDocumentMigration(
        SearchClusterContainer.Version baseSourceImageVersion,
        String generatorImage,
        String[] generatorArgs,
        String targetImageName,
        int numWorkers
    ) throws Exception {

        try (
            var esSourceContainer = new PreloadedSearchClusterContainer(
                baseSourceImageVersion,
                SOURCE_SERVER_ALIAS,
                generatorImage,
                generatorArgs
            );
            OpensearchContainer<?> osTargetContainer = new OpensearchContainer<>(targetImageName)
        ) {
            esSourceContainer.start();
            osTargetContainer.start();

            final var SNAPSHOT_NAME = "test_snapshot";
            final List<String> INDEX_ALLOWLIST = List.of();
            CreateSnapshot.run(
                c -> new FileSystemSnapshotCreator(SNAPSHOT_NAME, c, SearchClusterContainer.CLUSTER_SNAPSHOT_DIR),
                new OpenSearchClient(esSourceContainer.getUrl(), null),
                false
            );
            var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
            try {
                esSourceContainer.copySnapshotData(tempDir.toString());

                var targetClient = new OpenSearchClient(osTargetContainer.getHttpHostAddress(), null);
                var sourceRepo = new FileSystemRepo(tempDir);
                migrateMetadata(sourceRepo, targetClient, SNAPSHOT_NAME, INDEX_ALLOWLIST);

                var workerFutures = new ArrayList<CompletableFuture<Void>>();
                var runCounter = new AtomicInteger();
                final var clockJitter = new Random(1);
                for (int i = 0; i < numWorkers; ++i) {
                    workerFutures.add(
                        CompletableFuture.supplyAsync(
                            () -> migrateDocumentsSequentially(
                                sourceRepo,
                                SNAPSHOT_NAME,
                                INDEX_ALLOWLIST,
                                osTargetContainer.getHttpHostAddress(),
                                runCounter,
                                clockJitter
                            )
                        )
                    );
                }
                var thrownException = Assertions.assertThrows(
                    ExecutionException.class,
                    () -> CompletableFuture.allOf(workerFutures.toArray(CompletableFuture[]::new)).get()
                );
                var exceptionResults = workerFutures.stream().map(cf -> {
                    try {
                        return cf.handle((v, t) -> Optional.ofNullable(t).map(Throwable::getCause).orElse(null)).get();
                    } catch (Exception e) {
                        throw Lombok.sneakyThrow(e);
                    }
                }).filter(Objects::nonNull).collect(Collectors.toList());
                exceptionResults.forEach(
                    e -> log.atLevel(e instanceof RfsMigrateDocuments.NoWorkLeftException ? Level.INFO : Level.ERROR)
                        .setMessage(() -> "First exception for run")
                        .setCause(thrownException.getCause())
                        .log()
                );
                exceptionResults.forEach(
                    e -> Assertions.assertInstanceOf(RfsMigrateDocuments.NoWorkLeftException.class, e)
                );

                // for now, lets make sure that we got all of the
                Assertions.assertInstanceOf(
                    RfsMigrateDocuments.NoWorkLeftException.class,
                    thrownException.getCause(),
                    "expected at least one worker to notice that all work was completed."
                );
                checkClusterMigrationOnFinished(esSourceContainer, osTargetContainer);
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
            } finally {
                deleteTree(tempDir);
            }
        }
    }

    private void checkClusterMigrationOnFinished(
        SearchClusterContainer esSourceContainer,
        OpensearchContainer<?> osTargetContainer
    ) {
        var targetClient = new RestClient(new ConnectionDetails(osTargetContainer.getHttpHostAddress(), null, null));
        var sourceClient = new RestClient(new ConnectionDetails(esSourceContainer.getUrl(), null, null));

        var requests = new SearchClusterRequests();
        var sourceMap = requests.getMapOfIndexAndDocCount(sourceClient);
        var refreshResponse = targetClient.get("_refresh");
        Assertions.assertEquals(200, refreshResponse.code);
        var targetMap = requests.getMapOfIndexAndDocCount(targetClient);

        MatcherAssert.assertThat(targetMap, Matchers.equalTo(sourceMap));
    }

    @SneakyThrows
    private Void migrateDocumentsSequentially(
        FileSystemRepo sourceRepo,
        String snapshotName,
        List<String> indexAllowlist,
        String targetAddress,
        AtomicInteger runCounter,
        Random clockJitter
    ) {
        for (int runNumber = 0;; ++runNumber) {
            try {
                var workResult = migrateDocumentsWithOneWorker(
                    sourceRepo,
                    snapshotName,
                    indexAllowlist,
                    targetAddress,
                    clockJitter
                );
                if (workResult == DocumentsRunner.CompletionStatus.NOTHING_DONE) {
                    return null;
                } else {
                    runCounter.incrementAndGet();
                }
            } catch (RfsMigrateDocuments.NoWorkLeftException e) {
                log.info(
                    "No work at all was found.  "
                        + "Presuming that work was complete and that all worker processes should terminate"
                );
                throw e;
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

    private static void migrateMetadata(
        SourceRepo sourceRepo,
        OpenSearchClient targetClient,
        String snapshotName,
        List<String> indexAllowlist
    ) {
        SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
        GlobalMetadata.Factory metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
        GlobalMetadataCreator_OS_2_11 metadataCreator = new GlobalMetadataCreator_OS_2_11(
            targetClient,
            List.of(),
            List.of(),
            List.of()
        );
        Transformer transformer = TransformFunctions.getTransformer(ClusterVersion.ES_7_10, ClusterVersion.OS_2_11, 1);
        new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();

        IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
        IndexCreator_OS_2_11 indexCreator = new IndexCreator_OS_2_11(targetClient);
        new IndexRunner(snapshotName, indexMetadataFactory, indexCreator, transformer, indexAllowlist).migrateIndices();
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

    static class LeasePastError extends Error {}

    @SneakyThrows
    private DocumentsRunner.CompletionStatus migrateDocumentsWithOneWorker(
        SourceRepo sourceRepo,
        String snapshotName,
        List<String> indexAllowlist,
        String targetAddress,
        Random clockJitter
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

            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(
                repoAccessor,
                tempDir,
                ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES
            );

            SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);
            final int ms_window = 1000;
            final var nextClockShift = (int) (clockJitter.nextDouble() * ms_window) - (ms_window / 2);
            log.info("nextClockShift=" + nextClockShift);

            return RfsMigrateDocuments.run(
                path -> new FilteredLuceneDocumentsReader(path, terminatingDocumentFilter),
                new DocumentReindexer(new OpenSearchClient(targetAddress, null)),
                new OpenSearchWorkCoordinator(
                    new ApacheHttpClient(new URI(targetAddress)),
                    // new ReactorHttpClient(new ConnectionDetails(osTargetContainer.getHttpHostAddress(),
                    // null, null)),
                    TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                    UUID.randomUUID().toString(),
                    Clock.offset(Clock.systemUTC(), Duration.ofMillis(nextClockShift))
                ),
                processManager,
                indexMetadataFactory,
                snapshotName,
                indexAllowlist,
                shardMetadataFactory,
                unpackerFactory,
                MAX_SHARD_SIZE_BYTES
            );
        } finally {
            deleteTree(tempDir);
        }
    }

    public static Stream<Arguments> makeProcessExitArgs() {
        return Stream.of(Arguments.of(true, 0), Arguments.of(false, 1));
    }

    @ParameterizedTest
    @MethodSource("makeProcessExitArgs")
    public void testProcessExitsAsExpected(boolean targetAvailable, int expectedExitCode) throws Exception {
        var sourceImageArgs = makeParamsForBase(SearchClusterContainer.ES_V7_10_2);
        var baseSourceImageVersion = (SearchClusterContainer.Version) sourceImageArgs[0];
        var generatorImage = (String) sourceImageArgs[1];
        var generatorArgs = (String[]) sourceImageArgs[2];
        var targetImageName = SearchClusterContainer.OS_V2_14_0.getImageName();

        try (
            var esSourceContainer = new PreloadedSearchClusterContainer(
                baseSourceImageVersion,
                SOURCE_SERVER_ALIAS,
                generatorImage,
                generatorArgs
            );
            OpensearchContainer<?> osTargetContainer = new OpensearchContainer<>(targetImageName)
        ) {
            esSourceContainer.start();
            osTargetContainer.start();

            final var SNAPSHOT_NAME = "test_snapshot";
            final List<String> INDEX_ALLOWLIST = List.of();
            CreateSnapshot.run(
                c -> new FileSystemSnapshotCreator(SNAPSHOT_NAME, c, SearchClusterContainer.CLUSTER_SNAPSHOT_DIR),
                new OpenSearchClient(esSourceContainer.getUrl(), null),
                false
            );
            var tempDirSnapshot = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
            var tempDirLucene = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");

            String targetAddress = osTargetContainer.getHttpHostAddress();

            String[] args = {
                "--snapshot-name",
                SNAPSHOT_NAME,
                "--snapshot-local-dir",
                tempDirSnapshot.toString(),
                "--lucene-dir",
                tempDirLucene.toString(),
                "--target-host",
                targetAddress };

            try {
                esSourceContainer.copySnapshotData(tempDirSnapshot.toString());

                var targetClient = new OpenSearchClient(targetAddress, null);
                var sourceRepo = new FileSystemRepo(tempDirSnapshot);
                migrateMetadata(sourceRepo, targetClient, SNAPSHOT_NAME, INDEX_ALLOWLIST);

                // Stop the target container if we don't want it to be available. We've already cached the address it
                // was
                // using, so we can have reasonable confidence that nothing else will be using it and bork our test.
                if (!targetAvailable) {
                    osTargetContainer.stop();
                }

                String classpath = System.getProperty("java.class.path");
                String javaHome = System.getProperty("java.home");
                String javaExecutable = javaHome + File.separator + "bin" + File.separator + "java";

                // Kick off the doc migration process
                log.atInfo().setMessage("Running RfsMigrateDocuments with args: " + Arrays.toString(args)).log();
                ProcessBuilder processBuilder = new ProcessBuilder(
                    javaExecutable,
                    "-cp",
                    classpath,
                    "com.rfs.RfsMigrateDocuments"
                );
                processBuilder.command().addAll(Arrays.asList(args));
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();
                log.atInfo().setMessage("Process started with ID: " + Long.toString(process.toHandle().pid())).log();

                // Kill the process and fail if we have to wait too long
                int timeoutSeconds = 90;
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    // Print the process output
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append(System.lineSeparator());
                        }
                    }
                    log.atError().setMessage("Process Output:").log();
                    log.atError().setMessage(output.toString()).log();

                    log.atError().setMessage("Process timed out, attempting to kill it...").log();
                    process.destroy(); // Try to be nice about things first...
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        log.atError().setMessage("Process still running, attempting to force kill it...").log();
                        process.destroyForcibly(); // ..then avada kedavra
                    }
                    Assertions.fail(
                        "The process did not finish within the timeout period (" + timeoutSeconds + " seconds)."
                    );
                }

                int actualExitCode = process.exitValue();
                log.atInfo().setMessage("Process exited with code: " + actualExitCode).log();

                // Check if the exit code is as expected
                Assertions.assertEquals(
                    expectedExitCode,
                    actualExitCode,
                    "The program did not exit with the expected status code."
                );

            } finally {
                deleteTree(tempDirSnapshot);
                deleteTree(tempDirLucene);
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
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
