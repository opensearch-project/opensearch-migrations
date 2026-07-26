package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.arguments.ArgLogUtils;
import org.opensearch.migrations.arguments.ArgNameConstants;
import org.opensearch.migrations.bulkload.common.ClusterVersionDetector;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts.ICreateSnapshotContext;
import org.opensearch.migrations.jcommander.EnvVarParameterPuller;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;
import org.opensearch.migrations.snapshot.creation.tracing.RootSnapshotContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.OtelCollectorEndpoints;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.utils.ProcessHelpers;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class CreateSnapshot {
    public static class Args {
        @Parameter(
                names = {"--help", "-h"},
                help = true,
                description = "Displays information about how to use this tool")
        private boolean help;

        @Parameter(
                names = { "--snapshot-name" },
                required = true,
                description = "The name of the snapshot to migrate")
        public String snapshotName;

        @Parameter(
                names = { "--snapshot-repo-name" },
                required = true,
                description = "The name of the snapshot repository")
        public String snapshotRepoName;

        @Parameter(
                names = {"--repo-uri", "--s3-repo-uri", "--file-system-repo-path"},
                required = true,
                description = "Repository URI. Schemes: file:///path, s3://bucket/path, gs://bucket/path (or bare absolute path)")
        public String repoUri;

        @Parameter(
                names = {"--s3-region" },
                required = false,
                description = "The AWS Region the S3 bucket is in, like: us-east-2")
        public String s3Region;

        @Parameter(
                names = {"--endpoint", "--s3-endpoint" },
                required = false,
                description = "Custom endpoint for the repository service (e.g. LocalStack for S3, fake-gcs-server for GCS)")
        public String endpoint;

        @ParametersDelegate
        public ConnectionContext.SourceArgs sourceArgs = new ConnectionContext.SourceArgs();

        @Parameter(
                names = {"--no-wait" },
                description = "Optional.  If provided, the snapshot runner will not wait for completion")
        public boolean noWait = false;

        @Parameter(
                names = {"--max-snapshot-rate-mb-per-node" },
                required = false,
                description = "The maximum snapshot rate in megabytes per second per node")
        public Integer maxSnapshotRateMBPerNode;

        @Parameter(
                names = {"--s3-role-arn" },
                required = false,
                description = "The role ARN the cluster will assume to write a snapshot to S3")
        public String s3RoleArn;

        @Parameter(
                names = {"--index-allowlist"},
                required = false,
                description = "A comma separated list of indices to include in the snapshot. If not provided, all indices are included.")
        public List<String> indexAllowlist = List.of();

        @Parameter(
                names = {"--compression-enabled"},
                required = false,
                description = "Whether to enable metadata compression for the snapshot. Defaults to false.")
        public boolean compressionEnabled = false;

        @Parameter(
                names = {"--include-global-state"},
                required = false,
                description = "Whether to include global state in the snapshot. Defaults to true.")
        public boolean includeGlobalState = true;

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

        @Parameter(
                names = {"--source-type"},
                required = false,
                description = "Source cluster type: 'elasticsearch' (default) or 'solr'")
        public String sourceType = "elasticsearch";

        @Parameter(
                names = {"--mode"},
                required = false,
                description = "Snapshot mode (Solr sources): 'create' (default) performs a standard snapshot backup; "
                    + "'import' performs no backup and instead retrieves each collection/core's schema from the live "
                    + "Solr source and uploads it into an externally-managed snapshot's repo so metadata migration can "
                    + "derive mappings. 'import' requires the live Solr source to be reachable and fails if the schema "
                    + "cannot be obtained.")
        public String mode = "create";

        @Parameter(
                names = {"--solr-collections"},
                required = false,
                description = "Comma-separated list of Solr collection names to back up (required when source-type=solr)")
        public List<String> solrCollections = List.of();
    }

    public static SnapshotMode getSnapshotMode(Args args) {
        return SnapshotMode.fromString(args.mode);
    }

    public static void main(String[] args) throws Exception {
        System.err.println("Starting program with: " + String.join(" ", ArgLogUtils.getRedactedArgs(args, ArgNameConstants.CENSORED_ARGS)));
        Args arguments = EnvVarParameterPuller.injectFromEnv(new Args(), "CREATE_SNAPSHOT_");
        var argParser = JsonCommandLineParser.newBuilder().addObject(arguments).build();
        argParser.parse(args);

        if (arguments.help) {
            argParser.getJCommander().usage();
            return;
        }

        var rootContext = new RootSnapshotContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorsOrAsNoop(
                new OtelCollectorEndpoints(arguments.otelTraceCollectorEndpoint, arguments.otelMetricsCollectorEndpoint),
                RootSnapshotContext.SCOPE_NAME, ProcessHelpers.getNodeInstanceName()),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        var parsedRepoUri = RepoUri.parse(arguments.repoUri);
        if (parsedRepoUri instanceof RepoUri.S3RepoUri && arguments.s3Region == null) {
            throw new ParameterException("If an s3 repo is being used, --s3-region must be set");
        }
        try {
            SnapshotMode.fromString(arguments.mode);
        } catch (IllegalArgumentException e) {
            throw new ParameterException("Invalid --mode value '" + arguments.mode + "'. Must be 'create' or 'import'.");
        }

        var snapshotCreator = new CreateSnapshot(arguments, rootContext.createSnapshotCreateContext());
        snapshotCreator.run();
    }

    private Args arguments;
    private ICreateSnapshotContext context;

    public void run() {
        resolveStrategy().run();
    }

    private SourceBackupStrategy resolveStrategy() {
        if (isSolrSource()) {
            return new SolrBackupStrategy(arguments);
        }
        return new ElasticsearchBackupStrategy(arguments, context);
    }

    private boolean isSolrSource() {
        if ("solr".equalsIgnoreCase(arguments.sourceType)) {
            return true;
        }
        try {
            var version = ClusterVersionDetector.detect(arguments.sourceArgs.toConnectionContext());
            if (version.getFlavor() == Flavor.SOLR) {
                log.atInfo().setMessage("Detected Solr source ({}), using Solr backup path").addArgument(version).log();
                return true;
            }
        } catch (Exception e) {
            log.atWarn().setMessage("Version detection failed, assuming Elasticsearch: {}").addArgument(e.getMessage()).log();
        }
        return false;
    }
}
