package org.opensearch.migrations;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.opensearch.migrations.arguments.ArgLogUtils;
import org.opensearch.migrations.arguments.ArgNameConstants;
import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.GcsRepo;
import org.opensearch.migrations.bulkload.common.GcsUri;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.SnapshotReadFailures;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationBootstrap;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.solr.SolrBackupIndexMetadataFactory;
import org.opensearch.migrations.bulkload.solr.SolrBackupLayout;
import org.opensearch.migrations.bulkload.solr.SolrMultiCollectionSource;
import org.opensearch.migrations.bulkload.solr.SolrSchemaXmlParser;
import org.opensearch.migrations.bulkload.solr.SolrShardPartition;
import org.opensearch.migrations.bulkload.solr.SolrSnapshotReader;
import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;
import org.opensearch.migrations.bulkload.tracing.RfsContexts;
import org.opensearch.migrations.bulkload.workcoordination.CoordinateWorkHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.WorkCoordinatorFactory;
import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.ShardWorkPreparer;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
import org.opensearch.migrations.jcommander.EnvVarParameterPuller;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;
import org.opensearch.migrations.reindexer.tracing.RootDocumentMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.OtelCollectorEndpoints;
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
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.slf4j.MDC;

@Slf4j
public class RfsMigrateDocuments {
    public static final int PROCESS_TIMED_OUT_EXIT_CODE = 2;
    public static final int NO_WORK_LEFT_EXIT_CODE = 3;
    public static final int NO_WORK_AVAILABLE_EXIT_CODE = 4;
    // Keep harmonized with the metadata command's MigratorEvaluatorBase.SNAPSHOT_READ_FAILED_EXIT_CODE.
    public static final int SNAPSHOT_READ_FAILED_EXIT_CODE = 5;

    // Arbitrary value, increasing from 5 to 15 seconds due to prevalence of clock skew exceptions
    // observed on production clusters during migrations
    public static final int TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 15;
    public static final String LOGGING_MDC_WORKER_ID = "workerId";

    // Decrease successor nextAcquisitionLeaseExponent if shard setup takes less than 2.5% of total lease time
    // Increase successor nextAcquisitionLeaseExponent if shard setup takes more than 10% of lease total time
    private static final double DECREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD = 0.025;
    private static final double INCREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD = 0.1;

