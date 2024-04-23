package com.rfs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.lucene.document.Document;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.rfs.common.*;
import com.rfs.transformers.*;
import com.rfs.version_es_6_8.*;
import com.rfs.version_es_7_10.*;
import com.rfs.version_os_2_11.*;

public class ReindexFromSnapshot {
    private static final Logger logger = LogManager.getLogger(ReindexFromSnapshot.class);

    public static class Args {
        @Parameter(names = {"-n", "--snapshot-name"}, description = "The name of the snapshot to migrate", required = true)
        public String snapshotName;

        @Parameter(names = {"--snapshot-dir"}, description = "The absolute path to the existing source snapshot directory on local disk", required = false)
        public String snapshotDirPath = null;

        @Parameter(names = {"--snapshot-local-repo-dir"}, description = "The absolute path to take and store a new snapshot on source, this location should be accessible by the source and this app", required = false)
        public String snapshotLocalRepoDirPath = null;

        @Parameter(names = {"--s3-local-dir"}, description = "The absolute path to the directory on local disk to download S3 files to", required = false)
        public String s3LocalDirPath = null;

        @Parameter(names = {"--s3-repo-uri"}, description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2", required = false)
        public String s3RepoUri = null;

        @Parameter(names = {"--s3-region"}, description = "The AWS Region the S3 bucket is in, like: us-east-2", required = false)
        public String s3Region = null;

        @Parameter(names = {"-l", "--lucene-dir"}, description = "The absolute path to the directory where we'll put the Lucene docs", required = true)
        public String luceneDirPath;

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

        @Parameter(names = {"-s", "--source-version"}, description = "The source cluster's version (e.g. 'es_6_8')", required = true, converter = ClusterVersion.ArgsConverter.class)
        public ClusterVersion sourceVersion;

        @Parameter(names = {"-t", "--target-version"}, description = "The target cluster's version (e.g. 'os_2_11')", required = true, converter = ClusterVersion.ArgsConverter.class)
        public ClusterVersion targetVersion;

        @Parameter(names = {"--movement-type"}, description = "What you want to move - everything, metadata, or data.  Default: 'everything'", required = false, converter = MovementType.ArgsConverter.class)
        public MovementType movementType = MovementType.EVERYTHING;

        //https://opensearch.org/docs/2.11/api-reference/cluster-api/cluster-awareness/
        @Parameter(names = {"--min-replicas"}, description = "The minimum number of replicas configured for migrated indices on the target. This can be useful for migrating to targets which use zonal deployments and require additional replicas to meet zone requirements", required = true)
        public int minNumberOfReplicas;

        @Parameter(names = {"--template-whitelist"}, description = "List of template names to migrate. Note: For ES 6.8 this refers to legacy templates and for ES 7.10 this is index templates (e.g. 'posts_index_template1, posts_index_template2')", required = false)
        public List<String> templateWhitelist;

        @Parameter(names = {"--component-template-whitelist"}, description = "List of component template names to migrate (e.g. 'posts_template1, posts_template2')", required = false)
        public List<String> componentTemplateWhitelist;

        @Parameter(names = {"--enable-persistent-run"}, description = "If enabled, the java process will continue in an idle mode after the migration is completed.  Default: false", arity=0, required = false)
        public boolean enablePersistentRun;

        @Parameter(names = {"--log-level"}, description = "What log level you want.  Default: 'info'", required = false, converter = Logging.ArgsConverter.class)
        public Level logLevel = Level.INFO;

        @Parameter(names = {"--index_suffix"}, description = "An optional suffix to add to index names as they're transfered. Default: none", required = false)
        public String indexSuffix = "";
    }

