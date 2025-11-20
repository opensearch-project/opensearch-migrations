package org.opensearch.migrations.bulkload;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DefaultSourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
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
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.utils.FileSystemUtils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.opensearch.migrations.bulkload.CustomRfsTransformationTest.SNAPSHOT_NAME;

@Slf4j
public class SourceTestBase {
    public static final long MAX_SHARD_SIZE_BYTES = 1024 * 1024 * 1024L; // 1 GB
    public static final String SOURCE_SERVER_ALIAS = "source";
    public static final long TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 3600;

    @NotNull
    protected static Process runAndMonitorProcess(ProcessBuilder processBuilder) throws IOException {
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        var process = processBuilder.start();

        log.atInfo().setMessage("Process started with ID: {}").addArgument(() -> process.toHandle().pid()).log();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var readerThread = new Thread(() -> {
            String line;
            while (true) {
                try {
                    if ((line = reader.readLine()) == null) break;
                } catch (IOException e) {
                    log.atWarn().setCause(e).setMessage("Couldn't read next line from sub-process").log();
                    return;
                }
                String finalLine = line;
                log.atInfo()
                    .setMessage("from sub-process [{}]: {}")
                    .addArgument(() -> process.toHandle().pid())
                    .addArgument(finalLine)
                    .log();
            }
        });

        // Kill the process and fail if we have to wait too long
        readerThread.start();
        return process;
    }

