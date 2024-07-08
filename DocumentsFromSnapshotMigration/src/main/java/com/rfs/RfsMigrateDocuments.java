package com.rfs;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.rfs.cms.IWorkCoordinator;
import lombok.extern.slf4j.Slf4j;
import com.rfs.cms.ApacheHttpClient;
import com.rfs.cms.OpenSearchWorkCoordinator;
import com.rfs.cms.LeaseExpireTrigger;
import com.rfs.cms.ScopedWorkCoordinator;
import com.rfs.worker.ShardWorkPreparer;

import com.rfs.common.DefaultSourceRepoAccessor;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.IndexMetadata;
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

        @Parameter(names = {"--snapshot-local-dir"},
                required = false,
                description = ("The absolute path to the directory on local disk where the snapshot exists.  Use this parameter"
                    + " if have a copy of the snapshot disk.  Mutually exclusive with --s3-local-dir, --s3-repo-uri, and --s3-region."
                ))
        public String snapshotLocalDir = null;

        @Parameter(names = {"--s3-local-dir"},
                required = false,
                description = ("The absolute path to the directory on local disk to download S3 files to.  If you supply this, you must"
                    + " also supply --s3-repo-uri and --s3-region.  Mutually exclusive with --snapshot-local-dir."
                ))
        public String s3LocalDir = null;

        @Parameter(names = {"--s3-repo-uri"},
                required = false,
                description = ("The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2.  If you supply this, you must"
                    + " also supply --s3-local-dir and --s3-region.  Mutually exclusive with --snapshot-local-dir."
                ))
        public String s3RepoUri = null;

        @Parameter(names = {"--s3-region"},
                required = false,
                description = ("The AWS Region the S3 bucket is in, like: us-east-2.  If you supply this, you must"
                    + " also supply --s3-local-dir and --s3-repo-uri.  Mutually exclusive with --snapshot-local-dir."
                ))
        public String s3Region = null;

        @Parameter(names = {"--lucene-dir"},
                required = true,
                description = "The absolute path to the directory where we'll put the Lucene docs")
        public String luceneDir;

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

        @Parameter(names = {"--index-allowlist"}, description = ("Optional.  List of index names to migrate"
            + " (e.g. 'logs_2024_01, logs_2024_02').  Default: all non-system indices (e.g. those not starting with '.')"), required = false)
        public List<String> indexAllowlist = List.of();

        @Parameter(names = {"--max-shard-size-bytes"}, description = ("Optional. The maximum shard size, in bytes, to allow when"
            + " performing the document migration.  Useful for preventing disk overflow.  Default: 50 * 1024 * 1024 * 1024 (50 GB)"), required = false)
        public long maxShardSizeBytes = 50 * 1024 * 1024 * 1024L;
    }

    public static class NoWorkLeftException extends Exception {
        public NoWorkLeftException(String message) {
            super(message);
        }
    }

    public static void validateArgs(Args args) {
        boolean isSnapshotLocalDirProvided = args.snapshotLocalDir != null;
        boolean areAllS3ArgsProvided = args.s3LocalDir != null && args.s3RepoUri != null && args.s3Region != null;
        boolean areAnyS3ArgsProvided = args.s3LocalDir != null || args.s3RepoUri != null || args.s3Region != null;

        if (isSnapshotLocalDirProvided && areAnyS3ArgsProvided) {
            throw new ParameterException("You must provide either --snapshot-local-dir or --s3-local-dir, --s3-repo-uri, and --s3-region, but not both.");
        }

        if (areAnyS3ArgsProvided && !areAllS3ArgsProvided) {
            throw new ParameterException("If provide the S3 Snapshot args, you must provide all of them (--s3-local-dir, --s3-repo-uri and --s3-region).");
        }

        if (!isSnapshotLocalDirProvided && !areAllS3ArgsProvided) {
            throw new ParameterException("You must provide either --snapshot-local-dir or --s3-local-dir, --s3-repo-uri, and --s3-region.");
        }

    }

    public static void main(String[] args) throws Exception {
        Args arguments = new Args();
        JCommander.newBuilder()
                .addObject(arguments)
                .build()
                .parse(args);

        validateArgs(arguments);

        var luceneDirPath = Paths.get(arguments.luceneDir);
        var snapshotLocalDirPath = arguments.snapshotLocalDir != null ? Paths.get(arguments.snapshotLocalDir) : null;


        try (var processManager = new LeaseExpireTrigger(workItemId->{
            log.error("Terminating RunRfsWorker because its lease has expired for " + workItemId);
            System.exit(PROCESS_TIMED_OUT);
        }, Clock.systemUTC())) {
            var workCoordinator = new OpenSearchWorkCoordinator(new ApacheHttpClient(new URI(arguments.targetHost)),
                    TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS, UUID.randomUUID().toString());

            TryHandlePhaseFailure.executeWithTryCatch(() -> {
                log.info("Running RfsWorker");

                OpenSearchClient targetClient =
                        new OpenSearchClient(arguments.targetHost, arguments.targetUser, arguments.targetPass, false);
                DocumentReindexer reindexer = new DocumentReindexer(targetClient);

                SourceRepo sourceRepo;
                if (snapshotLocalDirPath == null) {
                    sourceRepo = S3Repo.create(
                        Paths.get(arguments.s3LocalDir),
                        new S3Uri(arguments.s3RepoUri),
                        arguments.s3Region
                    );
                } else {
                    sourceRepo = new FileSystemRepo(snapshotLocalDirPath);
                }
                SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);

                IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
                ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);
                DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
                SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor,
                        luceneDirPath, ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES);

                run(LuceneDocumentsReader::new, reindexer, workCoordinator, processManager, indexMetadataFactory,
                        arguments.snapshotName, arguments.indexAllowlist, shardMetadataFactory, unpackerFactory,
                        arguments.maxShardSizeBytes);
            });
        }
    }

    public static DocumentsRunner.CompletionStatus run(Function<Path,LuceneDocumentsReader> readerFactory,
                                                       DocumentReindexer reindexer,
                                                       IWorkCoordinator workCoordinator,
                                                       LeaseExpireTrigger leaseExpireTrigger,
                                                       IndexMetadata.Factory indexMetadataFactory,
                                                       String snapshotName,
                                                       List<String> indexAllowlist,
                                                       ShardMetadata.Factory shardMetadataFactory,
                                                       SnapshotShardUnpacker.Factory unpackerFactory,
                                                       long maxShardSizeBytes)
            throws IOException, InterruptedException, NoWorkLeftException {
        var scopedWorkCoordinator = new ScopedWorkCoordinator(workCoordinator, leaseExpireTrigger);
        confirmShardPrepIsComplete(indexMetadataFactory, snapshotName, indexAllowlist, scopedWorkCoordinator);
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

    private static void confirmShardPrepIsComplete(IndexMetadata.Factory indexMetadataFactory,
                                                   String snapshotName,
                                                   List<String> indexAllowlist,
                                                   ScopedWorkCoordinator scopedWorkCoordinator)
            throws IOException, InterruptedException
    {
        // assume that the shard setup work will be done quickly, much faster than its lease in most cases.
        // in cases where that isn't true, doing random backoff across the fleet should guarantee that eventually,
        // these workers will attenuate enough that it won't cause an impact on the coordination server
        long lockRenegotiationMillis = 1000;
        for (int shardSetupAttemptNumber=0; ; ++shardSetupAttemptNumber) {
            try {
                new ShardWorkPreparer().run(scopedWorkCoordinator, indexMetadataFactory, snapshotName, indexAllowlist);
                return;
            } catch (IWorkCoordinator.LeaseLockHeldElsewhereException e) {
                long finalLockRenegotiationMillis = lockRenegotiationMillis;
                int finalShardSetupAttemptNumber = shardSetupAttemptNumber;
                log.atInfo().setMessage(() ->
                        "After " + finalShardSetupAttemptNumber + "another process holds the lock" +
                                " for setting up the shard work items.  " +
                                "Waiting " + finalLockRenegotiationMillis + "ms before trying again.").log();
                Thread.sleep(lockRenegotiationMillis);
                lockRenegotiationMillis *= 2;
                continue;
            }
        }
    }
}
