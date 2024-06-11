package com.rfs;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;


import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.OpenSearchCmsClient;
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
import com.rfs.worker.GlobalState;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import com.rfs.worker.Runner;
import com.rfs.worker.SnapshotRunner;

public class RunRfsWorker {
    private static final Logger logger = LogManager.getLogger(RunRfsWorker.class);

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

        @Parameter(names = {"--source-host"}, description = "The source host and port (e.g. http://localhost:9200)", required = true)
        public String sourceHost;

        @Parameter(names = {"--source-username"}, description = "Optional.  The source username; if not provided, will assume no auth on source", required = false)
        public String sourceUser = null;

        @Parameter(names = {"--source-password"}, description = "Optional.  The source password; if not provided, will assume no auth on source", required = false)
        public String sourcePass = null;

        @Parameter(names = {"--target-host"}, description = "The target host and port (e.g. http://localhost:9200)", required = true)
        public String targetHost;

        @Parameter(names = {"--target-username"}, description = "Optional.  The target username; if not provided, will assume no auth on target", required = false)
        public String targetUser = null;

        @Parameter(names = {"--target-password"}, description = "Optional.  The target password; if not provided, will assume no auth on target", required = false)
        public String targetPass = null;

        @Parameter(names = {"--index-allowlist"}, description = ("Optional.  List of index names to migrate"
            + " (e.g. 'logs_2024_01, logs_2024_02').  Default: all indices"), required = false)
        public List<String> indexAllowlist = List.of();

        @Parameter(names = {"--index-template-allowlist"}, description = ("Optional.  List of index template names to migrate"
            + " (e.g. 'posts_index_template1, posts_index_template2').  Default: empty list"), required = false)
        public List<String> indexTemplateAllowlist = List.of();

        @Parameter(names = {"--component-template-allowlist"}, description = ("Optional. List of component template names to migrate"
            + " (e.g. 'posts_template1, posts_template2').  Default: empty list"), required = false)
        public List<String> componentTemplateAllowlist = List.of();
        
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

        String snapshotName = arguments.snapshotName;
        Path s3LocalDirPath = Paths.get(arguments.s3LocalDirPath);
        String s3RepoUri = arguments.s3RepoUri;
        String s3Region = arguments.s3Region;
        Path luceneDirPath = Paths.get(arguments.luceneDirPath);
        String sourceHost = arguments.sourceHost;
        String sourceUser = arguments.sourceUser;
        String sourcePass = arguments.sourcePass;
        String targetHost = arguments.targetHost;
        String targetUser = arguments.targetUser;
        String targetPass = arguments.targetPass;
        List<String> indexTemplateAllowlist = arguments.indexAllowlist;
        List<String> componentTemplateAllowlist = arguments.componentTemplateAllowlist;
        int awarenessDimensionality = arguments.minNumberOfReplicas + 1;
        Level logLevel = arguments.logLevel;

        Logging.setLevel(logLevel);

        ConnectionDetails sourceConnection = new ConnectionDetails(sourceHost, sourceUser, sourcePass);
        ConnectionDetails targetConnection = new ConnectionDetails(targetHost, targetUser, targetPass);

        try {
            logger.info("Running RfsWorker");
            GlobalState globalState = GlobalState.getInstance();
            OpenSearchClient sourceClient = new OpenSearchClient(sourceConnection);
            OpenSearchClient targetClient = new OpenSearchClient(targetConnection);
            CmsClient cmsClient = new OpenSearchCmsClient(targetClient);

            SnapshotCreator snapshotCreator = new S3SnapshotCreator(snapshotName, sourceClient, s3RepoUri, s3Region);
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);

            SourceRepo sourceRepo = S3Repo.create(s3LocalDirPath, new S3Uri(s3RepoUri), s3Region);
            SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            GlobalMetadata.Factory metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
            GlobalMetadataCreator_OS_2_11 metadataCreator = new GlobalMetadataCreator_OS_2_11(targetClient, List.of(), componentTemplateAllowlist, indexTemplateAllowlist);
            Transformer transformer = TransformFunctions.getTransformer(ClusterVersion.ES_7_10, ClusterVersion.OS_2_11, awarenessDimensionality);
            MetadataRunner metadataWorker = new MetadataRunner(globalState, cmsClient, snapshotName, metadataFactory, metadataCreator, transformer);
            metadataWorker.run();

            IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            IndexCreator_OS_2_11 indexCreator = new IndexCreator_OS_2_11(targetClient);
            IndexRunner indexWorker = new IndexRunner(globalState, cmsClient, snapshotName, indexMetadataFactory, indexCreator, transformer);
            indexWorker.run();

            ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);
            SnapshotShardUnpacker unpacker = new SnapshotShardUnpacker(sourceRepo, luceneDirPath, ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES);
            LuceneDocumentsReader reader = new LuceneDocumentsReader(luceneDirPath);
            DocumentReindexer reindexer = new DocumentReindexer(targetClient);
            DocumentsRunner documentsWorker = new DocumentsRunner(globalState, cmsClient, snapshotName, indexMetadataFactory, shardMetadataFactory, unpacker, reader, reindexer);
            documentsWorker.run();

        } catch (Runner.PhaseFailed e) {
            logPhaseFailureRecord(e.phase, e.cmsEntry, e.getCause());
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error running RfsWorker", e);
            throw e;
        }
    }

    public static void logPhaseFailureRecord(GlobalState.Phase phase, Optional<CmsEntry.Base> cmsEntry, Throwable e) {
        ObjectNode errorBlob = new ObjectMapper().createObjectNode();
        errorBlob.put("exceptionMessage", e.getMessage());
        errorBlob.put("exceptionClass", e.getClass().getSimpleName());
        errorBlob.put("exceptionTrace", Arrays.toString(e.getStackTrace()));

        errorBlob.put("phase", phase.toString());

        String currentEntry = cmsEntry.map(CmsEntry.Base::toRepresentationString).orElse("null");
        errorBlob.put("cmsEntry", currentEntry);

        logger.error(errorBlob.toString());
    }
}
