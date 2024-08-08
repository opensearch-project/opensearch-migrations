package com.rfs;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.opensearch.migrations.reindexer.tracing.RootDocumentMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import com.rfs.cms.CoordinateWorkHttpClient;
import com.rfs.cms.IWorkCoordinator;
import com.rfs.cms.LeaseExpireTrigger;
import com.rfs.cms.OpenSearchWorkCoordinator;
import com.rfs.cms.ScopedWorkCoordinator;
import com.rfs.common.DefaultSourceRepoAccessor;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.S3Repo;
import com.rfs.common.S3Uri;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.common.SourceRepo;
import com.rfs.common.TryHandlePhaseFailure;
import com.rfs.common.http.ConnectionContext;
import com.rfs.models.IndexMetadata;
import com.rfs.models.ShardMetadata;
import com.rfs.tracing.RootWorkCoordinationContext;
import com.rfs.version_es_7_10.ElasticsearchConstants_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.worker.DocumentsRunner;
import com.rfs.worker.ShardWorkPreparer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RfsMigrateDocuments {
    public static final int PROCESS_TIMED_OUT = 2;
    public static final int TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 5;

    public static class DurationConverter implements IStringConverter<Duration> {
        @Override
        public Duration convert(String value) {
            return Duration.parse(value);
        }
    }

    public static class Args {
        @Parameter(names = {"--help", "-h"}, help = true, description = "Displays information about how to use this tool")
        private boolean help;

        @Parameter(names = { "--snapshot-name" }, required = true, description = "The name of the snapshot to migrate")
        public String snapshotName;

        @Parameter(names = {
            "--snapshot-local-dir" }, required = false, description = ("The absolute path to the directory on local disk where the snapshot exists.  Use this parameter"
                + " if have a copy of the snapshot disk.  Mutually exclusive with --s3-local-dir, --s3-repo-uri, and --s3-region."))
        public String snapshotLocalDir = null;

        @Parameter(names = {
            "--s3-local-dir" }, required = false, description = ("The absolute path to the directory on local disk to download S3 files to.  If you supply this, you must"
                + " also supply --s3-repo-uri and --s3-region.  Mutually exclusive with --snapshot-local-dir."))
        public String s3LocalDir = null;

        @Parameter(names = {
            "--s3-repo-uri" }, required = false, description = ("The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2.  If you supply this, you must"
                + " also supply --s3-local-dir and --s3-region.  Mutually exclusive with --snapshot-local-dir."))
        public String s3RepoUri = null;

        @Parameter(names = {
            "--s3-region" }, required = false, description = ("The AWS Region the S3 bucket is in, like: us-east-2.  If you supply this, you must"
                + " also supply --s3-local-dir and --s3-repo-uri.  Mutually exclusive with --snapshot-local-dir."))
        public String s3Region = null;

        @Parameter(names = {
            "--lucene-dir" }, required = true, description = "The absolute path to the directory where we'll put the Lucene docs")
        public String luceneDir;

        @ParametersDelegate
        public ConnectionContext.TargetArgs targetArgs = new ConnectionContext.TargetArgs();

        @Parameter(names = { "--index-allowlist" }, description = ("Optional.  List of index names to migrate"
            + " (e.g. 'logs_2024_01, logs_2024_02').  Default: all non-system indices (e.g. those not starting with '.')"), required = false)
        public List<String> indexAllowlist = List.of();

        @Parameter(names = {
            "--max-shard-size-bytes" }, description = ("Optional. The maximum shard size, in bytes, to allow when"
                + " performing the document migration.  Useful for preventing disk overflow.  Default: 80 * 1024 * 1024 * 1024 (80 GB)"), required = false)
        public long maxShardSizeBytes = 80 * 1024 * 1024 * 1024L;

        @Parameter(names = { "--max-initial-lease-duration" }, description = ("Optional. The maximum time that the "
            + "first attempt to migrate a shard's documents should take.  If a process takes longer than this "
            + "the process will terminate, allowing another process to attempt the migration, but with double the "
            + "amount of time than the last time.  Default: PT10M"), required = false, converter = DurationConverter.class)
        public Duration maxInitialLeaseDuration = Duration.ofMinutes(10);

        @Parameter(required = false, names = {
            "--otel-collector-endpoint" }, arity = 1, description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be"
                + "forwarded. If no value is provided, metrics will not be forwarded.")
        String otelCollectorEndpoint;

        @Parameter(required = false,
        names = "--documents-per-bulk-request",
        description = "Optional.  The number of documents to be included within each bulk request sent.")
        int numDocsPerBulkRequest = 1000;

        @Parameter(required = false,
            names = "--max-connections",
            description = "Optional.  The maximum number of connections to simultaneously " +
                "used to communicate to the target.")
        int maxConnections = -1;
    }

    public static class NoWorkLeftException extends Exception {
        public NoWorkLeftException(String message) {
            super(message);
        }
    }

    public static void validateArgs(Args args) {
        boolean isSnapshotLocalDirProvided = args.snapshotLocalDir != null;
        boolean areAllS3ArgsProvided = args.s3LocalDir != null && args.s3RepoUri != null && args.s3Region != null;
        boolean areAnyS3ArgsProvided = args.s3LocalDir != null || args.s3RepoUri != null || args.s3Region != null;

        if (isSnapshotLocalDirProvided && areAnyS3ArgsProvided) {
            throw new ParameterException(
                "You must provide either --snapshot-local-dir or --s3-local-dir, --s3-repo-uri, and --s3-region, but not both."
            );
        }

        if (areAnyS3ArgsProvided && !areAllS3ArgsProvided) {
            throw new ParameterException(
                "If provide the S3 Snapshot args, you must provide all of them (--s3-local-dir, --s3-repo-uri and --s3-region)."
            );
        }

        if (!isSnapshotLocalDirProvided && !areAllS3ArgsProvided) {
            throw new ParameterException(
                "You must provide either --snapshot-local-dir or --s3-local-dir, --s3-repo-uri, and --s3-region."
            );
        }

    }

    public static void main(String[] args) throws Exception {
        Args arguments = new Args();
        JCommander jCommander = JCommander.newBuilder().addObject(arguments).build();
        jCommander.parse(args);

        if (arguments.help) {
            jCommander.usage();
            return;
        }

        validateArgs(arguments);

        var rootDocumentContext = makeRootContext(arguments);
        var luceneDirPath = Paths.get(arguments.luceneDir);
        var snapshotLocalDirPath = arguments.snapshotLocalDir != null ? Paths.get(arguments.snapshotLocalDir) : null;

        try (var processManager = new LeaseExpireTrigger(workItemId -> {
            log.error("Terminating RfsMigrateDocuments because the lease has expired for " + workItemId);
            System.exit(PROCESS_TIMED_OUT);
        }, Clock.systemUTC())) {
            ConnectionContext connectionContext = arguments.targetArgs.toConnectionContext();
            var workCoordinator = new OpenSearchWorkCoordinator(
                new CoordinateWorkHttpClient(connectionContext),
                TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                UUID.randomUUID().toString()
            );
            TryHandlePhaseFailure.executeWithTryCatch(() -> {
                log.info("Running RfsWorker");

                OpenSearchClient targetClient = new OpenSearchClient(connectionContext, arguments.maxConnections);
                DocumentReindexer reindexer = new DocumentReindexer(targetClient, arguments.numDocsPerBulkRequest);

                SourceRepo sourceRepo;
                if (snapshotLocalDirPath == null) {
                    sourceRepo = S3Repo.create(
                        Paths.get(arguments.s3LocalDir),
                        new S3Uri(arguments.s3RepoUri),
                        arguments.s3Region
                    );
                } else {
                    sourceRepo = new FileSystemRepo(snapshotLocalDirPath);
                }
                SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);

                IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
                ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);
                DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
                SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(
                    repoAccessor,
                    luceneDirPath,
                    ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES
                );

                run(
                    LuceneDocumentsReader::new,
                    reindexer,
                    workCoordinator,
                    arguments.maxInitialLeaseDuration,
                    processManager,
                    indexMetadataFactory,
                    arguments.snapshotName,
                    arguments.indexAllowlist,
                    shardMetadataFactory,
                    unpackerFactory,
                    arguments.maxShardSizeBytes,
                    rootDocumentContext
                );
            });
        }
    }

    private static RootDocumentMigrationContext makeRootContext(Args arguments) {
        var compositeContextTracker = new CompositeContextTracker(
            new ActiveContextTracker(),
            new ActiveContextTrackerByActivityType()
        );
        var otelSdk = RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(
            arguments.otelCollectorEndpoint,
            "docMigration"
        );
        var workContext = new RootWorkCoordinationContext(otelSdk, compositeContextTracker);
        return new RootDocumentMigrationContext(otelSdk, compositeContextTracker, workContext);
    }

    public static DocumentsRunner.CompletionStatus run(
        Function<Path, LuceneDocumentsReader> readerFactory,
        DocumentReindexer reindexer,
        IWorkCoordinator workCoordinator,
        Duration maxInitialLeaseDuration,
        LeaseExpireTrigger leaseExpireTrigger,
        IndexMetadata.Factory indexMetadataFactory,
        String snapshotName,
        List<String> indexAllowlist,
        ShardMetadata.Factory shardMetadataFactory,
        SnapshotShardUnpacker.Factory unpackerFactory,
        long maxShardSizeBytes,
        RootDocumentMigrationContext rootDocumentContext
    ) throws IOException, InterruptedException, NoWorkLeftException {
        var scopedWorkCoordinator = new ScopedWorkCoordinator(workCoordinator, leaseExpireTrigger);
        confirmShardPrepIsComplete(
            indexMetadataFactory,
            snapshotName,
            indexAllowlist,
            scopedWorkCoordinator,
            rootDocumentContext
        );
        if (!workCoordinator.workItemsArePending(
            rootDocumentContext.getWorkCoordinationContext()::createItemsPendingContext
        )) {
            throw new NoWorkLeftException("No work items are pending/all work items have been processed.  Returning.");
        }
        return new DocumentsRunner(scopedWorkCoordinator, maxInitialLeaseDuration, (name, shard) -> {
            var shardMetadata = shardMetadataFactory.fromRepo(snapshotName, name, shard);
            log.info("Shard size: " + shardMetadata.getTotalSizeBytes());
            if (shardMetadata.getTotalSizeBytes() > maxShardSizeBytes) {
                throw new DocumentsRunner.ShardTooLargeException(shardMetadata.getTotalSizeBytes(), maxShardSizeBytes);
            }
            return shardMetadata;
        }, unpackerFactory, readerFactory, reindexer).migrateNextShard(rootDocumentContext::createReindexContext);
    }

    private static void confirmShardPrepIsComplete(
        IndexMetadata.Factory indexMetadataFactory,
        String snapshotName,
        List<String> indexAllowlist,
        ScopedWorkCoordinator scopedWorkCoordinator,
        RootDocumentMigrationContext rootContext
    ) throws IOException, InterruptedException {
        // assume that the shard setup work will be done quickly, much faster than its lease in most cases.
        // in cases where that isn't true, doing random backoff across the fleet should guarantee that eventually,
        // these workers will attenuate enough that it won't cause an impact on the coordination server
        long lockRenegotiationMillis = 1000;
        for (int shardSetupAttemptNumber = 0;; ++shardSetupAttemptNumber) {
            try {
                new ShardWorkPreparer().run(
                    scopedWorkCoordinator,
                    indexMetadataFactory,
                    snapshotName,
                    indexAllowlist,
                    rootContext
                );
                return;
            } catch (IWorkCoordinator.LeaseLockHeldElsewhereException e) {
                long finalLockRenegotiationMillis = lockRenegotiationMillis;
                int finalShardSetupAttemptNumber = shardSetupAttemptNumber;
                log.atInfo()
                    .setMessage(
                        () -> "After "
                            + finalShardSetupAttemptNumber
                            + "another process holds the lock"
                            + " for setting up the shard work items.  "
                            + "Waiting "
                            + finalLockRenegotiationMillis
                            + "ms before trying again."
                    )
                    .log();
                Thread.sleep(lockRenegotiationMillis);
                lockRenegotiationMillis *= 2;
                continue;
            }
        }
    }
}
