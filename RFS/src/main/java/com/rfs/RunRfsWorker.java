package com.rfs;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.rfs.cms.*;
import com.rfs.common.*;
import com.rfs.worker.GlobalData;
import com.rfs.worker.RfsWorker;

public class RunRfsWorker {
    private static final Logger logger = LogManager.getLogger(RunRfsWorker.class);

    public static class Args {
        @Parameter(names = {"-n", "--snapshot-name"}, description = "The name of the snapshot to migrate", required = true)
        public String snapshotName;

        @Parameter(names = {"--s3-repo-uri"}, description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2", required = false)
        public String s3RepoUri = null;

        @Parameter(names = {"--s3-region"}, description = "The AWS Region the S3 bucket is in, like: us-east-2", required = false)
        public String s3Region = null;

        @Parameter(names = {"--source-host"}, description = "The source host and port (e.g. http://localhost:9200)", required = false)
        public String sourceHost = null;

        @Parameter(names = {"--source-username"}, description = "The source username; if not provided, will assume no auth on source", required = false)
        public String sourceUser = null;

        @Parameter(names = {"--source-password"}, description = "The source password; if not provided, will assume no auth on source", required = false)
        public String sourcePass = null;

        @Parameter(names = {"--target-host"}, description = "The target host and port (e.g. http://localhost:9200)", required = true)
        public String targetHost;

        @Parameter(names = {"--target-username"}, description = "The target username; if not provided, will assume no auth on target", required = false)
        public String targetUser = null;

        @Parameter(names = {"--target-password"}, description = "The target password; if not provided, will assume no auth on target", required = false)
        public String targetPass = null;

        @Parameter(names = {"--log-level"}, description = "What log level you want.  Default: 'info'", required = false, converter = Logging.ArgsConverter.class)
        public Level logLevel = Level.INFO;
    }

    public static void main(String[] args) throws InterruptedException {
        // Grab out args
        Args arguments = new Args();
        JCommander.newBuilder()
            .addObject(arguments)
            .build()
            .parse(args);

        String snapshotName = arguments.snapshotName;
        String s3RepoUri = arguments.s3RepoUri;
        String s3Region = arguments.s3Region;
        String sourceHost = arguments.sourceHost;
        String sourceUser = arguments.sourceUser;
        String sourcePass = arguments.sourcePass;
        String targetHost = arguments.targetHost;
        String targetUser = arguments.targetUser;
        String targetPass = arguments.targetPass;
        Level logLevel = arguments.logLevel;

        Logging.setLevel(logLevel);

        ConnectionDetails sourceConnection = new ConnectionDetails(sourceHost, sourceUser, sourcePass);
        ConnectionDetails targetConnection = new ConnectionDetails(targetHost, targetUser, targetPass);

        try {
            GlobalData globalState = GlobalData.getInstance();
            OpenSearchClient sourceClient = new OpenSearchClient(sourceConnection);
            OpenSearchClient targetClient = new OpenSearchClient(targetConnection);
            CmsClient cmsClient = new OpenSearchCmsClient(targetClient);
            SnapshotCreator snapshotCreator = new S3SnapshotCreator(snapshotName, sourceClient, s3RepoUri, s3Region);

            RfsWorker worker = new RfsWorker(globalState, cmsClient, snapshotCreator);
            worker.run();            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
