package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.commands.Configure;
import org.opensearch.migrations.commands.Migrate;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.rfs.common.ConnectionDetails;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MetadataMigration {

    @ToString
    public static class Args {
        @Parameter(names = { "--snapshot-name" }, description = "The name of the snapshot to migrate", required = true)
        public String snapshotName;

        @Parameter(names = {
            "--file-system-repo-path" }, required = false, description = "The full path to the snapshot repo on the file system.")
        public String fileSystemRepoPath;

        @Parameter(names = {
            "--s3-local-dir" }, description = "The absolute path to the directory on local disk to download S3 files to", required = false)
        public String s3LocalDirPath;

        @Parameter(names = {
            "--s3-repo-uri" }, description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2", required = false)
        public String s3RepoUri;

        @Parameter(names = {
            "--s3-region" }, description = "The AWS Region the S3 bucket is in, like: us-east-2", required = false)
        public String s3Region;

        @ParametersDelegate
        public ConnectionDetails.TargetArgs targetArgs = new ConnectionDetails.TargetArgs();

        @Parameter(names = { "--index-allowlist" }, description = ("Optional.  List of index names to migrate"
            + " (e.g. 'logs_2024_01, logs_2024_02').  Default: all non-system indices (e.g. those not starting with '.')"), required = false)
        public List<String> indexAllowlist = List.of();

        @Parameter(names = {
            "--index-template-allowlist" }, description = ("Optional.  List of index template names to migrate"
                + " (e.g. 'posts_index_template1, posts_index_template2').  Default: empty list"), required = false)
        public List<String> indexTemplateAllowlist = List.of();

        @Parameter(names = {
            "--component-template-allowlist" }, description = ("Optional. List of component template names to migrate"
                + " (e.g. 'posts_template1, posts_template2').  Default: empty list"), required = false)
        public List<String> componentTemplateAllowlist = List.of();

        // https://opensearch.org/docs/2.11/api-reference/cluster-api/cluster-awareness/
        @Parameter(names = {
            "--min-replicas" }, description = ("Optional.  The minimum number of replicas configured for migrated indices on the target."
                + " This can be useful for migrating to targets which use zonal deployments and require additional replicas to meet zone requirements.  Default: 0"), required = false)
        public int minNumberOfReplicas = 0;

        @Parameter(required = false, names = {
            "--otel-collector-endpoint" }, arity = 1, description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be"
                + "forwarded. If no value is provided, metrics will not be forwarded.")
        String otelCollectorEndpoint;
    }

    public static void main(String[] args) throws Exception {
        // Grab out args
        Args arguments = new Args();
        JCommander.newBuilder().addObject(arguments).build().parse(args);

        var context = new RootMetadataMigrationContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(arguments.otelCollectorEndpoint, "rfs"),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );


        var meta = new MetadataMigration(arguments);
        var result = meta.migrate().execute(context);
        System.exit(result.getExitCode());
    }

    private final Args arguments;

    public MetadataMigration(Args arguments) {
        this.arguments = arguments;
    }

    public Configure configure() {
        return new Configure();
    }

    public Migrate migrate() {
        return new Migrate(arguments);
    }
}
