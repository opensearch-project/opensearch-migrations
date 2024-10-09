package org.opensearch.migrations;



import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

public class MigrateOrEvaluateArgs {
    @Parameter(names = {"--help", "-h"}, help = true, description = "Displays information about how to use this tool")
    public boolean help;

    @Parameter(names = { "--snapshot-name" }, description = "The name of the snapshot to migrate")
    public String snapshotName;

    @Parameter(names = {
        "--file-system-repo-path" }, description = "The full path to the snapshot repo on the file system.")
    public String fileSystemRepoPath;

    @Parameter(names = {
        "--s3-local-dir" }, description = "The absolute path to the directory on local disk to download S3 files to")
    public String s3LocalDirPath;

    @Parameter(names = {
        "--s3-repo-uri" }, description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2")
    public String s3RepoUri;

    @Parameter(names = {
        "--s3-region" }, description = "The AWS Region the S3 bucket is in, like: us-east-2")
    public String s3Region;

    @ParametersDelegate
    public ConnectionContext.SourceArgs sourceArgs = new ConnectionContext.SourceArgs();

    @ParametersDelegate
    public ConnectionContext.TargetArgs targetArgs = new ConnectionContext.TargetArgs();

    @ParametersDelegate
    public DataFilterArgs dataFilterArgs = new DataFilterArgs(); 

    // https://opensearch.org/docs/2.11/api-reference/cluster-api/cluster-awareness/
    @Parameter(names = {"--min-replicas" }, description = "Optional.  The minimum number of replicas configured for migrated indices on the target."
            + " This can be useful for migrating to targets which use zonal deployments and require additional replicas to meet zone requirements.  Default: 0")
    public int minNumberOfReplicas = 0;

    @Parameter(required = false, names = {
        "--otel-collector-endpoint" }, arity = 1, description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be"
            + "forwarded. If no value is provided, metrics will not be forwarded.")
    String otelCollectorEndpoint;

    @Parameter(names = {"--source-version" }, description = "Version of the source cluster, for example: Elasticsearch 7.10 or OS 1.3.", converter = VersionConverter.class)
    public Version sourceVersion = null;
}
