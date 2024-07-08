package com.rfs;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.rfs.cms.ApacheHttpClient;
import com.rfs.cms.OpenSearchWorkCoordinator;
import com.rfs.cms.LeaseExpireTrigger;
import com.rfs.cms.ScopedWorkCoordinator;
import com.rfs.common.DefaultSourceRepoAccessor;
import com.rfs.worker.ShardWorkPreparer;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;

import com.rfs.common.ClusterVersion;
import com.rfs.common.ConnectionDetails;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.GlobalMetadata;
import com.rfs.common.IndexMetadata;
import com.rfs.common.Logging;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.S3Uri;
import com.rfs.common.ShardMetadata;
import com.rfs.common.S3Repo;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.SourceRepo;
import com.rfs.common.S3SnapshotCreator;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.transformers.TransformFunctions;
import com.rfs.transformers.Transformer;
import com.rfs.version_es_7_10.ElasticsearchConstants_ES_7_10;
import com.rfs.version_es_7_10.GlobalMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import com.rfs.worker.DocumentsRunner;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import com.rfs.worker.SnapshotRunner;

@Slf4j
public class RunRfsWorker {
    public static final int PROCESS_TIMED_OUT = 1;

    public static class Args {
        @Parameter(names = {"--snapshot-name"}, description = "The name of the snapshot to migrate", required = true)
        public String snapshotName;

        @Parameter(names = {"--s3-local-dir"}, description = "The absolute path to the directory on local disk to download S3 files to", required = true)
        public String s3LocalDirPath;

        @Parameter(names = {"--s3-repo-uri"}, description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2", required = true)
        public String s3RepoUri;

        @Parameter(names = {"--s3-region"}, description = "The AWS Region the S3 bucket is in, like: us-east-2", required = true)
        public String s3Region;

        @Parameter(names = {"--lucene-dir"}, description = "The absolute path to the directory where we'll put the Lucene docs", required = true)
        public String luceneDirPath;
        
        @ParametersDelegate
        public ConnectionDetails.SourceArgs sourceArgs;

        @ParametersDelegate
        public ConnectionDetails.TargetArgs targetArgs;

        @Parameter(names = {"--index-allowlist"}, description = ("Optional.  List of index names to migrate"
            + " (e.g. 'logs_2024_01, logs_2024_02').  Default: all indices"), required = false)
        public List<String> indexAllowlist = List.of();

        @Parameter(names = {"--index-template-allowlist"}, description = ("Optional.  List of index template names to migrate"
            + " (e.g. 'posts_index_template1, posts_index_template2').  Default: empty list"), required = false)
        public List<String> indexTemplateAllowlist = List.of();

        @Parameter(names = {"--component-template-allowlist"}, description = ("Optional. List of component template names to migrate"
            + " (e.g. 'posts_template1, posts_template2').  Default: empty list"), required = false)
        public List<String> componentTemplateAllowlist = List.of();

        @Parameter(names = {"--max-shard-size-bytes"}, description = ("Optional. The maximum shard size, in bytes, to allow when"
            + " performing the document migration.  Useful for preventing disk overflow.  Default: 50 * 1024 * 1024 * 1024 (50 GB)"), required = false)
        public long maxShardSizeBytes = 50 * 1024 * 1024 * 1024L;

        //https://opensearch.org/docs/2.11/api-reference/cluster-api/cluster-awareness/
        @Parameter(names = {"--min-replicas"}, description = ("Optional.  The minimum number of replicas configured for migrated indices on the target."
            + " This can be useful for migrating to targets which use zonal deployments and require additional replicas to meet zone requirements.  Default: 0")
            , required = false)
        public int minNumberOfReplicas = 0;

        @Parameter(names = {"--log-level"}, description = "What log level you want.  Default: 'info'", required = false, converter = Logging.ArgsConverter.class)
        public Level logLevel = Level.INFO;
    }

    public static void main(String[] args) throws Exception {
        // Grab out args
        Args arguments = new Args();
        JCommander.newBuilder()
            .addObject(arguments)
            .build()
            .parse(args);

        final String snapshotName = arguments.snapshotName;
        final Path s3LocalDirPath = Paths.get(arguments.s3LocalDirPath);
        final String s3RepoUri = arguments.s3RepoUri;
        final String s3Region = arguments.s3Region;
        final Path luceneDirPath = Paths.get(arguments.luceneDirPath);
        final List<String> indexAllowlist = arguments.indexAllowlist;
        final List<String> indexTemplateAllowlist = arguments.indexTemplateAllowlist;
        final List<String> componentTemplateAllowlist = arguments.componentTemplateAllowlist;
        final long maxShardSizeBytes = arguments.maxShardSizeBytes;
        final int awarenessDimensionality = arguments.minNumberOfReplicas + 1;
        final Level logLevel = arguments.logLevel;

        Logging.setLevel(logLevel);

        final ConnectionDetails sourceConnection = new ConnectionDetails(arguments.sourceArgs);
        final ConnectionDetails targetConnection = new ConnectionDetails(arguments.targetArgs);

        try (var processManager = new LeaseExpireTrigger(workItemId -> {
            log.error("terminating RunRfsWorker because its lease has expired for " + workItemId);
            System.exit(PROCESS_TIMED_OUT);
        }, Clock.systemUTC())) {
            log.info("Running RfsWorker");
            OpenSearchClient sourceClient = new OpenSearchClient(sourceConnection);
            OpenSearchClient targetClient = new OpenSearchClient(targetConnection);

            SnapshotCreator snapshotCreator = new S3SnapshotCreator(snapshotName, sourceClient, s3RepoUri, s3Region);
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);

            SourceRepo sourceRepo = S3Repo.create(s3LocalDirPath, new S3Uri(s3RepoUri), s3Region);
            SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            GlobalMetadata.Factory metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
            GlobalMetadataCreator_OS_2_11 metadataCreator = new GlobalMetadataCreator_OS_2_11(targetClient, List.of(), componentTemplateAllowlist, indexTemplateAllowlist);
            Transformer transformer = TransformFunctions.getTransformer(ClusterVersion.ES_7_10, ClusterVersion.OS_2_11, awarenessDimensionality);
            new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();

            IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            IndexCreator_OS_2_11 indexCreator = new IndexCreator_OS_2_11(targetClient);
            new IndexRunner(snapshotName, indexMetadataFactory, indexCreator, transformer, indexAllowlist).migrateIndices();

            ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);
            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            var unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor,
                    luceneDirPath, ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES);
            DocumentReindexer reindexer = new DocumentReindexer(targetClient);
            var workCoordinator = new OpenSearchWorkCoordinator(new ApacheHttpClient(new URI(arguments.targetArgs.getHost())),
                    5, UUID.randomUUID().toString());
            var scopedWorkCoordinator = new ScopedWorkCoordinator(workCoordinator, processManager);
            new ShardWorkPreparer().run(scopedWorkCoordinator, indexMetadataFactory, snapshotName, indexAllowlist);
            new DocumentsRunner(scopedWorkCoordinator,
                    (name,shard) -> shardMetadataFactory.fromRepo(snapshotName,name,shard),
                    unpackerFactory,
                    path -> new LuceneDocumentsReader(path),
                    reindexer)
                    .migrateNextShard();
        } catch (Exception e) {
            log.error("Unexpected error running RfsWorker", e);
            throw e;
        }
    }
}