    public static final String DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG = null;

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
                    String.join(", ", Arrays.stream(DeltaMode.values())
                        .map(Enum::name)
                        .toArray(String[]::new)));
            }
        }
    }

    public enum ServerGeneratedIdMode {
        AUTO,   // Auto-detect serverless TIMESERIES/VECTOR collections and enable
        ALWAYS, // Always use server-generated IDs
        NEVER   // Always preserve source IDs
    }

    public enum EmitDocTypeMode {
        AUTO, // Emit _type only when source is ES <= 6 and a doc transformer is configured
        ON,   // Always emit _type into bulk action-line metadata
        OFF   // Never emit _type
    }

    public static class Args {
        /** Default maximum documents per bulk batch. */
        static final int DEFAULT_MAX_DOCS_PER_BATCH = Integer.MAX_VALUE;
        /** Default maximum bytes per bulk batch (10 MiB). */
        static final long DEFAULT_MAX_BYTES_PER_BATCH = 10L * 1024 * 1024;
        /** Default number of concurrent batches in flight. */
        static final int DEFAULT_BATCH_CONCURRENCY = 10;

        @Parameter(
            names = {"--help", "-h"},
            help = true,
            description = "Displays information about how to use this tool")
        private boolean help;

        @Parameter(required = false,
            names = { "--snapshot-name", "--snapshotName" },
            description = "The name of the snapshot to migrate. Required when --source-type is SNAPSHOT.")
        public String snapshotName;

        @Parameter(required = false,
            names = { "--repo-uri", "--s3-repo-uri", "--s3RepoUri", "--snapshot-local-dir", "--snapshotLocalDir", "--file-system-repo-path" },
            description = ("Repository URI. Schemes: file:///path, s3://bucket/path, gs://bucket/path (or bare absolute path)"))
        public String repoUri = null;

        @Parameter(required = false,
            names = { "--local-dir", "--s3-local-dir", "--s3LocalDir", "--gcs-local-dir", "--gcsLocalDir" },
            description = ("The absolute path to the directory on local disk to download remote repo files to.  " +
                "Required for s3:// and gs:// repos."))
        public String localDir = null;

        @Parameter(required = false,
            names = { "--s3-region", "--s3Region" },
            description = ("The AWS Region the S3 bucket is in, like: us-east-2.  Required for s3:// repos."))
        public String s3Region = null;

        @Parameter(required = false,
            names = { "--endpoint", "--s3-endpoint", "--s3Endpoint" },
            description = ("Custom endpoint for the repository service (e.g. LocalStack for S3, fake-gcs-server for GCS)"))
        public String endpoint = null;

        @Parameter(required = false,
            names = { "--lucene-dir", "--luceneDir" },
            description = "The absolute path to the directory where we'll put the Lucene docs. Required when --source-type is SNAPSHOT.")
        public String luceneDir;

        @Parameter(required = false,
            names = { "--clean-local-dirs", "--cleanLocalDirs" },
            description = "Optional. If enabled, deletes localDir and luceneDir before running. Default: false")
        public boolean cleanLocalDirs = false;

        @ParametersDelegate
        public ConnectionContext.TargetArgs targetArgs = new ConnectionContext.TargetArgs();

        @ParametersDelegate
        public ConnectionContext.CoordinatorArgs coordinatorArgs = new ConnectionContext.CoordinatorArgs();

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

        @Parameter(
            required = false,
            names = { "--otel-trace-collector-endpoint", "--otelTraceCollectorEndpoint" },
            arity = 1,
            description = "Endpoint for the OpenTelemetry Collector to which traces should be forwarded. " +
                "Omit this option to disable trace export.")
        String otelTraceCollectorEndpoint;

        @Parameter(
            required = false,
            names = { "--otel-metrics-collector-endpoint", "--otelMetricsCollectorEndpoint" },
            arity = 1,
            description = "Endpoint for the OpenTelemetry Collector to which metrics should be forwarded. " +
                "Omit this option to disable metric export.")
        String otelMetricsCollectorEndpoint;

        @Parameter(required = false,
        names =  {"--documents-per-bulk-request", "--documentsPerBulkRequest"},
        description = "Optional.  The number of documents to be included within each bulk request sent. " +
            "Default " + DEFAULT_MAX_DOCS_PER_BATCH)
        int numDocsPerBulkRequest = DEFAULT_MAX_DOCS_PER_BATCH;

        @Parameter(required = false,
            names = { "--documents-size-per-bulk-request", "--documentsSizePerBulkRequest" },
            description = "Optional. The maximum aggregate document size to be used in bulk requests in bytes. " +
                "Note does not apply to single document requests. Default 10 MiB")
        long numBytesPerBulkRequest = DEFAULT_MAX_BYTES_PER_BATCH;

        @Parameter(required = false,
            names = {"--max-connections", "--maxConnections" },
            description = "Optional.  The maximum number of connections to simultaneously " +
                "used to communicate to the target, default " + DEFAULT_BATCH_CONCURRENCY)
        int maxConnections = DEFAULT_BATCH_CONCURRENCY;

        @Parameter(required = false,
            names = { "--server-generated-ids" },
            description = "Optional. Controls document ID generation on target. " +
                "AUTO (default): auto-detect serverless TIMESERIES/VECTOR collections and enable server-generated IDs. " +
                "ALWAYS: always use server-generated IDs. " +
                "NEVER: always preserve source IDs (may fail on serverless TIMESERIES/VECTOR).")
        public ServerGeneratedIdMode serverGeneratedIds = ServerGeneratedIdMode.AUTO;

        @Parameter(required = false,
            names = { "--source-version", "--sourceVersion" },
            converter = VersionConverter.class,
            description = ("Version of the source cluster. Required when --source-type is SNAPSHOT."))
        public Version sourceVersion;

        @Parameter(required = false,
            names = { "--session-name", "--sessionName" },
            description = "Name to disambiguate fleets of RFS workers running against the same target.  " +
                "This will be appended to the name of the index that is used for work coordination.",
            validateValueWith = IndexNameValidator.class)
        public String indexNameSuffix = "";

        // Defaults mirror OpenSearchWorkCoordinator.CompletionRetryConfig.DEFAULT
        // (defined again as literals because annotations require compile time constants)
        @Parameter(required = false,
            names = { "--coordinator-retry-max-retries" },
            description = "Optional. Maximum number of retries when marking work items as completed on the coordinator. Default: 7")
        public int coordinatorRetryMaxRetries = 7;

        @Parameter(required = false,
            names = { "--coordinator-retry-initial-delay-ms" },
            description = "Optional. Initial delay in milliseconds for coordinator completion retries (doubles each attempt). Default: 1000")
        public long coordinatorRetryInitialDelayMs = 1000;

        @Parameter(required = false,
            names = { "--coordinator-retry-max-delay-ms" },
            description = "Optional. Maximum delay in milliseconds for any single coordinator completion retry. Default: 64000")
        public long coordinatorRetryMaxDelayMs = 64_000;

        @Parameter(required = false,
            names = { "--emit-doc-type" },
            description = "Optional. Controls whether the ES _type field is propagated into bulk action-line metadata. " +
                "AUTO (default): emit _type only when the source is ES 6 or older AND a document transformer is " +
                "configured (e.g. TypeMappingSanitizationTransformerProvider for multi-type indices). " +
                "ON: always emit _type. OFF: never emit _type.")
        public EmitDocTypeMode emitDocType = EmitDocTypeMode.AUTO;

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

        @Parameter(required = false,
            names = { "--enable-sourceless-migrations" },
            description = "Enable migration of indices that have _source disabled. When enabled, documents " +
                "are reconstructed from stored fields and doc_values instead of _source. " +
                "Without this flag, migration of sourceless indices will fail with an error.",
            arity = 0
        )
        public boolean enableSourcelessMigrations = false;

        @Parameter(required = false,
            names = { "--use-recovery-source" },
            description = "When enabled, treat the _recovery_source stored field (present in ES 7+ / OpenSearch " +
                "snapshots with soft-deletes) as _source. This field is transient and may not be present for " +
                "all documents, so results can be inconsistent. Use only when reconstruction from doc_values " +
                "and stored fields is insufficient.",
            arity = 0
        )
        public boolean useRecoverySource = false;

        @Parameter(required = false,
            names = { "--position-gap-stopword", "--positionGapStopword" },
            description = "Optional. Token used to fill skipped Lucene positions when reconstructing analyzed-text " +
                "fields from postings. ES preserves position increments for stop-word-filtered tokens (e.g. " +
                "\"i like the tree\" with stopword \"the\" indexes at positions 0,1,3 — position 2 is consumed " +
                "by \"the\" but the term itself is dropped). Without this flag the reconstructor joins on spaces " +
                "and OS re-tokenizes the document at consecutive positions [0,1,2], silently changing slop / " +
                "proximity / phrase semantics. The reconstructor splices this token into the gap so OS — assumed " +
                "to have the same token configured as a stopword — re-creates the original [0,1,3] postings while " +
                "indexing. The token MUST be on the target's stopword list or it leaks into search results; " +
                "'a' is a safe default for the english / standard analyzers. " +
                "Pass an empty string to opt out and fall back to the legacy multi-space behaviour. " +
                "Default: 'a'."
        )
        public String positionGapStopword = "a";
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
        // Solr backup path
        if (args.sourceVersion != null && args.sourceVersion.getFlavor() == Flavor.SOLR) {
            if (args.repoUri == null) {
                throw new ParameterException(
                    "For Solr backup migration, provide --repo-uri with a file:// or s3:// scheme."
                );
            }
            var parsedUri = RepoUri.parse(args.repoUri);
            if (parsedUri instanceof RepoUri.S3RepoUri && (args.localDir == null || args.s3Region == null)) {
                throw new ParameterException(
                    "For Solr backup migration with S3, --local-dir and --s3-region are required."
                );
            }
            if (args.coordinatorArgs.host == null) {
                throw new ParameterException(
                    "When source version is SOLR, --coordinator-host must be provided for work coordination."
                );
            }
            return;
        }

        if (args.snapshotName == null) {
            throw new ParameterException("--snapshot-name is required when --source-type is SNAPSHOT.");
        }
        if (args.luceneDir == null) {
            throw new ParameterException("--lucene-dir is required when --source-type is SNAPSHOT.");
        }
        if (args.sourceVersion == null) {
            throw new ParameterException("--source-version is required when --source-type is SNAPSHOT.");
        }

        if (args.repoUri == null) {
            throw new ParameterException("--repo-uri is required.");
        }

        var parsedUri = RepoUri.parse(args.repoUri);
        if (parsedUri instanceof RepoUri.S3RepoUri && (args.localDir == null || args.s3Region == null)) {
            throw new ParameterException(
                "If an s3 repo is being used, --s3-region and --local-dir must be set."
            );
        }
        if (parsedUri instanceof RepoUri.GcsRepoUri && args.localDir == null) {
            throw new ParameterException(
                "If a GCS repo is being used, --local-dir must be set."
            );
        }
        
        // Validate delta mode parameters
        if (args.experimental.experimentalDeltaMode != null) {
            if (args.experimental.previousSnapshotName == null) {
                throw new ParameterException(
                    "When --experimental-delta-mode is specified, --experimental-previous-snapshot-name must be provided."
                );
            }
            log.atWarn().setMessage("EXPERIMENTAL FEATURE: Delta snapshot migration mode {} is enabled. " +
                    "This feature is experimental and should not be used in production.")
                    .addArgument(args.experimental.experimentalDeltaMode).log();
        } else if (args.experimental.previousSnapshotName != null) {
            log.atError().setMessage("--experimental-previous-snapshot-name was provided but --experimental-delta-mode is not specified.").log();
            throw new ParameterException(
                "When --experimental-previous-snapshot-name is specified, --experimental-delta-mode must be provided."
            );
        }

        // Validate coordinator args - the ConnectionContext constructor will validate auth param consistency,
        // but we log here if coordinator is enabled for visibility
        if (args.coordinatorArgs.isEnabled()) {
            log.atInfo().setMessage("Coordinator connection enabled with host: {}").addArgument(args.coordinatorArgs.host).log();
        }
    }

    @FunctionalInterface
    interface MigrationSourceFactory {
        CompletionStatus buildAndRun(
            IWorkCoordinator workCoordinator,
            LeaseExpireTrigger processManager,
            AtomicReference<WorkItemCursor> progressCursor,
            AtomicReference<Runnable> cancellationRunnableRef,
            WorkItemTimeProvider workItemTimeProvider
        ) throws IOException, InterruptedException, NoWorkLeftException;
    }

    public static void main(String[] args) throws Exception {
        var workerId = ProcessHelpers.getNodeInstanceName();
        System.err.println("Starting program with: " + String.join(" ", ArgLogUtils.getRedactedArgs(args, ArgNameConstants.CENSORED_ARGS)));
        System.setProperty("log4j2.shutdownHookEnabled", "false");
        log.atInfo().setMessage("Starting RfsMigrateDocuments with workerId={}").addArgument(workerId).log();

        Args arguments = EnvVarParameterPuller.injectFromEnv(new Args(), "RFS_");
        var jCommander = JsonCommandLineParser.newBuilder().addObject(arguments).build();
        jCommander.parse(args);

        if (arguments.help) {
            jCommander.getJCommander().usage();
            return;
        }

        validateArgs(arguments);

        // Forward the position-gap stopword to the reconstruction layer via system property —
        // LuceneLeafReader.joinWithOffsets reads it from RfsTunables.positionGapStopword().
        // Done here (rather than threaded through DocumentMigrationBootstrap) to match the
        // existing tunables pattern for rfs.reader.parallelism, keeping the streaming-postings
        // hot path free of per-call config plumbing.
        if (arguments.experimental.positionGapStopword != null
                && !arguments.experimental.positionGapStopword.isBlank()) {
            System.setProperty(
                org.opensearch.migrations.bulkload.lucene.RfsTunables.POSITION_GAP_STOPWORD_PROP,
                arguments.experimental.positionGapStopword);
            log.atInfo().setMessage("Position-gap stopword filler enabled: '{}'")
                .addArgument(arguments.experimental.positionGapStopword).log();
        }

        if (arguments.cleanLocalDirs) {
            FileSystemUtils.deleteDirectories(arguments.localDir, arguments.luceneDir);
        }

        var context = makeRootContext(arguments, workerId);

        var targetConnectionContext = arguments.targetArgs.toConnectionContext();
        var targetClientFactory = new OpenSearchClientFactory(targetConnectionContext, arguments.maxConnections);
        OpenSearchClient targetClient = targetClientFactory.determineVersionAndCreate();
        var targetVersion = targetClient.getClusterVersion();

        boolean useServerGeneratedIds = switch (arguments.serverGeneratedIds) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> {
                var collectionType = targetClientFactory.detectServerlessCollectionType();
                if (collectionType.requiresServerGeneratedIds()) {
                    log.atInfo().setMessage("Auto-enabling server-generated IDs for {} serverless collection").addArgument(collectionType).log();
                    yield true;
                }
                yield false;
            }
        };

        var docTransformerConfig = TransformerConfigUtils.getTransformerConfig(arguments.docTransformationParams);
        if (docTransformerConfig != null) {
            log.atInfo().setMessage("Doc Transformations config string: {}")
                    .addArgument(docTransformerConfig).log();
        } else {
            log.atInfo().setMessage("No doc transformations configured; using raw-bytes fast path").log();
        }
        var transformationLoader = new TransformationLoader();
        Supplier<IJsonTransformer> docTransformerSupplier = docTransformerConfig == null
            ? null
            : () -> transformationLoader.getTransformerFactoryLoader(docTransformerConfig);

        boolean emitDocType = resolveEmitDocType(
            arguments.emitDocType, arguments.sourceVersion, docTransformerConfig);

        MigrationSourceFactory sourceFactory;
        if (arguments.sourceVersion != null && arguments.sourceVersion.getFlavor() == Flavor.SOLR) {
            sourceFactory = buildSolrSourceFactory(arguments, targetClient, docTransformerSupplier, useServerGeneratedIds, context);
        } else {
            sourceFactory = buildElasticsearchSourceFactory(arguments, targetClient,
                docTransformerSupplier, useServerGeneratedIds, emitDocType, context);
        }

        var coordinatorInfo = resolveCoordinatorConnection(arguments, targetConnectionContext, targetVersion);
        runMigration(workerId, arguments, coordinatorInfo, context, sourceFactory);
    }

    private static void runMigration(
        String workerId,
        Args arguments,
        CoordinatorInfo coordinatorInfo,
        RootDocumentMigrationContext context,
        MigrationSourceFactory sourceFactory
    ) throws Exception {
        var workItemRef = new AtomicReference<IWorkCoordinator.WorkItemAndDuration>();
        var progressCursor = new AtomicReference<WorkItemCursor>();
        var cancellationRunnableRef = new AtomicReference<Runnable>();
        var workItemTimeProvider = new WorkItemTimeProvider();
        var completionRetryConfig = buildCompletionRetryConfig(arguments);
        var coordinatorFactory = new WorkCoordinatorFactory(
            coordinatorInfo.version(), arguments.indexNameSuffix, completionRetryConfig);
        var cleanShutdownCompleted = new AtomicBoolean(false);

        try (var workCoordinator = coordinatorFactory.get(
                 new CoordinateWorkHttpClient(coordinatorInfo.connectionContext()),
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
                        context.getWorkCoordinationContext()::createSuccessorWorkItemsContext,
                        context.getWorkCoordinationContext()::createReleaseWorkItemContext),
                Clock.systemUTC());) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Thread.currentThread().setName("Cleanup-Hook-Thread");
                log.atWarn().setMessage("Received shutdown signal. Trying to mark progress and shutdown cleanly.").log();
                try {
                    executeCleanShutdownProcess(workItemRef, progressCursor, workCoordinator, cleanShutdownCompleted,
                            context.getWorkCoordinationContext()::createSuccessorWorkItemsContext,
                            context.getWorkCoordinationContext()::createReleaseWorkItemContext);
                    log.atInfo().setMessage("Clean shutdown completed.").log();
                } catch (InterruptedException e) {
                    log.atError().setMessage("Clean exit process was interrupted: {}").addArgument(e).log();
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.atError().setMessage("Could not complete clean exit process: {}").addArgument(e).log();
                } finally {
                    LogManager.shutdown();
                }
            }));

            MDC.put(LOGGING_MDC_WORKER_ID, workerId);

            var status = sourceFactory.buildAndRun(
                workCoordinator, processManager, progressCursor, cancellationRunnableRef, workItemTimeProvider);
            cleanShutdownCompleted.set(true);
            if (status == CompletionStatus.NOTHING_DONE) {
                log.atInfo().setMessage("Work exists but none available to this worker. Exiting with exit code " + NO_WORK_AVAILABLE_EXIT_CODE).log();
                System.exit(NO_WORK_AVAILABLE_EXIT_CODE);
            }
        } catch (NoWorkLeftException e) {
            log.atInfo().setMessage("No work left to acquire. Exiting with exit code " + NO_WORK_LEFT_EXIT_CODE).log();
            cleanShutdownCompleted.set(true);
            System.exit(NO_WORK_LEFT_EXIT_CODE);
        } catch (Exception e) {
            var snapshotReadExitCode = classifySnapshotReadFailure(e, arguments);
            if (snapshotReadExitCode.isPresent()) {
                // A non-retriable snapshot read failure (the snapshot's repo/index/shard metadata or
                // blob data could not be read). The labeled reason/path/context was already logged at
                // ERROR by classifySnapshotReadFailure so it is visible in the workflow log and
                // CloudWatch even if the pod is terminated immediately afterward. We exit explicitly
                // (rather than rethrowing to the JVM uncaught handler) to flush the appenders and return
                // a deterministic exit code the workflow can branch on.
                LogManager.shutdown();
                System.exit(snapshotReadExitCode.getAsInt());
            }
            log.atError().setCause(e).setMessage("Unexpected error running RfsWorker").log();
            throw e;
        }
    }

    /**
     * If {@code e} (or a wrapped cause) is a non-retriable snapshot read failure, log a labeled ERROR
     * line naming the reason, snapshot path, and context, then return the dedicated
     * {@link #SNAPSHOT_READ_FAILED_EXIT_CODE}; otherwise return empty so the caller rethrows. Extracted
     * from the {@code runMigration} catch block so the classification/logging is unit-testable without
     * forking a JVM — the caller performs the actual {@link System#exit}. Mirrors the metadata
     * command's {@code MigratorEvaluatorBase.classifyFailure}.
     */
    static OptionalInt classifySnapshotReadFailure(Exception e, Args arguments) {
        var snapshotReadFailure = SnapshotReadFailures.find(e);
        if (snapshotReadFailure == null) {
            return OptionalInt.empty();
        }
        var repo = arguments.snapshotLocalDir != null ? arguments.snapshotLocalDir : arguments.s3RepoUri;
        log.atError().setCause(e)
            .setMessage("{}")
            .addArgument(SnapshotReadFailures.describe(
                snapshotReadFailure, arguments.snapshotName, repo, arguments.s3Region))
            .log();
        return OptionalInt.of(SNAPSHOT_READ_FAILED_EXIT_CODE);
    }

    private static MigrationSourceFactory buildElasticsearchSourceFactory(
        Args arguments,
        OpenSearchClient targetClient,
        Supplier<IJsonTransformer> docTransformerSupplier,
        boolean useServerGeneratedIds,
        boolean emitDocType,
        RootDocumentMigrationContext context
    ) {
        return (workCoordinator, processManager, progressCursor, cancellationRunnableRef, workItemTimeProvider) -> {
            DocumentExceptionAllowlist allowlist = buildDocumentExceptionAllowlist(arguments);

            var luceneDirPath = Paths.get(arguments.luceneDir);

            var finder = SnapshotReaderRegistry.getSnapshotFileFinder(
                    arguments.sourceVersion,
                    arguments.versionStrictness.allowLooseVersionMatches);

            var parsedUri = RepoUri.parse(arguments.repoUri);
            SourceRepo sourceRepo = switch (parsedUri) {
                case RepoUri.FileRepoUri f -> new FileSystemRepo(Paths.get(f.path()), finder);
                case RepoUri.GcsRepoUri g -> GcsRepo.create(
                    Paths.get(arguments.localDir),
                    new GcsUri(g.rawUri()),
                    arguments.endpoint,
                    finder);
                case RepoUri.S3RepoUri s -> S3Repo.create(
                    Paths.get(arguments.localDir),
                    s.s3Uri(),
                    arguments.s3Region,
                    Optional.ofNullable(arguments.endpoint).map(URI::create).orElse(null),
                    finder);
            };

            var sourceResourceProvider = SnapshotReaderRegistry.getSnapshotReader(
                arguments.sourceVersion, sourceRepo, arguments.versionStrictness.allowLooseVersionMatches);

            var extractor = SnapshotExtractor.create(
                arguments.sourceVersion, sourceResourceProvider, sourceRepo);

            var sourceBuilder = LuceneSnapshotSource.builder(extractor, arguments.snapshotName, luceneDirPath)
                .maxShardSizeBytes(arguments.maxShardSizeBytes)
                .useRecoverySource(arguments.experimental.useRecoverySource)
                .emitDocType(emitDocType);
            if (arguments.experimental.previousSnapshotName != null && arguments.experimental.experimentalDeltaMode != null) {
                sourceBuilder.delta(arguments.experimental.previousSnapshotName,
                    arguments.experimental.experimentalDeltaMode,
                    () -> new RfsContexts.DeltaStreamContext(context, null));
            }
            if (arguments.experimental.enableSourcelessMigrations) {
                var indexMetadataFactory = sourceResourceProvider.getIndexMetadata();
                Map<String, Optional<FieldMappingContext>> cache = new java.util.concurrent.ConcurrentHashMap<>();
                sourceBuilder.sourcelessMappingContextProvider(indexName -> cache.computeIfAbsent(indexName, name -> {
                    try {
                        var meta = indexMetadataFactory.fromRepo(arguments.snapshotName, name);
                        if (!meta.needsSourceReconstruction()) return Optional.empty();
                        return Optional.of(new FieldMappingContext(meta.getMappings()));
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read metadata for index " + name, e);
                    }
                }).orElse(null));
            }
            var documentSource = sourceBuilder.build();

            return prepareAndMigrate(documentSource,
                workCoordinator, processManager, targetClient, docTransformerSupplier,
                useServerGeneratedIds, allowlist, progressCursor, cancellationRunnableRef,
                workItemTimeProvider, arguments, context);
        };
    }

    private static CompletionStatus prepareAndMigrate(
        org.opensearch.migrations.bulkload.pipeline.source.DocumentSource documentSource,
        IWorkCoordinator workCoordinator,
        LeaseExpireTrigger processManager,
        OpenSearchClient targetClient,
        Supplier<IJsonTransformer> docTransformerSupplier,
        boolean useServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        AtomicReference<WorkItemCursor> progressCursor,
        AtomicReference<Runnable> cancellationRunnableRef,
        WorkItemTimeProvider workItemTimeProvider,
        Args arguments,
        RootDocumentMigrationContext context
    ) throws IOException, InterruptedException, NoWorkLeftException {
        var scopedWorkCoordinator = prepareWorkCoordination(
            workCoordinator, processManager, documentSource,
            arguments.indexAllowlist, context);

        var runner = DocumentMigrationBootstrap.builder()
            .documentSource(documentSource)
            .targetClient(targetClient)
            .maxDocsPerBatch(arguments.numDocsPerBulkRequest)
            .maxBytesPerBatch(arguments.numBytesPerBulkRequest)
            .batchConcurrency(arguments.maxConnections)
            .transformerSupplier(docTransformerSupplier)
            .allowServerGeneratedIds(useServerGeneratedIds)
            .allowlist(allowlist)
            .workCoordinator(scopedWorkCoordinator)
            .workItemTimeProvider(workItemTimeProvider)
            .maxInitialLeaseDuration(arguments.initialLeaseDuration)
            .cursorConsumer(progressCursor::set)
            .cancellationTriggerConsumer(cancellationRunnableRef::set)
            .build();

        return runner.migrateOneShard(context::createReindexContext);
    }

    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    private record CoordinatorInfo(ConnectionContext connectionContext, Version version) {}

    /**
     * Calculates the approximate total retry window in seconds based on exponential backoff with a max delay cap.
     * This sums the backoff delays (initial * 2^n, capped at maxDelay) across all retry attempts.
     * Does not include request execution time or network latency.
     * Logic matches the runtime retry implementation in OpenSearchWorkCoordinator.retryWithExponentialBackoff()
     */
    private static long calculateTotalRetryWindowSeconds(OpenSearchWorkCoordinator.CompletionRetryConfig config) {
        long totalMs = 0;
        long delay = config.initialDelayMs();
        for (int i = 0; i < config.maxRetries(); i++) {
            totalMs += Math.min(delay, config.maxDelayMs());
            
            // Update delay for next iteration
            if (delay < config.maxDelayMs()) {
                delay = (delay > config.maxDelayMs() / OpenSearchWorkCoordinator.EXPONENTIAL_BACKOFF_MULTIPLIER)
                    ? config.maxDelayMs()
                    : delay * OpenSearchWorkCoordinator.EXPONENTIAL_BACKOFF_MULTIPLIER;
            }
        }
        return totalMs / 1000;
    }

    /**
     * Build the coordinator completion-retry configuration from CLI args and log its summary.
     * Shared between the ES and Solr backfill paths.
     */
    private static OpenSearchWorkCoordinator.CompletionRetryConfig buildCompletionRetryConfig(Args arguments) {
        var completionRetryConfig = new OpenSearchWorkCoordinator.CompletionRetryConfig(
            arguments.coordinatorRetryMaxRetries,
            arguments.coordinatorRetryInitialDelayMs,
            arguments.coordinatorRetryMaxDelayMs);
        log.atInfo().setMessage("Coordinator completion retry config: maxRetries={}, initialDelay={}ms, maxDelay={}ms, totalWindow=~{}s")
            .addArgument(completionRetryConfig.maxRetries())
            .addArgument(completionRetryConfig.initialDelayMs())
            .addArgument(completionRetryConfig.maxDelayMs())
            .addArgument(calculateTotalRetryWindowSeconds(completionRetryConfig))
            .log();
        return completionRetryConfig;
    }

    /**
     * Resolve {@link EmitDocTypeMode} to a boolean. AUTO enables _type emission only when the
     * source is ES 6 or older AND a document transformer is configured — the combination that
     * exercises type-mapping transformers (e.g. TypeMappingSanitizationTransformerProvider) on
     * multi-type indices.
     */
    static boolean resolveEmitDocType(EmitDocTypeMode mode, Version sourceVersion, String docTransformerConfig) {
        return switch (mode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> {
                boolean isLegacyEs = sourceVersion != null
                    && sourceVersion.getFlavor() == Flavor.ELASTICSEARCH
                    && sourceVersion.getMajor() <= 6;
                boolean hasCustomTransformer = docTransformerConfig != null;
                boolean enable = isLegacyEs && hasCustomTransformer;
                if (enable) {
                    log.atInfo().setMessage("Auto-enabling --emit-doc-type for {} with custom transformer")
                        .addArgument(sourceVersion).log();
                }
                yield enable;
            }
        };
    }

    /**
     * Build the document-exception allowlist from CLI args and log when non-empty.
     * Shared between the ES and Solr backfill paths.
     */
    private static DocumentExceptionAllowlist buildDocumentExceptionAllowlist(Args arguments) {
        var allowedExceptionTypesSet = new HashSet<>(arguments.allowedDocExceptionTypes);
        var allowlist = new DocumentExceptionAllowlist(allowedExceptionTypesSet);
        if (!allowedExceptionTypesSet.isEmpty()) {
            log.atInfo().setMessage("Document exception allowlist configured with types: {}")
                .addArgument(String.join(", ", allowedExceptionTypesSet))
                .log();
        }
        return allowlist;
    }

    private static CoordinatorInfo resolveCoordinatorConnection(Args arguments, ConnectionContext targetConnectionContext, Version targetVersion) {
        if (arguments.coordinatorArgs.isEnabled()) {
            var ctx = arguments.coordinatorArgs.toConnectionContext();
            var version = new OpenSearchClientFactory(ctx).getClusterVersion();
            if (version.getFlavor() == Flavor.AMAZON_SERVERLESS_OPENSEARCH) {
                throw new IllegalArgumentException(
                    "OpenSearch Serverless cannot be used as a coordinator cluster. " +
                    "Serverless does not support the work coordination indices required for document migration. " +
                    "Please use a managed OpenSearch or self-hosted cluster for coordination."
                );
            }
            log.atInfo().setMessage("Using separate coordinator cluster: {} (version: {})")
                .addArgument(ctx.getUri()).addArgument(version).log();
            return new CoordinatorInfo(ctx, version);
        }
        if (targetVersion.getFlavor() == Flavor.AMAZON_SERVERLESS_OPENSEARCH) {
            throw new IllegalArgumentException(
                "OpenSearch Serverless cannot be used for work coordination. " +
                "Please specify a separate coordinator cluster using --coordinator-host."
            );
        }
        log.atInfo().setMessage("Using target cluster for coordination").log();
        return new CoordinatorInfo(targetConnectionContext, targetVersion);
    }

    private static void executeCleanShutdownProcess(
            AtomicReference<IWorkCoordinator.WorkItemAndDuration> workItemRef,
            AtomicReference<WorkItemCursor> progressCursor,
            IWorkCoordinator coordinator,
            AtomicBoolean cleanShutdownCompleted,
            Supplier<IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext> contextSupplier,
            Supplier<IWorkCoordinationContexts.IReleaseWorkItemContext> releaseContextSupplier
    ) throws IOException, InterruptedException {
        if (cleanShutdownCompleted.get())  {
            log.atInfo().setMessage("Clean shutdown already completed").log();
            return;
        }
        if (workItemRef.get() == null) {
            log.atInfo().setMessage("No work item found. This may indicate that the task is exiting too early to have progress to mark.").log();
            return;
        }
        var workItemAndDuration = workItemRef.get();
        if (progressCursor.get() == null) {
            // No documents have been migrated yet (the cursor is only populated after the first
            // successful bulk batch — see DocumentMigrationBootstrap), so there is nothing to
            // checkpoint and we can't seed a successor.  Releasing the lease here lets another
            // worker retry the same item immediately instead of waiting for natural expiration.
            releaseLeaseWithoutProgress(workItemAndDuration, coordinator, releaseContextSupplier);
            cleanShutdownCompleted.set(true);
            return;
        }
        log.atInfo().setMessage("Marking progress: " + workItemAndDuration.getWorkItem().toString() + ", at doc " + progressCursor.get().getProgressCheckpointNum()).log();
        var successorWorkItem = getSuccessorWorkItemIds(workItemAndDuration, progressCursor.get());

        coordinator.createSuccessorWorkItemsAndMarkComplete(
                workItemAndDuration.getWorkItem().toString(), successorWorkItem, 1, contextSupplier
        );
        cleanShutdownCompleted.set(true);
    }

    /**
     * Release the lease for a work item that we acquired but made no progress on — the doc
     * cursor is still null, so there is nothing to checkpoint and no meaningful successor to
     * create.  Without this, the work item stays leased for the full expiration window and
     * blocks any other worker from picking it up.
     */
    private static void releaseLeaseWithoutProgress(
            IWorkCoordinator.WorkItemAndDuration workItemAndDuration,
            IWorkCoordinator coordinator,
            Supplier<IWorkCoordinationContexts.IReleaseWorkItemContext> releaseContextSupplier
    ) throws IOException, InterruptedException {
        var workItemId = workItemAndDuration.getWorkItem().toString();
        log.atWarn().setMessage("Releasing lease for work item {} because no progress was made before shutdown — letting another worker retry immediately rather than waiting for natural lease expiration.")
                .addArgument(workItemId)
                .log();
        coordinator.releaseWorkItem(workItemId, releaseContextSupplier);
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
            Supplier<IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext> contextSupplier,
            Supplier<IWorkCoordinationContexts.IReleaseWorkItemContext> releaseContextSupplier) {
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
                            workItemAndDuration.getLeaseExpirationTime(),
                            contextSupplier
                    );
                }
            } else {
                // We held the lease but never produced a checkpoint — the most common cause is
                // shard download/unpack outliving the lease window before any docs were migrated.
                // Release the lease so another worker can immediately retry instead of waiting
                // for natural expiration.  workItemRef may be null if the trigger fired before
                // acquisition completed; in that case there's nothing to release.
                log.atWarn().setMessage("No progress cursor to create successor work items from. This can happen when " +
                        "downloading and unpacking shard takes longer than the lease.").log();
                var workItemAndDuration = workItemRef.get();
                if (workItemAndDuration != null) {
                    releaseLeaseWithoutProgress(workItemAndDuration, coordinator, releaseContextSupplier);
                } else {
                    log.atWarn().setMessage("No work item reference available; skipping lease release.").log();
                }
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
        // Re-process same checkpoint to handle 1:many doc splits correctly
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
        var otelSdk = RootOtelContext.initializeOpenTelemetryWithCollectorsOrAsNoop(
            new OtelCollectorEndpoints(arguments.otelTraceCollectorEndpoint, arguments.otelMetricsCollectorEndpoint),
            RootDocumentMigrationContext.SCOPE_NAME,
            workerId
        );
        return new RootDocumentMigrationContext(otelSdk, compositeContextTracker);
    }


    private static MigrationSourceFactory buildSolrSourceFactory(
        Args arguments,
        OpenSearchClient targetClient,
        Supplier<IJsonTransformer> docTransformerSupplier,
        boolean useServerGeneratedIds,
        RootDocumentMigrationContext context
    ) {
        return (workCoordinator, processManager, progressCursor, cancellationRunnableRef, workItemTimeProvider) -> {
            DocumentExceptionAllowlist allowlist = buildDocumentExceptionAllowlist(arguments);

            Path backupDir;
            S3Repo s3Repo = null;
            var parsedUri = RepoUri.parse(arguments.repoUri);
            switch (parsedUri) {
                case RepoUri.FileRepoUri f -> {
                    backupDir = Paths.get(f.path());
                    log.atInfo().setMessage("Starting Solr backup document migration from local dir: {}").addArgument(backupDir).log();
                }
                case RepoUri.S3RepoUri s -> {
                    var backupS3Uri = SolrBackupLayout.buildBackupS3Uri(s.s3Uri(), arguments.snapshotName);
                    log.atInfo().setMessage("Downloading Solr backup metadata from S3: {}").addArgument(backupS3Uri).log();
                    s3Repo = S3Repo.createRaw(
                        Paths.get(arguments.localDir),
                        new S3Uri(backupS3Uri),
                        arguments.s3Region,
                        arguments.endpoint != null ? URI.create(arguments.endpoint) : null
                    );
                    backupDir = s3Repo.getRepoRootDir();
                }
                default -> throw new ParameterException(
                    "When source version is SOLR, provide --repo-uri with a file:// or s3:// scheme."
                );
            }

            var schemas = new LinkedHashMap<String, JsonNode>();
            final List<String> collections;
            if (s3Repo != null) {
                collections = new ArrayList<>(s3Repo.listTopLevelDirectories());
            } else {
                collections = new ArrayList<>(SolrSnapshotReader.discoverCollections(backupDir));
            }
            if (!arguments.indexAllowlist.isEmpty()) {
                collections.retainAll(arguments.indexAllowlist);
            }
            for (var collection : collections) {
                schemas.put(collection, null);
            }

            final S3Repo finalS3Repo = s3Repo;
            final Path finalBackupDir = backupDir;
            final Map<String, String> dataPrefixByCollection = new ConcurrentHashMap<>();
            Consumer<String> collectionPreparer = collection -> {
                if (finalS3Repo != null) {
                    var resolved = SolrBackupLayout.resolveCollectionDataPrefix(
                        collection, finalS3Repo::listSubDirectories);
                    if (resolved != null) {
                        dataPrefixByCollection.put(collection, resolved.dataPrefix());
                        var dataRoot = resolved.joinWith(collection);
                        finalS3Repo.downloadPrefix(dataRoot + "/" + resolved.latestZkBackupName());
                        log.atInfo().setMessage("Downloading shard metadata for collection '{}' from S3").addArgument(collection).log();
                        finalS3Repo.downloadPrefix(dataRoot + "/shard_backup_metadata");
                        // Solr 6: create local stub dirs for snapshot.shardN/ so shard
                        // discovery can count them before index files are downloaded.
                        finalS3Repo.listSubDirectories(dataRoot).stream()
                            .filter(name -> name.startsWith("snapshot."))
                            .forEach(snapshotDirName -> {
                                try {
                                    Files.createDirectories(finalBackupDir.resolve(dataRoot).resolve(snapshotDirName));
                                } catch (IOException e) {
                                    log.warn("Failed to create snapshot stub dir {}/{}", dataRoot, snapshotDirName, e);
                                }
                            });
                    } else {
                        log.warn("No zk_backup directories found for collection '{}' in S3", collection);
                    }
                }
                var collectionRoot = finalBackupDir.resolve(collection);
                var dataPrefix = dataPrefixByCollection.getOrDefault(collection, "");
                var dataDir = dataPrefix.isEmpty() ? collectionRoot : collectionRoot.resolve(dataPrefix);
                schemas.put(collection, SolrSchemaXmlParser.findAndParse(dataDir));
            };
            // Only S3 needs lazy per-shard downloads; filesystem backups are already local
            Consumer<SolrShardPartition> shardPreparer = (finalS3Repo != null) ? partition -> {
                var dataPrefix = dataPrefixByCollection.getOrDefault(partition.collection(), "");
                var collectionDataPrefix = dataPrefix.isEmpty()
                    ? partition.collection()
                    : partition.collection() + "/" + dataPrefix;
                var mapping = partition.fileNameMapping();
                if (mapping != null) {
                    log.atInfo().setMessage("Downloading {} index files for shard '{}/{}' from S3")
                        .addArgument(mapping.size()).addArgument(partition.collection()).addArgument(partition.shard()).log();
                    for (var uuid : mapping.values()) {
                        finalS3Repo.downloadFile(collectionDataPrefix + "/index/" + uuid);
                    }
                } else {
                    // Non-UUID layout: Solr 6 uses snapshot.shardN/ dirs at the collection
                    // root; Solr 8 non-incremental uses a single index/ dir.
                    log.atInfo().setMessage("Downloading index data for shard '{}/{}' from S3")
                        .addArgument(partition.collection()).addArgument(partition.shard()).log();
                    var shardPath = partition.shard().startsWith("snapshot.")
                        ? collectionDataPrefix + "/" + partition.shard()
                        : collectionDataPrefix + "/index";
                    finalS3Repo.downloadPrefix(shardPath);
                }
            } : null;

            var solrMajor = arguments.sourceVersion.getMajor();
            var indexMetadataFactory = new SolrBackupIndexMetadataFactory(backupDir, schemas, collectionPreparer);
            var documentSource = new SolrMultiCollectionSource(backupDir, schemas, collectionPreparer, shardPreparer, solrMajor);

            return prepareAndMigrate(documentSource,
                workCoordinator, processManager, targetClient, docTransformerSupplier,
                useServerGeneratedIds, allowlist, progressCursor, cancellationRunnableRef,
                workItemTimeProvider, arguments, context);
        };
    }


    /**
     * Shared work-coordination setup: creates a scoped coordinator, ensures shard prep
     * is complete, and verifies there is still work to do.
     */
    public static ScopedWorkCoordinator prepareWorkCoordination(
        IWorkCoordinator workCoordinator,
        LeaseExpireTrigger leaseExpireTrigger,
        org.opensearch.migrations.bulkload.pipeline.source.DocumentSource documentSource,
        List<String> indexAllowlist,
        RootDocumentMigrationContext rootDocumentContext
    ) throws IOException, InterruptedException, NoWorkLeftException {
        var scopedWorkCoordinator = new ScopedWorkCoordinator(workCoordinator, leaseExpireTrigger);
        confirmShardPrepIsComplete(documentSource, indexAllowlist,
            scopedWorkCoordinator, rootDocumentContext);
        if (!workCoordinator.workItemsNotYetComplete(
            rootDocumentContext.getWorkCoordinationContext()::createItemsPendingContext
        )) {
            throw new NoWorkLeftException("No work items are pending/all work items have been processed.  Returning.");
        }
        return scopedWorkCoordinator;
    }

    private static void confirmShardPrepIsComplete(
        org.opensearch.migrations.bulkload.pipeline.source.DocumentSource documentSource,
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
                    documentSource,
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
