package com.rfs;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionConverter;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;
import org.opensearch.migrations.reindexer.tracing.RootDocumentMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.utils.ProcessHelpers;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import com.rfs.RfsMigrateDocuments.RunParameters;
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
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.common.SourceRepo;
import com.rfs.common.http.ConnectionContext;
import com.rfs.models.IndexMetadata;
import com.rfs.models.ShardMetadata;
import com.rfs.worker.DocumentsRunner;
import com.rfs.worker.ShardWorkPreparer;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
public class RfsMigrateDocuments {
    public static final int PROCESS_TIMED_OUT = 2;
    public static final int TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 5;
    public static final String LOGGING_MDC_WORKER_ID = "workerId";

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

        @Parameter(names = { "--initial-lease-duration" }, description = ("Optional. The time that the "
            + "first attempt to migrate a shard's documents should take.  If a process takes longer than this "
            + "the process will terminate, allowing another process to attempt the migration, but with double the "
            + "amount of time than the last time.  Default: PT10M"), required = false, converter = DurationConverter.class)
        public Duration initialLeaseDuration = Duration.ofMinutes(10);

        @Parameter(required = false, names = {
            "--otel-collector-endpoint" }, arity = 1, description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be"
                + "forwarded. If no value is provided, metrics will not be forwarded.")
        String otelCollectorEndpoint;

        @Parameter(required = false,
        names = "--documents-per-bulk-request",
        description = "Optional.  The number of documents to be included within each bulk request sent. Default no max (controlled by documents size)")
        int numDocsPerBulkRequest = Integer.MAX_VALUE;

        @Parameter(required = false,
            names = "--documents-size-per-bulk-request",
            description = "Optional. The maximum aggregate document size to be used in bulk requests in bytes. " +
                "Note does not apply to single document requests. Default 10 MiB")
        long numBytesPerBulkRequest = 10 * 1024L * 1024L;

        @Parameter(required = false,
            names = "--max-connections",
            description = "Optional.  The maximum number of connections to simultaneously " +
                "used to communicate to the target, default 10")
        int maxConnections = 10;

        @Parameter(names = { "--source-version" }, description = ("Optional. Version of the source cluster.  Default: ES_7.10"), required = false,
            converter = VersionConverter.class)
        public Version sourceVersion = Version.fromString("ES 7.10");
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
        log.info("Got args: " + String.join("; ", args));
        var workerId = ProcessHelpers.getNodeInstanceName();
        log.info("Starting RfsMigrateDocuments with workerId =" + workerId);

        Args arguments = new Args();
        JCommander jCommander = JCommander.newBuilder().addObject(arguments).build();
        jCommander.parse(args);

        if (arguments.help) {
            jCommander.usage();
            return;
        }

        validateArgs(arguments);

        var context = makeRootContext(arguments, workerId);
        var luceneDirPath = Paths.get(arguments.luceneDir);
        var snapshotLocalDirPath = arguments.snapshotLocalDir != null ? Paths.get(arguments.snapshotLocalDir) : null;

        var connectionContext = arguments.targetArgs.toConnectionContext();
        try (var processManager = new LeaseExpireTrigger(workItemId -> {
            log.error("Terminating RfsMigrateDocuments because the lease has expired for " + workItemId);
            System.exit(PROCESS_TIMED_OUT);
        }, Clock.systemUTC());
            var workCoordinator = new OpenSearchWorkCoordinator(
                new CoordinateWorkHttpClient(connectionContext),
                TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                workerId
            )) {
            MDC.put(LOGGING_MDC_WORKER_ID, workerId); // I don't see a need to clean this up since we're in main
            OpenSearchClient targetClient = new OpenSearchClient(connectionContext);
            DocumentReindexer reindexer = new DocumentReindexer(targetClient,
                arguments.numDocsPerBulkRequest,
                arguments.numBytesPerBulkRequest,
                arguments.maxConnections);

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
            var repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);