    public static void main(String[] args) throws InterruptedException {
        // Grab out args
        Args arguments = new Args();
        JCommander.newBuilder()
            .addObject(arguments)
            .build()
            .parse(args);

        String snapshotName = arguments.snapshotName;
        Path snapshotDirPath = (arguments.snapshotDirPath != null) ? Paths.get(arguments.snapshotDirPath) : null;
        Path snapshotLocalRepoDirPath = (arguments.snapshotLocalRepoDirPath != null) ? Paths.get(arguments.snapshotLocalRepoDirPath) : null;
        Path s3LocalDirPath = (arguments.s3LocalDirPath != null) ? Paths.get(arguments.s3LocalDirPath) : null;
        String s3RepoUri = arguments.s3RepoUri;
        String s3Region = arguments.s3Region;
        Path luceneDirPath = Paths.get(arguments.luceneDirPath);
        String sourceHost = arguments.sourceHost;
        String sourceUser = arguments.sourceUser;
        String sourcePass = arguments.sourcePass;
        String targetHost = arguments.targetHost;
        String targetUser = arguments.targetUser;
        String targetPass = arguments.targetPass;
        int awarenessDimensionality = arguments.minNumberOfReplicas + 1;
        ClusterVersion sourceVersion = arguments.sourceVersion;
        ClusterVersion targetVersion = arguments.targetVersion;
        List<String> templateWhitelist = arguments.templateWhitelist;
        List<String> componentTemplateWhitelist = arguments.componentTemplateWhitelist;
        MovementType movementType = arguments.movementType;
        Level logLevel = arguments.logLevel;
        String indexSuffix = arguments.indexSuffix;

        Logging.setLevel(logLevel);

        ConnectionDetails sourceConnection = new ConnectionDetails(sourceHost, sourceUser, sourcePass);
        ConnectionDetails targetConnection = new ConnectionDetails(targetHost, targetUser, targetPass);

        // Sanity checks
        if (!((sourceVersion == ClusterVersion.ES_6_8) || (sourceVersion == ClusterVersion.ES_7_10))) {
            throw new IllegalArgumentException("Unsupported source version: " + sourceVersion);
        }

        if (targetVersion != ClusterVersion.OS_2_11) {
            throw new IllegalArgumentException("Unsupported target version: " + sourceVersion);
        }

        /*
         * You have three options for providing the snapshot data
         * 1. A local snapshot directory
         * 2. A source host we'll take the snapshot from
         * 3. An S3 URI of an existing snapshot in S3
         * 
         * If you provide the source host, you still need to provide the S3 details or the snapshotLocalRepoDirPath to write the snapshot to.
         */
        if (snapshotDirPath != null && (sourceHost != null || s3RepoUri != null)) {
            throw new IllegalArgumentException("If you specify a local directory to take the snapshot from, you cannot specify a source host or S3 URI");
        } else if (sourceHost != null) {
           if (s3RepoUri == null && s3Region == null && s3LocalDirPath == null && snapshotLocalRepoDirPath == null) {
                throw new IllegalArgumentException(
                    "If you specify a source host, you must also specify the S3 details or the snapshotLocalRepoDirPath to write the snapshot to as well");
            }
           if ((s3RepoUri != null || s3Region != null || s3LocalDirPath != null) &&
               (s3RepoUri == null || s3Region == null || s3LocalDirPath == null)) {
               throw new IllegalArgumentException(
                   "You must specify all S3 details (repo URI, region, local directory path)");
           }
        }

        SourceRepo repo;
        if (snapshotDirPath != null) {
            repo = new FilesystemRepo(snapshotDirPath);
        } else if (s3RepoUri != null && s3Region != null && s3LocalDirPath != null) {
            repo = S3Repo.create(s3LocalDirPath, s3RepoUri, s3Region);
        } else if (snapshotLocalRepoDirPath != null) {
            repo = new FilesystemRepo(snapshotLocalRepoDirPath);
        } else {
            throw new IllegalArgumentException("Could not construct a source repo from the available, user-supplied arguments");
        }

        // Set the transformer
        Transformer transformer = TransformFunctions.getTransformer(sourceVersion, targetVersion, awarenessDimensionality);

        try {

            if (sourceHost != null) {
                // ==========================================================================================================
                // Create the snapshot if necessary
                // ==========================================================================================================            
                logger.info("==================================================================");
                logger.info("Attempting to create the snapshot...");
                SnapshotCreator snapshotCreator = repo instanceof S3Repo
                    ? new S3SnapshotCreator(snapshotName, sourceConnection, s3RepoUri, s3Region)
                    : new FileSystemSnapshotCreator(snapshotName, sourceConnection, snapshotLocalRepoDirPath.toString());
                snapshotCreator.registerRepo();
                snapshotCreator.createSnapshot();
                while (!snapshotCreator.isSnapshotFinished()) {
                    logger.info("Snapshot not finished yet; sleeping for 5 seconds...");
                    Thread.sleep(5000);
                }
                logger.info("Snapshot created successfully");
            }

            // ==========================================================================================================
            // Read the Repo data file
            // ==========================================================================================================
            logger.info("==================================================================");
            logger.info("Attempting to read Repo data file...");
            SnapshotRepo.Provider repoDataProvider;
            if (sourceVersion == ClusterVersion.ES_6_8) {
                repoDataProvider = new SnapshotRepoProvider_ES_6_8(repo);
            } else {
                repoDataProvider = new SnapshotRepoProvider_ES_7_10(repo);
            }

            if (repoDataProvider.getSnapshots().size() > 1){
                // Avoid having to deal with things like incremental snapshots
                throw new IllegalArgumentException("Only repos with a single snapshot are supported at this time");
            }

            logger.info("Repo data read successfully");

            // ==========================================================================================================
            // Read the Snapshot details
            // ==========================================================================================================
            logger.info("==================================================================");
            logger.info("Attempting to read Snapshot details...");
            String snapshotIdString = repoDataProvider.getSnapshotId(snapshotName);

            if (snapshotIdString == null) {
                logger.error("Snapshot not found");
                return;
            }
            SnapshotMetadata.Data snapshotMetadata;
            if (sourceVersion == ClusterVersion.ES_6_8) {
                snapshotMetadata = new SnapshotMetadataFactory_ES_6_8().fromRepo(repo, repoDataProvider, snapshotName);
            } else {
                snapshotMetadata = new SnapshotMetadataFactory_ES_7_10().fromRepo(repo, repoDataProvider, snapshotName);
            }
            logger.info("Snapshot data read successfully");

            if (!snapshotMetadata.isIncludeGlobalState() && ((movementType == MovementType.EVERYTHING) || (movementType == MovementType.METADATA))){
                throw new IllegalArgumentException("Snapshot does not include global state, so we can't move metadata");
            }

            if (!snapshotMetadata.isSuccessful()){
                throw new IllegalArgumentException("Snapshot must be successful; its actual state is " + snapshotMetadata.getState());
            }

            // We might not actually get this far if the snapshot is the wrong version; we'll probably have failed to
            // parse one of the previous metadata files
            if (sourceVersion != ClusterVersion.fromInt(snapshotMetadata.getVersionId())){
                throw new IllegalArgumentException("Snapshot version is " + snapshotMetadata.getVersionId() + ", but source version is " + sourceVersion);
            }

            if ((movementType == MovementType.EVERYTHING) || (movementType == MovementType.METADATA)){
                // ==========================================================================================================
                // Read the Global Metadata
                // ==========================================================================================================
                logger.info("==================================================================");
                logger.info("Attempting to read Global Metadata details...");
                GlobalMetadata.Data globalMetadata;
                if (sourceVersion == ClusterVersion.ES_6_8) {
                    globalMetadata = new GlobalMetadataFactory_ES_6_8().fromRepo(repo, repoDataProvider, snapshotName);
                } else {
                    globalMetadata = new GlobalMetadataFactory_ES_7_10().fromRepo(repo, repoDataProvider, snapshotName);
                }
                logger.info("Global Metadata read successfully");

                // ==========================================================================================================
                // Recreate the Global Metadata
                // ==========================================================================================================
                logger.info("==================================================================");
                logger.info("Attempting to recreate the Global Metadata...");

                if (sourceVersion == ClusterVersion.ES_6_8) {
                    ObjectNode root = globalMetadata.toObjectNode();
                    ObjectNode transformedRoot = transformer.transformGlobalMetadata(root);                    
                    GlobalMetadataCreator_OS_2_11.create(transformedRoot, targetConnection, Collections.emptyList(), templateWhitelist);
                } else if (sourceVersion == ClusterVersion.ES_7_10) {
                    ObjectNode root = globalMetadata.toObjectNode();
                    ObjectNode transformedRoot = transformer.transformGlobalMetadata(root);                    
                    GlobalMetadataCreator_OS_2_11.create(transformedRoot, targetConnection, componentTemplateWhitelist, templateWhitelist);
                }
            }

            // ==========================================================================================================
            // Read all the Index Metadata
            // ==========================================================================================================
            logger.info("==================================================================");
            logger.info("Attempting to read Index Metadata...");
            List<IndexMetadata.Data> indexMetadatas = new ArrayList<>();
            for (SnapshotRepo.Index index : repoDataProvider.getIndicesInSnapshot(snapshotName)) {
                logger.info("Reading Index Metadata for index: " + index.getName());
                IndexMetadata.Data indexMetadata;
                if (sourceVersion == ClusterVersion.ES_6_8) {
                    indexMetadata = new IndexMetadataFactory_ES_6_8().fromRepo(repo, repoDataProvider, snapshotName, index.getName());
                } else {
                    indexMetadata = new IndexMetadataFactory_ES_7_10().fromRepo(repo, repoDataProvider, snapshotName, index.getName());
                }
                indexMetadatas.add(indexMetadata);
            }
            logger.info("Index Metadata read successfully");

            if ((movementType == MovementType.EVERYTHING) || (movementType == MovementType.METADATA)){
                // ==========================================================================================================
                // Recreate the Indices
                // ==========================================================================================================
                logger.info("==================================================================");
                logger.info("Attempting to recreate the indices...");
                for (IndexMetadata.Data indexMetadata : indexMetadatas) {
                    String reindexName = indexMetadata.getName() + indexSuffix;
                    logger.info("Recreating index " + indexMetadata.getName() + " as " + reindexName + " on target...");

                    ObjectNode root = indexMetadata.toObjectNode();
                    ObjectNode transformedRoot = transformer.transformIndexMetadata(root);
                    IndexMetadataData_OS_2_11 indexMetadataOS211 = new IndexMetadataData_OS_2_11(transformedRoot, indexMetadata.getId(), reindexName);
                    IndexCreator_OS_2_11.create(reindexName, indexMetadataOS211, targetConnection);
                }
            }

            if ((movementType == MovementType.EVERYTHING) || (movementType == MovementType.DATA)){
                // ==========================================================================================================
                // Unpack the snapshot blobs
                // ==========================================================================================================
                logger.info("==================================================================");
                logger.info("Unpacking blob files to disk...");

                for (IndexMetadata.Data indexMetadata : indexMetadatas) {
                    logger.info("Processing index: " + indexMetadata.getName());
                    for (int shardId = 0; shardId < indexMetadata.getNumberOfShards(); shardId++) {
                        logger.info("=== Shard ID: " + shardId + " ===");

                        // Get the shard metadata
                        ShardMetadata.Data shardMetadata;
                        if (sourceVersion == ClusterVersion.ES_6_8) {
                            shardMetadata = new ShardMetadataFactory_ES_6_8().fromRepo(repo, repoDataProvider, snapshotName, indexMetadata.getName(), shardId);
                        } else {
                            shardMetadata = new ShardMetadataFactory_ES_7_10().fromRepo(repo, repoDataProvider, snapshotName, indexMetadata.getName(), shardId);
                        }

                        // Unpack the shard
                        int bufferSize;
                        if (sourceVersion == ClusterVersion.ES_6_8) {
                            bufferSize = ElasticsearchConstants_ES_6_8.BUFFER_SIZE_IN_BYTES;
                        } else {
                            bufferSize = ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES;
                        }

                        SnapshotShardUnpacker.unpack(repo, shardMetadata, luceneDirPath, bufferSize);
                    }
                }

                logger.info("Blob files unpacked successfully");

                // ==========================================================================================================
                // Reindex the documents
                // ==========================================================================================================
                logger.info("==================================================================");
                logger.info("Reindexing the documents...");

                for (IndexMetadata.Data indexMetadata : indexMetadatas) {
                    for (int shardId = 0; shardId < indexMetadata.getNumberOfShards(); shardId++) {
                        logger.info("=== Index Id: " + indexMetadata.getName() + ", Shard ID: " + shardId + " ===");

                        List<Document> documents = LuceneDocumentsReader.readDocuments(luceneDirPath, indexMetadata.getName(), shardId);
                        logger.info("Documents read successfully");

                        for (Document document : documents) {
                            String targetIndex = indexMetadata.getName() + indexSuffix;
                            DocumentReindexer.reindex(targetIndex, document, targetConnection);
                        }
                    }
                }

                logger.info("Documents reindexed successfully");

                logger.info("Refreshing newly added documents");
                DocumentReindexer.refreshAllDocuments(targetConnection);
                logger.info("Refresh complete");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Optional temporary persistent runtime flag to continue Java process after steps have completed. This should get
        // replaced as this app develops and becomes aware of determining work to be completed
        if (arguments.enablePersistentRun) {
            while (true) {
                logger.info("Process is in idle mode, to retry migration please restart this app.");
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            }
        }
    }
}
