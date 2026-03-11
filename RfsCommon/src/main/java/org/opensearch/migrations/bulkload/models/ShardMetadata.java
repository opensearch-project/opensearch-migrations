package org.opensearch.migrations.bulkload.models;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.common.SnapshotMetadataLoader;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

/**
 * Defines the behavior expected of an object that will surface the metadata of an shard stored in a snapshot
 * See: https://github.com/elastic/elasticsearch/blob/7.10/server/src/main/java/org/elasticsearch/index/snapshots/blobstore/BlobStoreIndexShardSnapshot.java#L510
 * See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/index/snapshots/blobstore/BlobStoreIndexShardSnapshot.java#L508
 */
public interface ShardMetadata {

    public String getSnapshotName();

    public String getIndexName();

    public String getIndexId();

    public int getShardId();

    public int getIndexVersion();

    public long getStartTime();

    public long getTime();

    public int getNumberOfFiles();

    public long getTotalSizeBytes();

    public List<ShardFileInfo> getFiles();

    default String getSegmentFileName() {
        var segmentFiles = getFiles().stream()
            .filter(
                file -> file.getPhysicalName().startsWith("segments_")
            ).toList();
        assert segmentFiles.size() == 1 : "Expect exactly one segment file but got " + segmentFiles.size();
        return segmentFiles.get(0).getPhysicalName();
    }

    /**
    * Defines the behavior required to read a snapshot's shard metadata as JSON and convert it into a Data object
    */
    public static interface Factory {
        private JsonNode getJsonNode(String snapshotId, String indexId, int shardId, SmileFactory smileFactory) {
            Path filePath = getRepoDataProvider().getRepo().getShardMetadataFilePath(snapshotId, indexId, shardId);

            try (InputStream fis = new FileInputStream(filePath.toFile())) {
                byte[] bytes = fis.readAllBytes();
                InputStream bis = SnapshotMetadataLoader.processMetadataBytes(bytes, "snapshot");

                ObjectMapper smileMapper = new ObjectMapper(smileFactory);
                return smileMapper.readTree(bis);
            } catch (Exception e) {
                throw new CouldNotParseShardMetadata(
                    "Could not parse shard metadata for Snapshot "
                        + snapshotId
                        + ", Index "
                        + indexId
                        + ", Shard "
                        + shardId,
                    e
                );
            }
        }

        default ShardMetadata fromRepo(String snapshotName, String indexName, int shardId) {
            SmileFactory smileFactory = getSmileFactory();
            String snapshotId = getRepoDataProvider().getSnapshotId(snapshotName);
            String indexId = getRepoDataProvider().getIndexId(indexName);
            JsonNode root = getJsonNode(snapshotId, indexId, shardId, smileFactory);
            return fromJsonNode(root, indexId, indexName, shardId);
        }

        // Version-specific implementation
        public ShardMetadata fromJsonNode(JsonNode root, String indexId, String indexName, int shardId);

        // Version-specific implementation
        public SmileFactory getSmileFactory();

        // Get the underlying SnapshotRepo Provider
        public SnapshotRepo.Provider getRepoDataProvider();
    }

    public static class CouldNotParseShardMetadata extends RfsException {
        public CouldNotParseShardMetadata(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
