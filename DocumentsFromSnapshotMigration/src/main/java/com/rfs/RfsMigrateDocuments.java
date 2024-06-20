package com.rfs;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;

import com.rfs.cms.IWorkCoordinator;
import lombok.extern.slf4j.Slf4j;
import com.rfs.cms.ApacheHttpClient;
import com.rfs.cms.OpenSearchWorkCoordinator;
import com.rfs.cms.ProcessManager;
import com.rfs.cms.ScopedWorkCoordinatorHelper;
import com.rfs.worker.ShardWorkPreparer;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;

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

@Slf4j
public class RfsMigrateDocuments {
    public static final int PROCESS_TIMED_OUT = 1;
    public static final int TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 5;

    public static class Args {
        @Parameter(names = {"--snapshot-name"},
                required = true,
                description = "The name of the snapshot to migrate")
        public String snapshotName;

        @Parameter(names = {"--s3-local-dir"},
                required = true,
                description = "The absolute path to the directory on local disk to download S3 files to")
        public String s3LocalDirPath;

        @Parameter(names = {"--s3-repo-uri"},
                required = true,
                description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2")
        public String s3RepoUri;

        @Parameter(names = {"--s3-region"},
                required = true,
                description = "The AWS Region the S3 bucket is in, like: us-east-2")
        public String s3Region;

        @Parameter(names = {"--lucene-dir"},
                required = true,
                description = "The absolute path to the directory where we'll put the Lucene docs")
        public String luceneDirPath;

        @Parameter(names = {"--target-host"},
                required = true,
                description = "The target host and port (e.g. http://localhost:9200)")
        public String targetHost;

        @Parameter(names = {"--target-username"},
                description = "Optional.  The target username; if not provided, will assume no auth on target")
        public String targetUser = null;

        @Parameter(names = {"--target-password"},
                description = "Optional.  The target password; if not provided, will assume no auth on target")
        public String targetPass = null;

        @Parameter(names = {"--max-shard-size-bytes"}, description = ("Optional. The maximum shard size, in bytes, to allow when"
            + " performing the document migration.  Useful for preventing disk overflow.  Default: 50 * 1024 * 1024 * 1024 (50 GB)"), required = false)
        public long maxShardSizeBytes = 50 * 1024 * 1024 * 1024L;
    }

    public static class NoWorkLeftException extends Exception {
        public NoWorkLeftException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) throws Exception {
        // Grab out args
        Args arguments = new Args();
        JCommander.newBuilder()
                .addObject(arguments)
                .build()
                .parse(args);

        var luceneDirPath = Paths.get(arguments.luceneDirPath);
        var processManager = new ProcessManager(workItemId->{
            log.error("terminating RunRfsWorker because its lease has expired for " + workItemId);
            System.exit(PROCESS_TIMED_OUT);
        }, Clock.systemUTC());
        var workCoordinator = new OpenSearchWorkCoordinator(new ApacheHttpClient(new URI(arguments.targetHost)),
                TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS, UUID.randomUUID().toString());

        TryHandlePhaseFailure.executeWithTryCatch(() -> {
            log.info("Running RfsWorker");

            OpenSearchClient targetClient =
                    new OpenSearchClient(arguments.targetHost, arguments.targetUser, arguments.targetPass);
            DocumentReindexer reindexer = new DocumentReindexer(targetClient);

            SourceRepo sourceRepo = S3Repo.create(Paths.get(arguments.s3LocalDirPath),
                    new S3Uri(arguments.s3RepoUri), arguments.s3Region);
            SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);

            IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);
            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor,
                    luceneDirPath, ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES);

            run(LuceneDocumentsReader::new, reindexer, workCoordinator, processManager, indexMetadataFactory,
                    arguments.snapshotName, shardMetadataFactory, unpackerFactory, arguments.maxShardSizeBytes);
        });
    }

    public static DocumentsRunner.CompletionStatus run(Function<Path,LuceneDocumentsReader> readerFactory,
                                                       DocumentReindexer reindexer,
                                                       IWorkCoordinator workCoordinator,
                                                       ProcessManager processManager,
                                                       IndexMetadata.Factory indexMetadataFactory,
                                                       String snapshotName,
                                                       ShardMetadata.Factory shardMetadataFactory,
                                                       SnapshotShardUnpacker.Factory unpackerFactory,
                                                       long maxShardSizeBytes)
            throws IOException, InterruptedException, NoWorkLeftException {
        var scopedWorkCoordinator = new ScopedWorkCoordinatorHelper(workCoordinator, processManager);
        new ShardWorkPreparer().run(scopedWorkCoordinator, indexMetadataFactory, snapshotName);
        if (!workCoordinator.workItemsArePending()) {
            throw new NoWorkLeftException("No work items are pending/all work items have been processed.  Returning.");
        }
        return new DocumentsRunner(scopedWorkCoordinator,
                (name, shard) -> {
                    var shardMetadata = shardMetadataFactory.fromRepo(snapshotName, name, shard);
                    log.info("Shard size: " + shardMetadata.getTotalSizeBytes());
                    if (shardMetadata.getTotalSizeBytes() > maxShardSizeBytes) {
                        throw new DocumentsRunner.ShardTooLargeException(shardMetadata.getTotalSizeBytes(), maxShardSizeBytes);
                    }
                    return shardMetadata;
                },
                unpackerFactory, readerFactory, reindexer).migrateNextShard();
    }
}
