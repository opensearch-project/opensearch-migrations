package com.rfs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

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
        @Parameter(names = {"-n", "--snapshot-name"}, description = "The name of the snapshot to read", required = true)
        public String snapshotName;

        @Parameter(names = {"--snapshot-dir"}, description = "The absolute path to the source snapshot directory on local disk", required = false)
        public String snapshotDirPath = null;

        @Parameter(names = {"--s3-local-dir"}, description = "The absolute path to the directory on local disk to write S3 files to", required = false)
        public String s3LocalDirPath = null;

        @Parameter(names = {"--s3-repo-uri"}, description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2", required = false)
        public String s3RepoUri = null;

        @Parameter(names = {"--s3-region"}, description = "The AWS Region the S3 bucket is in, like: us-east-2", required = false)
        public String s3Region = null;

        @Parameter(names = {"-l", "--lucene-dir"}, description = "The absolute path to the directory where we'll put the Lucene docs", required = true)
        public String luceneDirPath;

        @Parameter(names = {"-h", "--target-host"}, description = "The target host and port (e.g. http://localhost:9200)", required = true)
        public String targetHost;

        @Parameter(names = {"-u", "--target-username"}, description = "The target username", required = true)
        public String targetUser;

        @Parameter(names = {"-p", "--target-password"}, description = "The target password", required = true)
        public String targetPass;

        @Parameter(names = {"-s", "--source-version"}, description = "Source version", required = true, converter = ClusterVersion.ArgsConverter.class)
        public ClusterVersion sourceVersion;

        @Parameter(names = {"-t", "--target-version"}, description = "Target version", required = true, converter = ClusterVersion.ArgsConverter.class)
        public ClusterVersion targetVersion;

        @Parameter(names = {"--movement-type"}, description = "What you want to move - everything, metadata, or data.  Default: 'everything'", required = false, converter = MovementType.ArgsConverter.class)
        public MovementType movementType = MovementType.EVERYTHING;

        @Parameter(names = {"--log-level"}, description = "What log level you want.  Default: 'info'", required = false, converter = Logging.ArgsConverter.class)
        public Level logLevel = Level.INFO;
    }

    public static void main(String[] args) {
        // Grab out args
        Args arguments = new Args();
        JCommander.newBuilder()
            .addObject(arguments)
            .build()
            .parse(args);
        
        String snapshotName = arguments.snapshotName;
        Path snapshotDirPath = (arguments.snapshotDirPath != null) ? Paths.get(arguments.snapshotDirPath) : null;
        Path s3LocalDirPath = (arguments.s3LocalDirPath != null) ? Paths.get(arguments.s3LocalDirPath) : null;
        String s3RepoUri = arguments.s3RepoUri;
        String s3Region = arguments.s3Region;
        Path luceneDirPath = Paths.get(arguments.luceneDirPath);
        String targetHost = arguments.targetHost;
        String targetUser = arguments.targetUser;
        String targetPass = arguments.targetPass;
        ClusterVersion sourceVersion = arguments.sourceVersion;
        ClusterVersion targetVersion = arguments.targetVersion;
        MovementType movementType = arguments.movementType;
        Level logLevel = arguments.logLevel;

        Logging.setLevel(logLevel);

        ConnectionDetails targetConnection = new ConnectionDetails(targetHost, targetUser, targetPass);

        // Should probably be passed in as an arguments
        String[] templateWhitelist = {"posts_index_template"};
        String[] componentTemplateWhitelist = {"posts_template"};
        int awarenessAttributeDimensionality = 3; // https://opensearch.org/docs/2.11/api-reference/cluster-api/cluster-awareness/

        // Sanity checks
        if (!((sourceVersion == ClusterVersion.ES_6_8) || (sourceVersion == ClusterVersion.ES_7_10))) {
            throw new IllegalArgumentException("Unsupported source version: " + sourceVersion);
        }

        if (targetVersion != ClusterVersion.OS_2_11) {
            throw new IllegalArgumentException("Unsupported target version: " + sourceVersion);
        }

        SourceRepo repo;
        if (snapshotDirPath != null) {
            repo = new FilesystemRepo(snapshotDirPath);
        } else if (s3RepoUri != null && s3Region != null && s3LocalDirPath != null) {
            repo = new S3Repo(s3LocalDirPath, s3RepoUri, s3Region);
        } else {
            throw new IllegalArgumentException("You must specify either a snapshot directory or an S3 URI");
        }

        // Set the transformer
        Transformer transformer = TransformFunctions.getTransformer(sourceVersion, targetVersion, awarenessAttributeDimensionality);

        try {
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
                logger.warn("Snapshot not found");
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

            if (!"SUCCESS".equals(snapshotMetadata.getState())){
                throw new IllegalArgumentException("Snapshot state is " + snapshotMetadata.getState() + ", must be 'SUCCESS'");
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
                    GlobalMetadataCreator_OS_2_11.create(transformedRoot, targetConnection, new String[0], templateWhitelist);              
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
                    String reindexName = indexMetadata.getName() + "_reindexed";
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
                            String targetIndex = indexMetadata.getName() + "_reindexed";
                            DocumentReindexer.reindex(targetIndex, document, targetConnection);
                        }
                    }
                }

                logger.info("Documents reindexed successfully");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