    @SneakyThrows
    protected static int runProcessAgainstTarget(String[] processArgs)
    {
        int timeoutSeconds = 30;
        ProcessBuilder processBuilder = setupProcess(processArgs);

        var process = runAndMonitorProcess(processBuilder);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            log.atError().setMessage("Process timed out, attempting to kill it...").log();
            process.destroy(); // Try to be nice about things first...
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                log.atError().setMessage("Process still running, attempting to force kill it...").log();
                process.destroyForcibly();
            }
            Assertions.fail("The process did not finish within the timeout period (" + timeoutSeconds + " seconds).");
        }

        return process.exitValue();
    }

    @NotNull
    protected static ProcessBuilder setupProcess(String[] processArgs) {
        String classpath = System.getProperty("java.class.path");
        String javaHome = System.getProperty("java.home");
        String javaExecutable = javaHome + File.separator + "bin" + File.separator + "java";

        // Kick off the doc migration process
        log.atInfo().setMessage("Running RfsMigrateDocuments with args: {}")
            .addArgument(() -> Arrays.toString(processArgs))
            .log();
        ProcessBuilder processBuilder = new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "org.opensearch.migrations.RfsMigrateDocuments"
        );
        processBuilder.command().addAll(Arrays.asList(processArgs));
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput();
        return processBuilder;
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
        SearchClusterContainer target,
        AtomicInteger runCounter,
        Random clockJitter,
        DocumentMigrationTestContext testContext,
        Version sourceVersion,
        Version targetVersion,
        String transformationConfig
    ) {
        return migrateDocumentsSequentially(
            sourceRepo,
            null,
            snapshotName,
            indexAllowlist,
            target,
            runCounter,
            clockJitter,
            testContext,
            sourceVersion,
            targetVersion,
            transformationConfig
        );
    }


    public static int migrateDocumentsSequentially(
        FileSystemRepo sourceRepo,
        String previousSnapshotName,
        String snapshotName,
        List<String> indexAllowlist,
        SearchClusterContainer target,
        AtomicInteger runCounter,
        Random clockJitter,
        DocumentMigrationTestContext testContext,
        Version sourceVersion,
        Version targetVersion,
        String transformationConfig
    ) {
        return migrateDocumentsSequentially(
            sourceRepo,
            previousSnapshotName,
            snapshotName,
            indexAllowlist,
            target,
            runCounter,
            clockJitter,
            testContext,
            sourceVersion,
            targetVersion,
            transformationConfig,
            DocumentExceptionAllowlist.empty()
        );
    }

    public static int migrateDocumentsSequentially(
        FileSystemRepo sourceRepo,
        String previousSnapshotName,
        String snapshotName,
        List<String> indexAllowlist,
        SearchClusterContainer target,
        AtomicInteger runCounter,
        Random clockJitter,
        DocumentMigrationTestContext testContext,
        Version sourceVersion,
        Version targetVersion,
        String transformationConfig,
        DocumentExceptionAllowlist allowlist
    ) {
        for (int runNumber = 1; ; ++runNumber) {
            try {
                var workResult = migrateDocumentsWithOneWorker(
                    sourceRepo,
                    snapshotName,
                    previousSnapshotName,
                    indexAllowlist,
                    target.getUrl(),
                    clockJitter,
                    testContext,
                    sourceVersion,
                    targetVersion,
                    transformationConfig,
                    allowlist
                );
                if (workResult == CompletionStatus.NOTHING_DONE) {
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

    static class LeasePastError extends Error {
    }

    @SuppressWarnings("unchecked")
    @SneakyThrows
    public static CompletionStatus migrateDocumentsWithOneWorker(
        SourceRepo sourceRepo,
        String snapshotName,
        String previousSnapshotName,
        List<String> indexAllowlist,
        String targetAddress,
        Random clockJitter,
        DocumentMigrationTestContext context,
        Version sourceVersion,
        Version targetVersion,
        String transformationConfig
    ) throws RfsMigrateDocuments.NoWorkLeftException {
        return migrateDocumentsWithOneWorker(
            sourceRepo,
            snapshotName,
            previousSnapshotName,
            indexAllowlist,
            targetAddress,
            clockJitter,
            context,
            sourceVersion,
            targetVersion,
            transformationConfig,
            DocumentExceptionAllowlist.empty()
        );
    }

    @SuppressWarnings("unchecked")
    @SneakyThrows
    public static CompletionStatus migrateDocumentsWithOneWorker(
        SourceRepo sourceRepo,
        String snapshotName,
        String previousSnapshotName,
        List<String> indexAllowlist,
        String targetAddress,
        Random clockJitter,
        DocumentMigrationTestContext context,
        Version sourceVersion,
        Version targetVersion,
        String transformationConfig,
        DocumentExceptionAllowlist allowlist
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

            var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(sourceVersion, sourceRepo, false);

            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(
                repoAccessor,
                tempDir
            );

            final int ms_window = 1000;
            final var nextClockShift = (int) (clockJitter.nextDouble() * ms_window) - (ms_window / 2);
            log.info("nextClockShift=" + nextClockShift);

            var readerFactory = spy(new LuceneIndexReader.Factory(sourceResourceProvider));
            when(readerFactory.getReader(any())).thenAnswer(inv -> {
                var reader = (LuceneIndexReader)spy(inv.callRealMethod());
                when(reader.readDocuments(any())).thenAnswer(inv2 -> {
                    var flux = (Flux<RfsLuceneDocument>)inv2.callRealMethod();
                    return flux.map(terminatingDocumentFilter);
                });
                return reader;
            });


            var docTransformer = new TransformationLoader().getTransformerFactoryLoader(
                    Optional.ofNullable(transformationConfig).orElse(
                            RfsMigrateDocuments.DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG
                    ));

            AtomicReference<WorkItemCursor> progressCursor = new AtomicReference<>();
            var coordinatorFactory = new WorkCoordinatorFactory(targetVersion);
            var connectionContext = ConnectionContextTestParams.builder()
                    .host(targetAddress)
                    .build()
                    .toConnectionContext();
            var workItemRef = new AtomicReference<IWorkCoordinator.WorkItemAndDuration>();

            try (var workCoordinator = coordinatorFactory.get(
                    new CoordinateWorkHttpClient(connectionContext),
                    TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                    UUID.randomUUID().toString(),
                    Clock.offset(Clock.systemUTC(), Duration.ofMillis(nextClockShift)),
                    workItemRef::set
            )) {
                var clientFactory = new OpenSearchClientFactory(connectionContext);
                return RfsMigrateDocuments.run(
                    readerFactory,
                    new DocumentReindexer(clientFactory.determineVersionAndCreate(), 1000, Long.MAX_VALUE, 1, () -> docTransformer, allowlist),
                    progressCursor,
                    workCoordinator,
                    Duration.ofMinutes(10),
                    processManager,
                    sourceResourceProvider.getIndexMetadata(),
                    snapshotName,
                    previousSnapshotName,
                    previousSnapshotName != null ? DeltaMode.UPDATES_AND_DELETES : null,
                    indexAllowlist,
                    sourceResourceProvider.getShardMetadata(),
                    unpackerFactory,
                    MAX_SHARD_SIZE_BYTES,
                    context,
                    new AtomicReference<>(),
                    new WorkItemTimeProvider());
            }
        } finally {
            FileSystemUtils.deleteDirectories(tempDir.toString());
        }
    }

    @AllArgsConstructor
    @Getter
    public static class RunData {
        Path tempDirSnapshot;
        Path tempDirLucene;
        ToxiProxyWrapper proxyContainer;
    }

    public enum FailHow {
        NEVER,
        AT_STARTUP,
        WITH_DELAYS
    }

    @NotNull
    public static ProcessBuilder setupProcess(
        Path tempDirSnapshot,
        Path tempDirLucene,
        String targetAddress,
        String[] additionalArgs
    ) {
        String classpath = System.getProperty("java.class.path");
        String javaHome = System.getProperty("java.home");
        var javaExecutable = Paths.get(javaHome, "bin", "java").toString();

        List<String> argsList = new ArrayList<>(Arrays.asList(
            "--snapshot-name",
            SNAPSHOT_NAME,
            "--snapshot-local-dir",
            tempDirSnapshot.toString(),
            "--lucene-dir",
            tempDirLucene.toString(),
            "--target-host",
            targetAddress,
            "--index-allowlist",
            "geonames"
        ));

        if (additionalArgs != null && additionalArgs.length > 0) {
            argsList.addAll(Arrays.asList(additionalArgs));
            if (!argsList.contains("--source-version")) {
                argsList.addAll(Arrays.asList("--source-version", "ES_7_10"));
            }
        }


        log.atInfo().setMessage("Running RfsMigrateDocuments with args: {}")
            .addArgument(() -> argsList.toString())
            .log();
        ProcessBuilder processBuilder = new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "org.opensearch.migrations.RfsMigrateDocuments"
        );
        processBuilder.command().addAll(argsList);
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        return processBuilder;
    }

    public void createSnapshot(
        SearchClusterContainer sourceContainer,
        String snapshotName,
        SnapshotTestContext testSnapshotContext
    ) throws Exception {
        createSnapshot(sourceContainer, snapshotName, testSnapshotContext, false, true);
    }

    public void createSnapshot(
        SearchClusterContainer sourceContainer,
        String snapshotName,
        SnapshotTestContext testSnapshotContext,
        boolean compressionEnabled,
        boolean includeGlobalState
    ) throws Exception {
        var args = new CreateSnapshot.Args();
        args.snapshotName = snapshotName;
        args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
        args.sourceArgs.host = sourceContainer.getUrl();
        args.compressionEnabled = compressionEnabled;
        args.includeGlobalState = includeGlobalState;

        var snapshotCreator = new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext());
        snapshotCreator.run();
    }

    protected ExpectedMigrationWorkTerminationException waitForRfsCompletion(Executable executable) {
        var expectedTerminationException = Assertions.assertTimeout(
            Duration.ofSeconds(30),
            () -> {
                return Assertions.assertThrows(
                    ExpectedMigrationWorkTerminationException.class,
                    executable
                );
            }
        );
        return expectedTerminationException;
    }
}
