package com.rfs;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import java.nio.file.Path;
import java.nio.file.Paths;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;


import com.rfs.cms.CmsClient;
import com.rfs.cms.OpenSearchCmsClient;
import com.rfs.common.ConnectionDetails;
import com.rfs.common.DefaultSourceRepoAccessor;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.IndexMetadata;
import com.rfs.common.Logging;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.S3Uri;
import com.rfs.common.ShardMetadata;
import com.rfs.common.S3Repo;
import com.rfs.common.SourceRepo;
import com.rfs.common.TryHandlePhaseFailure;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.version_es_7_10.ElasticsearchConstants_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.worker.DocumentsRunner;
import com.rfs.worker.GlobalState;

@Slf4j
public class RfsMigrateDocuments {
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

        @Parameter(names = {"--target-host"}, description = "The target host and port (e.g. http://localhost:9200)", required = true)
        public String targetHost;

        @Parameter(names = {"--target-username"}, description = "Optional.  The target username; if not provided, will assume no auth on target", required = false)
        public String targetUser = null;

        @Parameter(names = {"--target-password"}, description = "Optional.  The target password; if not provided, will assume no auth on target", required = false)
        public String targetPass = null;

        @Parameter(names = {"--max-shard-size-bytes"}, description = ("Optional. The maximum shard size, in bytes, to allow when"
            + " performing the document migration.  Useful for preventing disk overflow.  Default: 50 * 1024 * 1024 * 1024 (50 GB)"), required = false)
        public long maxShardSizeBytes = 50 * 1024 * 1024 * 1024L;
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
        final String targetHost = arguments.targetHost;
        final String targetUser = arguments.targetUser;
        final String targetPass = arguments.targetPass;
        final long maxShardSizeBytes = arguments.maxShardSizeBytes;

        final ConnectionDetails targetConnection = new ConnectionDetails(targetHost, targetUser, targetPass);

        TryHandlePhaseFailure.executeWithTryCatch(() -> {
            log.info("Running RfsWorker");

            GlobalState globalState = GlobalState.getInstance();
            OpenSearchClient targetClient = new OpenSearchClient(targetConnection);
            final CmsClient cmsClient = new OpenSearchCmsClient(targetClient);

            final SourceRepo sourceRepo = S3Repo.create(s3LocalDirPath, new S3Uri(s3RepoUri), s3Region);
            final SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            
            final IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            final ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);
            final DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            final SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor, luceneDirPath, ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES);
            final LuceneDocumentsReader reader = new LuceneDocumentsReader(luceneDirPath);
            final DocumentReindexer reindexer = new DocumentReindexer(targetClient);
            
            DocumentsRunner documentsWorker = new DocumentsRunner(globalState, cmsClient, snapshotName, maxShardSizeBytes, indexMetadataFactory, shardMetadataFactory, unpackerFactory, reader, reindexer);
            documentsWorker.run();
        });
    }
}
