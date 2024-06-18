package com.rfs;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import com.rfs.common.UsernamePassword;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import com.rfs.common.ConnectionDetails;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.TryHandlePhaseFailure;
import com.rfs.common.S3SnapshotCreator;
import com.rfs.worker.SnapshotRunner;

import java.util.Optional;
import java.util.function.Function;

@Slf4j
public class CreateSnapshot {
    public static class Args {
        @Parameter(names = {"--snapshot-name"},
                required = true,
                description = "The name of the snapshot to migrate")
        public String snapshotName;

        @Parameter(names = {"--s3-repo-uri"},
                required = true,
                description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2")
        public String s3RepoUri;

        @Parameter(names = {"--s3-region"},
                required = true,
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

        log.info("Running CreateSnapshot with " + String.join(" ", args));
        run(c -> new S3SnapshotCreator(arguments.snapshotName, c, arguments.s3RepoUri, arguments.s3Region),
                new OpenSearchClient(arguments.sourceHost, arguments.sourceUser, arguments.sourcePass));
    }

    public static void run(Function<OpenSearchClient,SnapshotCreator> snapshotCreatorFactory,
                           OpenSearchClient openSearchClient)
            throws Exception {
        TryHandlePhaseFailure.executeWithTryCatch(() -> {
            SnapshotRunner.runAndWaitForCompletion(snapshotCreatorFactory.apply(openSearchClient));
        });
    }
}
