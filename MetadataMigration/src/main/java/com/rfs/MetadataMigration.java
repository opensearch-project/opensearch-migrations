package com.rfs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import com.rfs.common.ClusterVersion;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.S3Repo;
import com.rfs.common.S3Uri;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SourceRepo;
import com.rfs.common.TryHandlePhaseFailure;
import com.rfs.common.http.ConnectionContext;
import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.transformers.TransformFunctions;
import com.rfs.transformers.Transformer;
import com.rfs.version_es_7_10.GlobalMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MetadataMigration {

    public static class Args {
        @Parameter(names = {"--help", "-h"}, help = true, description = "Displays information about how to use this tool")
        private boolean help;

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
        public ConnectionContext.TargetArgs targetArgs = new ConnectionContext.TargetArgs();

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
        Args arguments = new Args();
        JCommander jCommander = JCommander.newBuilder().addObject(arguments).build();
        jCommander.parse(args);

        if (arguments.help) {
            jCommander.usage();
            return;
        }

        var rootContext = new RootMetadataMigrationContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(arguments.otelCollectorEndpoint, "rfs"),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        if (arguments.fileSystemRepoPath == null && arguments.s3RepoUri == null) {
            throw new ParameterException("Either file-system-repo-path or s3-repo-uri must be set");
        }
        if (arguments.fileSystemRepoPath != null && arguments.s3RepoUri != null) {
            throw new ParameterException("Only one of file-system-repo-path and s3-repo-uri can be set");
        }
        if ((arguments.s3RepoUri != null) && (arguments.s3Region == null || arguments.s3LocalDirPath == null)) {
            throw new ParameterException("If an s3 repo is being used, s3-region and s3-local-dir-path must be set");
        }

        final String snapshotName = arguments.snapshotName;
        final Path fileSystemRepoPath = arguments.fileSystemRepoPath != null
            ? Paths.get(arguments.fileSystemRepoPath)
            : null;
        final Path s3LocalDirPath = arguments.s3LocalDirPath != null ? Paths.get(arguments.s3LocalDirPath) : null;
        final String s3RepoUri = arguments.s3RepoUri;
        final String s3Region = arguments.s3Region;
        final List<String> indexAllowlist = arguments.indexAllowlist;
        final List<String> indexTemplateAllowlist = arguments.indexTemplateAllowlist;
        final List<String> componentTemplateAllowlist = arguments.componentTemplateAllowlist;
        final int awarenessDimensionality = arguments.minNumberOfReplicas + 1;

        final ConnectionContext targetConnection = arguments.targetArgs.toConnectionContext();

        TryHandlePhaseFailure.executeWithTryCatch(() -> {
            log.info("Running RfsWorker");
            OpenSearchClient targetClient = new OpenSearchClient(targetConnection);

            final SourceRepo sourceRepo = fileSystemRepoPath != null
                ? new FileSystemRepo(fileSystemRepoPath)
                : S3Repo.create(s3LocalDirPath, new S3Uri(s3RepoUri), s3Region);
            final SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            final GlobalMetadata.Factory metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
            final GlobalMetadataCreator_OS_2_11 metadataCreator = new GlobalMetadataCreator_OS_2_11(
                targetClient,
                List.of(),
                componentTemplateAllowlist,
                indexTemplateAllowlist,
                rootContext.createMetadataMigrationContext()
            );
            final Transformer transformer = TransformFunctions.getTransformer(
                ClusterVersion.ES_7_10,
                ClusterVersion.OS_2_11,
                awarenessDimensionality
            );
            new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();

            final IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            final IndexCreator_OS_2_11 indexCreator = new IndexCreator_OS_2_11(targetClient);
            new IndexRunner(
                snapshotName,
                indexMetadataFactory,
                indexCreator,
                transformer,
                indexAllowlist,
                rootContext.createIndexContext()
            ).migrateIndices();
        });
    }
}
