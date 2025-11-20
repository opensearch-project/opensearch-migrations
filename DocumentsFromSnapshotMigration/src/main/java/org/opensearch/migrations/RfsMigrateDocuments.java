package org.opensearch.migrations;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.opensearch.migrations.arguments.ArgLogUtils;
import org.opensearch.migrations.arguments.ArgNameConstants;
import org.opensearch.migrations.bulkload.common.DefaultSourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.delta.DeltaDocumentReaderEngine;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;
import org.opensearch.migrations.bulkload.workcoordination.CoordinateWorkHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.WorkCoordinatorFactory;
import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.DocumentsRunner;
import org.opensearch.migrations.bulkload.worker.RegularDocumentReaderEngine;
import org.opensearch.migrations.bulkload.worker.ShardWorkPreparer;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.jcommander.EnvVarParameterPuller;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;
import org.opensearch.migrations.reindexer.tracing.RootDocumentMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.transform.TransformerConfigUtils;
import org.opensearch.migrations.transform.TransformerParams;
import org.opensearch.migrations.utils.FileSystemUtils;
import org.opensearch.migrations.utils.ProcessHelpers;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.IValueValidator;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.slf4j.MDC;

@Slf4j
public class RfsMigrateDocuments {
    public static final int PROCESS_TIMED_OUT_EXIT_CODE = 2;
    public static final int NO_WORK_LEFT_EXIT_CODE = 3;

    // Arbitrary value, increasing from 5 to 15 seconds due to prevalence of clock skew exceptions
    // observed on production clusters during migrations
    public static final int TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 15;
    public static final String LOGGING_MDC_WORKER_ID = "workerId";

    // Decrease successor nextAcquisitionLeaseExponent if shard setup takes less than 2.5% of total lease time
    // Increase successor nextAcquisitionLeaseExponent if shard setup takes more than 10% of lease total time
    private static final double DECREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD = 0.025;
    private static final double INCREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD = 0.1;

    public static final String DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG = "[" +
            "  {" +
            "    \"JsonTransformerForDocumentTypeRemovalProvider\":\"\"" +
            "  }" +
            "]";

    public static class DurationConverter implements IStringConverter<Duration> {
        @Override
        public Duration convert(String value) {
            return Duration.parse(value);
        }
    }

    public static class DeltaModeConverter implements IStringConverter<DeltaMode> {
        @Override
        public DeltaMode convert(String value) {
            try {
                return DeltaMode.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ParameterException("Invalid delta mode: " + value + ". Valid values are: " + 
                    String.join(", ", java.util.Arrays.stream(DeltaMode.values())
                        .map(Enum::name)
                        .toArray(String[]::new)));
            }
        }
    }

    public static class Args {
        @Parameter(
            names = {"--help", "-h"},
            help = true,
            description = "Displays information about how to use this tool")
        private boolean help;

        @Parameter(required = true,
            names = { "--snapshot-name", "--snapshotName" },
            description = "The name of the snapshot to migrate")
        public String snapshotName;

        @Parameter(required = false,
            names = { "--snapshot-local-dir", "--snapshotLocalDir" },
            description = ("The absolute path to the directory on local disk where the snapshot exists.  " +
                "Use this parameter if there is a reachable copy of the snapshot on disk.  Mutually exclusive with " +
                "--s3-local-dir, --s3-repo-uri, and --s3-region."))
        public String snapshotLocalDir = null;

        @Parameter(required = false,
            names = { "--s3-local-dir", "--s3LocalDir" },
            description = ("The absolute path to the directory on local disk to download S3 files to.  " +
                "If you supply this, you must also supply --s3-repo-uri and --s3-region.  " +
                "Mutually exclusive with --snapshot-local-dir."))
        public String s3LocalDir = null;

        @Parameter(required = false,
            names = {"--s3-repo-uri", "--s3RepoUri" },
            description = ("The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2.  " +
                "If you supply this, you must also supply --s3-local-dir and --s3-region.  " +
                "Mutually exclusive with --snapshot-local-dir."))
        public String s3RepoUri = null;