            var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(arguments.sourceVersion, sourceRepo);

            var unpackerFactory = new SnapshotShardUnpacker.Factory(
                repoAccessor,
                luceneDirPath,
                sourceResourceProvider.getBufferSizeInBytes()
            );

            run(RunParameters.builder()
                .leaseExpireTrigger(processManager)
                .workCoordinator(workCoordinator)
                .reindexer(reindexer)
                .snapshotName(arguments.snapshotName)
                .snapshotReader(sourceResourceProvider)
                .snapshotUnpacker(unpackerFactory)
                .documentReader(LuceneDocumentsReader.getFactory(sourceResourceProvider))
                .indexAllowlist(arguments.indexAllowlist)
                .maxInitialLeaseDuration(arguments.initialLeaseDuration)
                .maxShardSizeBytes(arguments.maxShardSizeBytes)
                .tracingContext(context)
                .build());
        } catch (Exception e) {
            log.atError().setMessage("Unexpected error running RfsWorker").setCause(e).log();
            throw e;
        }
    }

    private static RootDocumentMigrationContext makeRootContext(Args arguments, String workerId) {
        var compositeContextTracker = new CompositeContextTracker(
            new ActiveContextTracker(),
            new ActiveContextTrackerByActivityType()
        );
        var otelSdk = RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(
            arguments.otelCollectorEndpoint,
            RootDocumentMigrationContext.SCOPE_NAME,
            workerId
        );
        return new RootDocumentMigrationContext(otelSdk, compositeContextTracker);
    }

    public static DocumentsRunner.CompletionStatus run(RunParameters params) throws Exception {
        var scopedWorkCoordinator = new ScopedWorkCoordinator(params.workCoordinator, params.leaseExpireTrigger);
        confirmShardPrepIsComplete(
            params.snapshotReader.getIndexMetadata(),
            params.snapshotName,
            params.indexAllowlist,
            scopedWorkCoordinator,
            params.tracingContext
        );
        if (!params.workCoordinator.workItemsArePending(
            params.tracingContext.getWorkCoordinationContext()::createItemsPendingContext
        )) {
            throw new NoWorkLeftException("No work items are pending/all work items have been processed.  Returning.");
        }
        BiFunction<String, Integer, ShardMetadata> shardFactory = (name, shard) -> {
            var shardMetadataFactory = params.snapshotReader.getShardMetadata();
            var shardMetadata = shardMetadataFactory.fromRepo(params.snapshotName, name, shard);
            log.info("Shard size: " + shardMetadata.getTotalSizeBytes());
            if (shardMetadata.getTotalSizeBytes() > params.maxShardSizeBytes) {
                throw new DocumentsRunner.ShardTooLargeException(shardMetadata.getTotalSizeBytes(), params.maxShardSizeBytes);
            }
            return shardMetadata;
        };
        var runner = new DocumentsRunner(
            scopedWorkCoordinator,
            params.maxInitialLeaseDuration,
            shardFactory,
            params.snapshotUnpacker,
            params.documentReader,
            params.reindexer);
        var migrationStatus = runner.migrateNextShard(params.tracingContext::createReindexContext);
        return migrationStatus;
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
            }
        }
    }

    @Builder
    static class RunParameters {
        @NonNull
        final LeaseExpireTrigger leaseExpireTrigger;
        @NonNull
        final IWorkCoordinator workCoordinator;
        @NonNull
        final String snapshotName;
        @NonNull
        final ClusterSnapshotReader snapshotReader;
        @NonNull
        final SnapshotShardUnpacker.Factory snapshotUnpacker;
        @NonNull
        final Function<Path, LuceneDocumentsReader> documentReader;
        @NonNull
        final DocumentReindexer reindexer;
        @NonNull
        final List<String> indexAllowlist;
        @NonNull
        final Duration maxInitialLeaseDuration;
        final long maxShardSizeBytes;
        @NonNull
        final RootDocumentMigrationContext tracingContext;
    }
}
