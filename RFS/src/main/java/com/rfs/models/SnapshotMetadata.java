package com.rfs.models;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.nio.file.Path;

import org.apache.lucene.codecs.CodecUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.rfs.common.ByteArrayIndexInput;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SourceRepo;

/**
 * Defines the behavior expected of an object that will surface the metadata of a snapshot
 * See: https://github.com/elastic/elasticsearch/blob/7.10/server/src/main/java/org/elasticsearch/snapshots/SnapshotInfo.java#L615
 * See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/snapshots/SnapshotInfo.java#L583
 */
public interface SnapshotMetadata {
    // TODO: Turn into an ENUM when we know the other possible values
    public static final String SNAPSHOT_SUCCEEDED = "SUCCESS";

    public String getName();    
    public String getUuid();    
    public int getVersionId();    
    public List<String> getIndices();    
    public String getState();    
    public String getReason();    
    public boolean isIncludeGlobalState();    
    public long getStartTime();    
    public long getEndTime();    
    public int getTotalShards();    
    public int getSuccessfulShards();
    public List<?> getFailures();

    /**
    * Defines the behavior required to read a snapshot metadata as JSON and convert it into a Data object
    */
    public static interface Factory {
        private JsonNode getJsonNode(SourceRepo repo, SnapshotRepo.Provider repoDataProvider, String snapshotName, SmileFactory smileFactory) throws Exception {
            String snapshotId = repoDataProvider.getSnapshotId(snapshotName);

            if (snapshotId == null) {
                throw new Exception("Snapshot not found");
            }

            Path filePath = repo.getSnapshotMetadataFilePath(snapshotId);

            try (InputStream fis = new FileInputStream(filePath.toFile())) {
                // Don't fully understand what the value of this code is, but it progresses the stream so we need to do it
                // See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/repositories/blobstore/ChecksumBlobStoreFormat.java#L100
                byte[] bytes = fis.readAllBytes();
                ByteArrayIndexInput indexInput = new ByteArrayIndexInput("snapshot-metadata", bytes);
                CodecUtil.checksumEntireFile(indexInput);
                CodecUtil.checkHeader(indexInput, "snapshot", 1, 1);
                int filePointer = (int) indexInput.getFilePointer();
                InputStream bis = new ByteArrayInputStream(bytes, filePointer, bytes.length - filePointer);

                ObjectMapper smileMapper = new ObjectMapper(smileFactory);
                return smileMapper.readTree(bis);
            }
        }

        default SnapshotMetadata fromRepo(SourceRepo repo, SnapshotRepo.Provider repoDataProvider, String snapshotName) throws Exception {
            SmileFactory smileFactory = getSmileFactory();
            JsonNode root = getJsonNode(repo, repoDataProvider, snapshotName, smileFactory);
            return fromJsonNode(root);
        }
        public SnapshotMetadata fromJsonNode(JsonNode root) throws Exception;
        public SmileFactory getSmileFactory();
    }

    default boolean isSuccessful() {
        return SNAPSHOT_SUCCEEDED.equals(getState());
    }
    
}
