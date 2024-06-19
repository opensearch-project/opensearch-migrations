package com.rfs;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.rfs.cms.CmsClient;
import com.rfs.cms.OpenSearchCmsClient;
import com.rfs.common.ConnectionDetails;
import com.rfs.common.Logging;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.TryHandlePhaseFailure;
import com.rfs.common.S3SnapshotCreator;
import com.rfs.worker.GlobalState;
import com.rfs.worker.SnapshotRunner;

@Slf4j
public class CreateSnapshot {
    public static class Args {
        @Parameter(names = {"--snapshot-name"}, description = "The name of the snapshot to migrate", required = true)
        public String snapshotName;

        @Parameter(names = {"--s3-repo-uri"}, description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2", required = true)
        public String s3RepoUri;

        @Parameter(names = {"--s3-region"}, description = "The AWS Region the S3 bucket is in, like: us-east-2", required = true)
        public String s3Region;

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
    }

    public static void main(String[] args) throws Exception {
        // Grab out args
        Args arguments = new Args();
        JCommander.newBuilder()
            .addObject(arguments)
            .build()
            .parse(args);

        final String snapshotName = arguments.snapshotName;
        final String s3RepoUri = arguments.s3RepoUri;
        final String s3Region = arguments.s3Region;
        final String sourceHost = arguments.sourceHost;
        final String sourceUser = arguments.sourceUser;
        final String sourcePass = arguments.sourcePass;
        final String targetHost = arguments.targetHost;
        final String targetUser = arguments.targetUser;
        final String targetPass = arguments.targetPass;

        final ConnectionDetails sourceConnection = new ConnectionDetails(sourceHost, sourceUser, sourcePass);
        final ConnectionDetails targetConnection = new ConnectionDetails(targetHost, targetUser, targetPass);

        TryHandlePhaseFailure.executeWithTryCatch(() -> {
            log.info("Running RfsWorker");
            GlobalState globalState = GlobalState.getInstance();
            OpenSearchClient sourceClient = new OpenSearchClient(sourceConnection);
            OpenSearchClient targetClient = new OpenSearchClient(targetConnection);
            final CmsClient cmsClient = new OpenSearchCmsClient(targetClient);

            final SnapshotCreator snapshotCreator = new S3SnapshotCreator(snapshotName, sourceClient, s3RepoUri, s3Region);
            final SnapshotRunner snapshotWorker = new SnapshotRunner(globalState, cmsClient, snapshotCreator);
            snapshotWorker.run();
        });
    }
}