        @Parameter(required = false,
            names = { "--s3-region", "--s3Region" },
            description = ("The AWS Region the S3 bucket is in, like: us-east-2.  If you supply this, you must"
                + " also supply --s3-local-dir and --s3-repo-uri.  Mutually exclusive with --snapshot-local-dir."))
        public String s3Region = null;

        @Parameter(required = false,
            names = { "--s3-endpoint", "--s3Endpoint" },
            description = ("The endpoint URL to use for S3 calls.  " +
                "For use when the default AWS ones won't work for a particular context."))
        public String s3Endpoint = null;

        @Parameter(required = true,
            names = { "--lucene-dir", "--luceneDir" },
            description = "The absolute path to the directory where we'll put the Lucene docs")
        public String luceneDir;

        @Parameter(required = false,
            names = { "--clean-local-dirs", "--cleanLocalDirs" },
            description = "Optional. If enabled, deletes s3LocalDir and luceneDir before running. Default: false")
        public boolean cleanLocalDirs = false;

        @ParametersDelegate
        public ConnectionContext.TargetArgs targetArgs = new ConnectionContext.TargetArgs();

        @Parameter(required = false,
            names = { "--index-allowlist", "--indexAllowlist" },
            description = ("Optional.  List of index names to migrate (e.g. 'logs_2024_01, logs_2024_02').  " +
                "Default: all non-system indices (e.g. those not starting with '.')"))
        public List<String> indexAllowlist = List.of();

        @Parameter(required = false,
            names = { "--max-shard-size-bytes", "--maxShardSizeBytes" },
            description = ("Optional. The maximum shard size, in bytes, to allow when " +
                "performing the document migration.  " +
                "Useful for preventing disk overflow.  Default: 80 * 1024 * 1024 * 1024 (80 GB)"))
        public long maxShardSizeBytes = 80 * 1024 * 1024 * 1024L;

        @Parameter(required = false,
            names = { "--initial-lease-duration", "--initialLeaseDuration" },
            converter = DurationConverter.class,
            description = "Optional. The time that the first attempt to migrate a shard's documents should take.  " +
                "If a process takes longer than this the process will terminate, allowing another process to " +
                "attempt the migration, but with double the amount of time than the last time.  Default: PT10M")
        public Duration initialLeaseDuration = Duration.ofMinutes(10);

        @Parameter(required = false,
            names = { "--otel-collector-endpoint", "--otelCollectorEndpoint" },
            arity = 1,
            description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be"
                + "forwarded. If no value is provided, metrics will not be forwarded.")
        String otelCollectorEndpoint;

        @Parameter(required = false,
        names =  {"--documents-per-bulk-request", "--documentsPerBulkRequest"},
        description = "Optional.  The number of documents to be included within each bulk request sent. " +
            "Default no max (controlled by documents size)")
        int numDocsPerBulkRequest = Integer.MAX_VALUE;

        @Parameter(required = false,
            names = { "--documents-size-per-bulk-request", "--documentsSizePerBulkRequest" },
            description = "Optional. The maximum aggregate document size to be used in bulk requests in bytes. " +
                "Note does not apply to single document requests. Default 10 MiB")
        long numBytesPerBulkRequest = 10 * 1024L * 1024L;

        @Parameter(required = false,
            names = {"--max-connections", "--maxConnections" },
            description = "Optional.  The maximum number of connections to simultaneously " +
                "used to communicate to the target, default 10")
        int maxConnections = 10;

        @Parameter(required = true,
            names = { "--source-version", "--sourceVersion" },
            converter = VersionConverter.class,
            description = ("Version of the source cluster. Required parameter - no default fallback."))
        public Version sourceVersion;

        @Parameter(required = false,
            names = { "--session-name", "--sessionName" },
            description = "Name to disambiguate fleets of RFS workers running against the same target.  " +
                "This will be appended to the name of the index that is used for work coordination.",
            validateValueWith = IndexNameValidator.class)
        public String indexNameSuffix = "";

