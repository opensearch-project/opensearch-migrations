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

import com.rfs.tracing.RootRfsContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;


import com.rfs.cms.CmsClient;
import com.rfs.cms.CmsEntry;
import com.rfs.cms.OpenSearchCmsClient;
import com.rfs.common.ClusterVersion;
import com.rfs.common.ConnectionDetails;
import com.rfs.common.GlobalMetadata;
import com.rfs.common.Logging;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.S3Uri;
import com.rfs.common.S3Repo;
import com.rfs.common.SnapshotCreator;
import com.rfs.common.SourceRepo;
import com.rfs.common.S3SnapshotCreator;
import com.rfs.common.SnapshotRepo;
import com.rfs.transformers.TransformFunctions;
import com.rfs.transformers.Transformer;
import com.rfs.version_es_7_10.GlobalMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.worker.GlobalState;
import com.rfs.worker.MetadataRunner;
import com.rfs.worker.Runner;
import com.rfs.worker.SnapshotRunner;
import com.rfs.worker.WorkerStep;

import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

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

        @Parameter(names = {"--index-template-whitelist"}, description = ("Optional.  List of template names to migrate"
            + " (e.g. 'posts_index_template1, posts_index_template2').  Default: empty list"), required = false)
        public List<String> indexTemplateWhitelist = List.of();

        @Parameter(names = {"--component-template-whitelist"}, description = ("Optional. List of component template names to migrate"
            + " (e.g. 'posts_template1, posts_template2').  Default: empty list"), required = false)
        public List<String> componentTemplateWhitelist = List.of();
        
        //https://opensearch.org/docs/2.11/api-reference/cluster-api/cluster-awareness/
        @Parameter(names = {"--min-replicas"}, description = ("Optional.  The minimum number of replicas configured for migrated indices on the target."
            + " This can be useful for migrating to targets which use zonal deployments and require additional replicas to meet zone requirements.  Default: 0")
            , required = false)
        public int minNumberOfReplicas = 0;

        @Parameter(names = {"--log-level"}, description = "What log level you want.  Default: 'info'", required = false, converter = Logging.ArgsConverter.class)
        public Level logLevel = Level.INFO;


        @Parameter(required = false,
                names = {"--otelCollectorEndpoint"},
                arity = 1,
                description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be" +
                        "forwarded. If no value is provided, metrics will not be forwarded.")
        String otelCollectorEndpoint;
    }

    public static void main(String[] args) throws Exception {
        // Grab out args
        Args arguments = new Args();
        JCommander.newBuilder()
            .addObject(arguments)
            .build()
            .parse(args);

        var rootContext = new RootRfsContext(
                RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(arguments.otelCollectorEndpoint, "rfs"),
                new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType()));

        String snapshotName = arguments.snapshotName;
        Path s3LocalDirPath = Paths.get(arguments.s3LocalDirPath);
        String s3RepoUri = arguments.s3RepoUri;
        String s3Region = arguments.s3Region;
        String sourceHost = arguments.sourceHost;
        String sourceUser = arguments.sourceUser;
        String sourcePass = arguments.sourcePass;
        String targetHost = arguments.targetHost;
        String targetUser = arguments.targetUser;
        String targetPass = arguments.targetPass;
        List<String> indexTemplateWhitelist = arguments.indexTemplateWhitelist;
        List<String> componentTemplateWhitelist = arguments.componentTemplateWhitelist;
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
            CmsClient cmsClient = new OpenSearchCmsClient(targetClient, rootContext.createWorkingStateContext());

            SnapshotCreator snapshotCreator = new S3SnapshotCreator(snapshotName, sourceClient, s3RepoUri, s3Region,
                    rootContext.createSnapshotCreateContext());
            SnapshotRunner snapshotWorker = new SnapshotRunner(globalState, cmsClient, snapshotCreator);
            snapshotWorker.run();

            SourceRepo sourceRepo = S3Repo.create(s3LocalDirPath, new S3Uri(s3RepoUri), s3Region);
            SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            GlobalMetadata.Factory metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
            GlobalMetadataCreator_OS_2_11 metadataCreator =
                    new GlobalMetadataCreator_OS_2_11(targetClient, List.of(), componentTemplateWhitelist,
                            indexTemplateWhitelist, rootContext.createMetadataMigrationContext());
            Transformer transformer = TransformFunctions.getTransformer(ClusterVersion.ES_7_10, ClusterVersion.OS_2_11, awarenessDimensionality);
            MetadataRunner metadataWorker = new MetadataRunner(globalState, cmsClient, snapshotName, metadataFactory, metadataCreator, transformer);
            metadataWorker.run();
            
        } catch (Runner.PhaseFailed e) {
            logPhaseFailureRecord(e.phase, e.nextStep, e.cmsEntry, e.e);
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error running RfsWorker", e);
            throw e;
        }
    }

    public static void logPhaseFailureRecord(GlobalState.Phase phase, WorkerStep nextStep, Optional<CmsEntry.Base> cmsEntry, Exception e) {
        ObjectNode errorBlob = new ObjectMapper().createObjectNode();
        errorBlob.put("exceptionMessage", e.getMessage());
        errorBlob.put("exceptionClass", e.getClass().getSimpleName());
        errorBlob.put("exceptionTrace", Arrays.toString(e.getStackTrace()));

        errorBlob.put("phase", phase.toString());

        String currentStep = (nextStep != null) ? nextStep.getClass().getSimpleName() : "null";
        errorBlob.put("currentStep", currentStep);

        String currentEntry = (cmsEntry.isPresent()) ? cmsEntry.toString() : "null";
        errorBlob.put("cmsEntry", currentEntry);


        logger.error(errorBlob.toString());
    }
}
