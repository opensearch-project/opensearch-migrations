package com.rfs;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.S3SnapshotCreator;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.TryHandlePhaseFailure;
import com.rfs.worker.SnapshotRunner;

import java.util.function.Function;

@Slf4j
public class CreateSnapshot {
    public static class Args {
        @Parameter(names = {"--snapshot-name"},
                required = true,
                description = "The name of the snapshot to migrate")
        public String snapshotName;

        @Parameter(names = {"--file-system-repo-path"},
                required = false,
                description = "The full path to the snapshot repo on the file system.")
        public String fileSystemRepoPath;

        @Parameter(names = {"--s3-repo-uri"},
                required = false,
                description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2")
        public String s3RepoUri;

        @Parameter(names = {"--s3-region"},
                required = false,
                description = "The AWS Region the S3 bucket is in, like: us-east-2"
        )
        public String s3Region;

        @Parameter(names = {"--source-host"},
                required = true,
                description = "The source host and port (e.g. http://localhost:9200)")
        public String sourceHost;

        @Parameter(names = {"--source-username"},
                description = "Optional.  The source username; if not provided, will assume no auth on source")
        public String sourceUser = null;

        @Parameter(names = {"--source-password"},
                description = "Optional.  The source password; if not provided, will assume no auth on source")
        public String sourcePass = null;

        @Parameter(names = {"--source-insecure"},
                description = "Allow untrusted SSL certificates for source")
        public boolean sourceInsecure = false;

        @Parameter(names = {"--no-wait"}, description = "Optional.  If provided, the snapshot runner will not wait for completion")
        public boolean noWait = false;

        @Parameter(names = {"--max-snapshot-rate-mb-per-node"},
                required = false,
                description = "The maximum snapshot rate in megabytes per second per node")
        public Integer maxSnapshotRateMBPerNode;
    }

    @Getter
    @AllArgsConstructor
    public static class S3RepoInfo {
        String awsRegion;
        String repoUri;
    }

    public static void main(String[] args) throws Exception {
        // Grab out args
        Args arguments = new Args();
        JCommander.newBuilder()
                .addObject(arguments)
                .build()
                .parse(args);

        if (arguments.fileSystemRepoPath == null && arguments.s3RepoUri == null) {
            throw new ParameterException("Either file-system-repo-path or s3-repo-uri must be set");
        }
        if (arguments.fileSystemRepoPath != null && arguments.s3RepoUri != null) {
            throw new ParameterException("Only one of file-system-repo-path and s3-repo-uri can be set");
        }
        if (arguments.s3RepoUri != null && arguments.s3Region == null) {
            throw new ParameterException("If an s3 repo is being used, s3-region must be set");
        }

        log.info("Running CreateSnapshot with {}", String.join(" ", args));
        run(c -> ((arguments.fileSystemRepoPath != null)
                        ? new FileSystemSnapshotCreator(arguments.snapshotName, c, arguments.fileSystemRepoPath)
                        : new S3SnapshotCreator(arguments.snapshotName, c, arguments.s3RepoUri, arguments.s3Region, arguments.maxSnapshotRateMBPerNode)),
                new OpenSearchClient(arguments.sourceHost, arguments.sourceUser, arguments.sourcePass, arguments.sourceInsecure),
                arguments.noWait
        );
    }

    public static void run(Function<OpenSearchClient, SnapshotCreator> snapshotCreatorFactory,
                           OpenSearchClient openSearchClient, boolean noWait) throws Exception {
        TryHandlePhaseFailure.executeWithTryCatch(() -> {
            if (noWait) {
                SnapshotRunner.run(snapshotCreatorFactory.apply(openSearchClient));
            } else {
                SnapshotRunner.runAndWaitForCompletion(snapshotCreatorFactory.apply(openSearchClient));
            }
        });
    }
}