        @ParametersDelegate
        private DocParams docTransformationParams = new DocParams();

        @ParametersDelegate
        private VersionStrictness versionStrictness = new VersionStrictness();

        @ParametersDelegate
        private ExperimentalArgs experimental = new ExperimentalArgs();

        @Parameter(required = false,
            names = { "--allowed-doc-exception-types", "--allowedDocExceptionTypes" },
            description = "Optional. Comma-separated list of document-level exception types that should be " +
                "treated as successful operations during bulk migration. This enables idempotent migrations by " +
                "allowing specific errors (e.g., 'version_conflict_engine_exception') to be treated as success " +
                "rather than failure. Example: --allowed-doc-exception-types version_conflict_engine_exception")
        public List<String> allowedDocExceptionTypes = List.of();
    }

    public static class ExperimentalArgs {
        @Parameter(required = false,
            names = { "--experimental-previous-snapshot-name", "--experimentalPreviousSnapshotName" },
            description = "Optional. The name of the previous snapshot for delta migration (experimental feature)",
            hidden = true
        )
        public String previousSnapshotName = null;

        @Parameter(required = false,
            names = { "--experimental-delta-mode" },
            converter = DeltaModeConverter.class,
            description = "Experimental delta snapshot migration mode. Requires --base-snapshot-name",
            hidden = true
        )
        public DeltaMode experimentalDeltaMode = null;
    }


    public static class IndexNameValidator implements IValueValidator<String> {
        @Override
        public void validate(String name, String value) throws ParameterException {
            final String REGEX_PATTERN = "[A-Za-z0-9-]*";
            if (!Pattern.compile(REGEX_PATTERN).matcher(value).matches()) {
                throw new ParameterException("Incoming value '" + value + "'did not match regex pattern " + REGEX_PATTERN);
            }
        }
    }

    @Getter
    public static class DocParams implements TransformerParams {
        public String getTransformerConfigParameterArgPrefix() {
            return DOC_CONFIG_PARAMETER_ARG_PREFIX;
        }
        private static final String DOC_CONFIG_PARAMETER_ARG_PREFIX = "doc";

        @Parameter(
                required = false,
                names = { "--" + DOC_CONFIG_PARAMETER_ARG_PREFIX + "-transformer-config-base64",
                        "--" + DOC_CONFIG_PARAMETER_ARG_PREFIX + "TransformerConfigBase64" },
                arity = 1,
                description = "Configuration of doc transformers.  The same contents as --doc-transformer-config but " +
                        "Base64 encoded so that the configuration is easier to pass as a command line parameter.")
        private String transformerConfigEncoded;

        @Parameter(
                required = false,
                names = { "--" + DOC_CONFIG_PARAMETER_ARG_PREFIX + "-transformer-config",
                        "--" + DOC_CONFIG_PARAMETER_ARG_PREFIX + "TransformerConfig" },
                arity = 1,
                description = "Configuration of doc transformers.  Either as a string that identifies the "
                        + "transformer that should be run (with default settings) or as json to specify options "
                        + "as well as multiple transformers to run in sequence.  "
                        + "For json, keys are the (simple) names of the loaded transformers and values are the "
                        + "configuration passed to each of the transformers.")
        private String transformerConfig;

        @Parameter(
                required = false,
                names = { "--" + DOC_CONFIG_PARAMETER_ARG_PREFIX + "-transformer-config-file",
                        "--" + DOC_CONFIG_PARAMETER_ARG_PREFIX + "TransformerConfigFile" },
                arity = 1,
                description = "Path to the JSON configuration file of doc transformers.")
        private String transformerConfigFile;
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
        
