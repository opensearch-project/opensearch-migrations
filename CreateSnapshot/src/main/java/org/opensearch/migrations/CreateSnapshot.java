package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.S3SnapshotCreator;
import org.opensearch.migrations.bulkload.common.SnapshotCreator;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts.ICreateSnapshotContext;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.snapshot.creation.tracing.RootSnapshotContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.utils.ProcessHelpers;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import lombok.AllArgsConstructor;
import lombok.Getter;
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
                names = {"--file-system-repo-path" },
                required = false,
                description = "The full path to the snapshot repo on the file system.")
        public String fileSystemRepoPath;

        @Parameter(
                names = {"--s3-repo-uri" },
                required = false,
                description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2")
        public String s3RepoUri;

        @Parameter(
                names = {"--s3-region" },
                required = false,
                description = "The AWS Region the S3 bucket is in, like: us-east-2")
        public String s3Region;

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
                required = false,
                names = {"--otel-collector-endpoint" },
                arity = 1,
                description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be"
                + "forwarded. If no value is provided, metrics will not be forwarded.")
        String otelCollectorEndpoint;
    }

    @Getter
    @AllArgsConstructor
    public static class S3RepoInfo {
        String awsRegion;
        String repoUri;
    }

    public static void main(String[] args) throws Exception {
        // TODO: Add back arg printing after not consuming plaintext password MIGRATIONS-1915
        Args arguments = new Args();
        JCommander jCommander = JCommander.newBuilder().addObject(arguments).build();
        jCommander.parse(args);

        if (arguments.help) {
            jCommander.usage();
            return;
        }

        var rootContext = new RootSnapshotContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(arguments.otelCollectorEndpoint,
                RootSnapshotContext.SCOPE_NAME, ProcessHelpers.getNodeInstanceName()),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        if (arguments.fileSystemRepoPath == null && arguments.s3RepoUri == null) {
            throw new ParameterException("Either file-system-repo-path or s3-repo-uri must be set");
        }
        if (arguments.fileSystemRepoPath != null && arguments.s3RepoUri != null) {
            throw new ParameterException("Only one of file-system-repo-path and s3-repo-uri can be set");
        }
        if (arguments.s3RepoUri != null && arguments.s3Region == null) {
            throw new ParameterException("If an s3 repo is being used, s3-region must be set");
        }

        var snapshotCreator = new CreateSnapshot(arguments, rootContext.createSnapshotCreateContext());
        snapshotCreator.run();
    }

    private Args arguments;
    private ICreateSnapshotContext context;

    public void run() {
        var clientFactory = new OpenSearchClientFactory(arguments.sourceArgs.toConnectionContext());
        var client = clientFactory.determineVersionAndCreate();
        SnapshotCreator snapshotCreator;
        if (arguments.fileSystemRepoPath != null) {
            snapshotCreator = new FileSystemSnapshotCreator(
                    arguments.snapshotName,
                    client,
                    arguments.fileSystemRepoPath,
                    arguments.indexAllowlist,
                    context
                );
        } else {
            snapshotCreator = new S3SnapshotCreator(
                arguments.snapshotName,
                client,
                arguments.s3RepoUri,
                arguments.s3Region,
                arguments.indexAllowlist,
                arguments.maxSnapshotRateMBPerNode,
                arguments.s3RoleArn,
                context
            );
        }

        try {
            if (arguments.noWait) {
                SnapshotRunner.run(snapshotCreator);
            } else {
                SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            }
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Unexpected error running RfsWorker").log();
            throw e;
        }
    }
}