        // Validate delta mode parameters
        if (args.experimental.experimentalDeltaMode != null) {
            if (args.experimental.previousSnapshotName == null) {
                throw new ParameterException(
                    "When --experimental-delta-mode is specified, --experimental-previous-snapshot-name must be provided."
                );
            }
            log.warn("EXPERIMENTAL FEATURE: Delta snapshot migration mode {} is enabled. " +
                    "This feature is experimental and should not be used in production.", 
                    args.experimental.experimentalDeltaMode);
        } else if (args.experimental.previousSnapshotName != null) {
            log.error("--experimental-previous-snapshot-name was provided but --experimental-delta-mode is not specified.");
            throw new ParameterException(
                "When --experimental-previous-snapshot-name is specified, --experimental-delta-mode must be provided."
            );
        }

    }

    public static void main(String[] args) throws Exception {
        var workerId = ProcessHelpers.getNodeInstanceName();
        System.err.println("Starting program with: " + String.join(" ", ArgLogUtils.getRedactedArgs(args, ArgNameConstants.CENSORED_TARGET_ARGS)));
        // Ensure that log4j2 doesn't execute shutdown hooks until ours have completed. This means that we need to take
        // responsibility for calling `LogManager.shutdown()` in our own shutdown hook..
        System.setProperty("log4j2.shutdownHookEnabled", "false");
        log.info("Starting RfsMigrateDocuments with workerId=" + workerId);

        Args arguments = EnvVarParameterPuller.injectFromEnv(new Args(), "RFS_");
        var jCommander = JsonCommandLineParser.newBuilder().addObject(arguments).build();
        jCommander.parse(args);

        if (arguments.help) {
            jCommander.getJCommander().usage();
            return;
        }

        validateArgs(arguments);

        if (arguments.cleanLocalDirs) {
            FileSystemUtils.deleteDirectories(arguments.s3LocalDir, arguments.luceneDir);
        }

        var context = makeRootContext(arguments, workerId);
        var luceneDirPath = Paths.get(arguments.luceneDir);
        var snapshotLocalDirPath = arguments.snapshotLocalDir != null ? Paths.get(arguments.snapshotLocalDir) : null;

        var connectionContext = arguments.targetArgs.toConnectionContext();
        OpenSearchClient targetClient = new OpenSearchClientFactory(connectionContext).determineVersionAndCreate();
        var targetVersion = targetClient.getClusterVersion();

        var docTransformerConfig = Optional.ofNullable(TransformerConfigUtils.getTransformerConfig(arguments.docTransformationParams))
            .orElse(DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG);
        log.atInfo().setMessage("Doc Transformations config string: {}")
                .addArgument(docTransformerConfig).log();
        var transformationLoader = new TransformationLoader();
        Supplier<IJsonTransformer> docTransformerSupplier = () -> transformationLoader.getTransformerFactoryLoader(docTransformerConfig);

        var workItemRef = new AtomicReference<IWorkCoordinator.WorkItemAndDuration>();
        var progressCursor = new AtomicReference<WorkItemCursor>();
        var cancellationRunnableRef = new AtomicReference<Runnable>();
        var workItemTimeProvider = new WorkItemTimeProvider();
        var coordinatorFactory = new WorkCoordinatorFactory(targetVersion, arguments.indexNameSuffix);
        var cleanShutdownCompleted = new AtomicBoolean(false);

        try (var workCoordinator = coordinatorFactory.get(
                 new CoordinateWorkHttpClient(connectionContext),
                 TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                 workerId,
                Clock.systemUTC(),
                workItemRef::set);
             var processManager = new LeaseExpireTrigger(
                w -> exitOnLeaseTimeout(
                        workItemRef,
                        workCoordinator,
                        w,
                        progressCursor,
                        workItemTimeProvider,
                        arguments.initialLeaseDuration,
                        () -> Optional.ofNullable(cancellationRunnableRef.get()).ifPresent(Runnable::run),
                        cleanShutdownCompleted,
                        context.getWorkCoordinationContext()::createSuccessorWorkItemsContext),
                Clock.systemUTC());) {
            // Set up a hook to attempt to shut down cleanly (to mark progress in the worker coordination system) in the
            // event of a SIGTERM signal.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Thread.currentThread().setName("Cleanup-Hook-Thread");
                log.atWarn().setMessage("Received shutdown signal. Trying to mark progress and shutdown cleanly.").log();
                try {
                    executeCleanShutdownProcess(workItemRef, progressCursor, workCoordinator, cleanShutdownCompleted,
                            context.getWorkCoordinationContext()::createSuccessorWorkItemsContext);
                    log.atInfo().setMessage("Clean shutdown completed.").log();
                } catch (InterruptedException e) {
                    log.atError().setMessage("Clean exit process was interrupted: {}").addArgument(e).log();
                    // Re-interrupt the thread to maintain interruption state
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.atError().setMessage("Could not complete clean exit process: {}").addArgument(e).log();
                } finally {
                    // Manually flush logs and shutdown log4j after all logging is done
                    LogManager.shutdown();
                }
            }));

            MDC.put(LOGGING_MDC_WORKER_ID, workerId); // I don't see a need to clean this up since we're in main
            
            // Create document exception allowlist from command-line arguments
            Set<String> allowedExceptionTypesSet = new HashSet<>(arguments.allowedDocExceptionTypes);
            DocumentExceptionAllowlist allowlist = new DocumentExceptionAllowlist(allowedExceptionTypesSet);
            if (!allowedExceptionTypesSet.isEmpty()) {
                log.atInfo().setMessage("Document exception allowlist configured with types: {}")
                    .addArgument(String.join(", ", allowedExceptionTypesSet))
                    .log();
            }
            
            DocumentReindexer reindexer = new DocumentReindexer(targetClient,
                arguments.numDocsPerBulkRequest,
                arguments.numBytesPerBulkRequest,
                arguments.maxConnections,
                docTransformerSupplier,
                allowlist);

            var finder = ClusterProviderRegistry.getSnapshotFileFinder(
                    arguments.sourceVersion,
                    arguments.versionStrictness.allowLooseVersionMatches);

            SourceRepo sourceRepo = (snapshotLocalDirPath == null)
                ? S3Repo.create(
                    Paths.get(arguments.s3LocalDir),
                    new S3Uri(arguments.s3RepoUri),
                    arguments.s3Region,
                    Optional.ofNullable(arguments.s3Endpoint).map(URI::create).orElse(null),
                    finder)
                : new FileSystemRepo(snapshotLocalDirPath, finder);

            var repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);

            var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(arguments.sourceVersion, sourceRepo, arguments.versionStrictness.allowLooseVersionMatches);

            var unpackerFactory = new SnapshotShardUnpacker.Factory(
                repoAccessor,
                luceneDirPath
            );

            run(
                new LuceneIndexReader.Factory(sourceResourceProvider),
                reindexer,
                progressCursor,
                workCoordinator,
                arguments.initialLeaseDuration,
                processManager,
                sourceResourceProvider.getIndexMetadata(),
                arguments.snapshotName,
                arguments.experimental.previousSnapshotName,
                arguments.experimental.experimentalDeltaMode,
                arguments.indexAllowlist,
                sourceResourceProvider.getShardMetadata(),
                unpackerFactory,
                arguments.maxShardSizeBytes,
                context,
                cancellationRunnableRef,
                workItemTimeProvider);
            cleanShutdownCompleted.set(true);
        } catch (NoWorkLeftException e) {
            log.atInfo().setMessage("No work left to acquire.  Exiting with error code to signal that.").log();
            cleanShutdownCompleted.set(true);
            System.exit(NO_WORK_LEFT_EXIT_CODE);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Unexpected error running RfsWorker").log();
            throw e;
        }
    }

    private static void executeCleanShutdownProcess(
            AtomicReference<IWorkCoordinator.WorkItemAndDuration> workItemRef,
            AtomicReference<WorkItemCursor> progressCursor,
            IWorkCoordinator coordinator,
            AtomicBoolean cleanShutdownCompleted,
            Supplier<IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext> contextSupplier
    ) throws IOException, InterruptedException {
        if (cleanShutdownCompleted.get())  {
            log.atInfo().setMessage("Clean shutdown already completed").log();
            return;
        }
        if (workItemRef.get() == null || progressCursor.get() == null) {
            log.atInfo().setMessage("No work item or progress cursor found. This may indicate that the task is exiting too early to have progress to mark.").log();
            return;
        }
        var workItemAndDuration = workItemRef.get();
        log.atInfo().setMessage("Marking progress: " + workItemAndDuration.getWorkItem().toString() + ", at doc " + progressCursor.get().getProgressCheckpointNum()).log();
        var successorWorkItem = getSuccessorWorkItemIds(workItemAndDuration, progressCursor.get());

        coordinator.createSuccessorWorkItemsAndMarkComplete(
                workItemAndDuration.getWorkItem().toString(), successorWorkItem, 1, contextSupplier
        );
        cleanShutdownCompleted.set(true);
    }

    @SneakyThrows
    private static void exitOnLeaseTimeout(
            AtomicReference<IWorkCoordinator.WorkItemAndDuration> workItemRef,
            IWorkCoordinator coordinator,
            String workItemId,
            AtomicReference<WorkItemCursor> progressCursorRef,
            WorkItemTimeProvider workItemTimeProvider,
            Duration initialLeaseDuration,
            Runnable cancellationRunnable,
            AtomicBoolean cleanShutdownCompleted,
            Supplier<IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext> contextSupplier) {
        log.atWarn().setMessage("Terminating RfsMigrateDocuments because the lease has expired for {}")
                .addArgument(workItemId)
                .log();
        try {
            if (progressCursorRef.get() != null) {
                log.atWarn().setMessage("Progress cursor set, cancelling active doc migration").log();
                cancellationRunnable.run();
                // Get a new progressCursor after cancellation for most up-to-date checkpoint
                var progressCursor = progressCursorRef.get();
                log.atWarn().setMessage("Progress cursor: {}")
                        .addArgument(progressCursor).log();
                var workItemAndDuration = workItemRef.get();
                if (workItemAndDuration == null) {
                    throw new IllegalStateException("Unexpected state with progressCursor set without a" +
                            "work item");
                }
                log.atWarn().setMessage("Work Item and Duration: {}").addArgument(workItemAndDuration)
                        .log();
                log.atWarn().setMessage("Work Item: {}").addArgument(workItemAndDuration.getWorkItem())
                        .log();
                var successorWorkItemIds = getSuccessorWorkItemIds(workItemAndDuration, progressCursor);
                if (successorWorkItemIds.size() == 1 && workItemId.equals(successorWorkItemIds.get(0))) {
                    log.atWarn().setMessage("No real progress was made for work item: {}. Will retry with larger timeout").addArgument(workItemId).log();
                } else {
                    log.atWarn().setMessage("Successor Work Ids: {}").addArgument(String.join(", ", successorWorkItemIds))
                            .log();
                    var successorNextAcquisitionLeaseExponent = getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, workItemAndDuration.getLeaseExpirationTime());
                    coordinator.createSuccessorWorkItemsAndMarkComplete(
                            workItemId,
                            successorWorkItemIds,
                            successorNextAcquisitionLeaseExponent,
                            contextSupplier
                    );
                }
            } else {
                log.atWarn().setMessage("No progress cursor to create successor work items from. This can happen when" +
                        "downloading and unpacking shard takes longer than the lease").log();
                log.atWarn().setMessage("Skipping creation of successor work item to retry the existing one with more time")
                        .log();
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.atError().setMessage("Exception during exit on lease timeout, clean shutdown failed")
                    .setCause(e).log();
            cleanShutdownCompleted.set(false);
            System.exit(PROCESS_TIMED_OUT_EXIT_CODE);
        }
        cleanShutdownCompleted.set(true);
        System.exit(PROCESS_TIMED_OUT_EXIT_CODE);
    }

    public static int getSuccessorNextAcquisitionLeaseExponent(WorkItemTimeProvider workItemTimeProvider, Duration initialLeaseDuration,
                                       Instant leaseExpirationTime) {
        if (workItemTimeProvider.getLeaseAcquisitionTimeRef().get() == null ||
            workItemTimeProvider.getDocumentMigraionStartTimeRef().get() == null) {
            throw new IllegalStateException("Unexpected state with either leaseAquisitionTime or" +
                    "documentMigrationStartTime as null while creating successor work item");
        }
        var leaseAcquisitionTime = workItemTimeProvider.getLeaseAcquisitionTimeRef().get();
        var documentMigrationStartTime = workItemTimeProvider.getDocumentMigraionStartTimeRef().get();
        var leaseDuration = Duration.between(leaseAcquisitionTime, leaseExpirationTime);
        var leaseDurationFactor = (double) leaseDuration.toMillis() / initialLeaseDuration.toMillis();
        // 2 ^ n = leaseDurationFactor <==> log2(leaseDurationFactor) = n, n >= 0
        var existingNextAcquisitionLeaseExponent = Math.max(Math.round(Math.log(leaseDurationFactor) / Math.log(2)), 0);
        var shardSetupDuration = Duration.between(leaseAcquisitionTime, documentMigrationStartTime);

        var shardSetupDurationFactor = (double) shardSetupDuration.toMillis() / leaseDuration.toMillis();
        int successorShardNextAcquisitionLeaseExponent = (int) existingNextAcquisitionLeaseExponent;
        if (shardSetupDurationFactor < DECREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD && successorShardNextAcquisitionLeaseExponent > 0) {
            // This can happen after a period of slow shard downloads e.g. S3 throttling/slow workers
            // that caused leases to grow larger than desired
            log.atInfo().setMessage("Shard setup took {}% of lease time which is less than target lower threshold of {}%." +
                    "Decreasing successor lease duration exponent.")
                    .addArgument(String.format("%.2f", shardSetupDurationFactor * 100))
                    .addArgument(String.format("%.2f", DECREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD * 100))
                    .log();
            successorShardNextAcquisitionLeaseExponent = successorShardNextAcquisitionLeaseExponent - 1;
        } else if (shardSetupDurationFactor > INCREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD) {
            log.atInfo().setMessage("Shard setup took {}% of lease time which is more than target upper threshold of {}%." +
                            "Increasing successor lease duration exponent.")
                    .addArgument(String.format("%.2f", shardSetupDurationFactor * 100))
                    .addArgument(String.format("%.2f", INCREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD * 100))
                    .log();
            successorShardNextAcquisitionLeaseExponent = successorShardNextAcquisitionLeaseExponent + 1;
        }

        log.atDebug().setMessage("SuccessorNextAcquisitionLeaseExponent calculated values:" +
                        "\nleaseAcquisitionTime:{}" +
                        "\ndocumentMigrationStartTime:{}" +
                        "\nleaseDuration:{}" +
                        "\nleaseDurationFactor:{}" +
                        "\nexistingNextAcquisitionLeaseExponent:{}" +
                        "\nshardSetupDuration:{}" +
                        "\nshardSetupDurationFactor:{}" +
                        "\nsuccessorShardNextAcquisitionLeaseExponent:{}")
                .addArgument(leaseAcquisitionTime)
                .addArgument(documentMigrationStartTime)
                .addArgument(leaseDuration)
                .addArgument(leaseDurationFactor)
                .addArgument(existingNextAcquisitionLeaseExponent)
                .addArgument(shardSetupDuration)
                .addArgument(shardSetupDurationFactor)
                .addArgument(successorShardNextAcquisitionLeaseExponent)
                .log();

        return successorShardNextAcquisitionLeaseExponent;
    }

    private static List<String> getSuccessorWorkItemIds(IWorkCoordinator.WorkItemAndDuration workItemAndDuration, WorkItemCursor progressCursor) {
        if (workItemAndDuration == null) {
            throw new IllegalStateException("Unexpected worker coordination state. Expected workItem set when progressCursor not null.");
        }
        var workItem = workItemAndDuration.getWorkItem();
        // Set successor as same last checkpoint Num, this will ensure we process every document fully in cases where there is a 1:many doc split
        var successorStartingCheckpointNum = progressCursor.getProgressCheckpointNum();
        var successorWorkItem = new IWorkCoordinator.WorkItemAndDuration
                .WorkItem(workItem.getIndexName(), workItem.getShardNumber(),
                successorStartingCheckpointNum);
        ArrayList<String> successorWorkItemIds = new ArrayList<>();
        successorWorkItemIds.add(successorWorkItem.toString());
        return successorWorkItemIds;
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


    public static CompletionStatus run(LuceneIndexReader.Factory readerFactory,
                                       DocumentReindexer reindexer,
                                       AtomicReference<WorkItemCursor> progressCursor,
                                       IWorkCoordinator workCoordinator,
                                       Duration maxInitialLeaseDuration,
                                       LeaseExpireTrigger leaseExpireTrigger,
                                       IndexMetadata.Factory indexMetadataFactory,
                                       String snapshotName,
                                       String previousSnapshotName,
                                       DeltaMode deltaMode,
                                       List<String> indexAllowlist,
                                       ShardMetadata.Factory shardMetadataFactory,
                                       SnapshotShardUnpacker.Factory unpackerFactory,
                                       long maxShardSizeBytes,
                                       RootDocumentMigrationContext rootDocumentContext,
                                       AtomicReference<Runnable> cancellationRunnable,
                                       WorkItemTimeProvider timeProvider)
        throws IOException, InterruptedException, NoWorkLeftException
    {
        var scopedWorkCoordinator = new ScopedWorkCoordinator(workCoordinator, leaseExpireTrigger);
        confirmShardPrepIsComplete(indexMetadataFactory,
            snapshotName,
            indexAllowlist,
            scopedWorkCoordinator,
            rootDocumentContext
        );
        if (!workCoordinator.workItemsNotYetComplete(
            rootDocumentContext.getWorkCoordinationContext()::createItemsPendingContext
        )) {
            throw new NoWorkLeftException("No work items are pending/all work items have been processed.  Returning.");
        }
        Function<String, BiFunction<String, Integer, ShardMetadata>> shardMetadataSupplierFactory = snapshot -> (indexName, shardId) -> {
            var shardMetadata = shardMetadataFactory.fromRepo(snapshot, indexName, shardId);
            log.atInfo()
                .setMessage("Shard size: {} for snapshotName={} indexName={} shardId={}")
                .addArgument(shardMetadata.getTotalSizeBytes())
                .addArgument(snapshot)
                .addArgument(indexName)
                .addArgument(shardId)
                .log();
            if (shardMetadata.getTotalSizeBytes() > maxShardSizeBytes) {
                throw new DocumentsRunner.ShardTooLargeException(shardMetadata.getTotalSizeBytes(), maxShardSizeBytes);
            }
            return shardMetadata;
        };

        var shardMetadataSupplier = shardMetadataSupplierFactory.apply(snapshotName);
        var strategy = (previousSnapshotName == null)
            ? new RegularDocumentReaderEngine(shardMetadataSupplier)
            : new DeltaDocumentReaderEngine(
                shardMetadataSupplierFactory.apply(previousSnapshotName), shardMetadataSupplier, deltaMode);

        DocumentsRunner runner = new DocumentsRunner(scopedWorkCoordinator,
            maxInitialLeaseDuration,
            reindexer,
            unpackerFactory,
            readerFactory,
            progressCursor::set,
            cancellationRunnable::set,
            timeProvider,
            strategy);
        return runner.migrateNextShard(rootDocumentContext::createReindexContext);
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
                log.atInfo().setMessage("After {} another process holds the lock for setting up the shard work items." +
                        "  Waiting {} ms before trying again.")
                    .addArgument(shardSetupAttemptNumber)
                    .addArgument(lockRenegotiationMillis)
                    .log();
                Thread.sleep(lockRenegotiationMillis);
                lockRenegotiationMillis *= 2;
            }
        }
    }
}
