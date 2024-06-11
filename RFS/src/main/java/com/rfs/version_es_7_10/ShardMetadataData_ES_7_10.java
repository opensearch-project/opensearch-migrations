package com.rfs.version_es_7_10;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.util.BytesRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfs.common.ShardMetadata;

public class ShardMetadataData_ES_7_10 implements ShardMetadata.Data {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private String snapshotName;
    private String indexName;
    private String indexId;
    private int shardId;
    private int indexVersion;
    private long startTime;
    private long time;
    private int numberOfFiles;
    private long totalSize;
    private List<FileInfo> files;

    public ShardMetadataData_ES_7_10(
            String snapshotName,
            String indexName,
            String indexId,
            int shardId,
            int indexVersion,
            long startTime,
            long time,
            int numberOfFiles,
            long totalSize,
            List<FileInfoRaw> files) {
        this.snapshotName = snapshotName;
        this.indexName = indexName;
        this.indexId = indexId;
        this.shardId = shardId;
        this.indexVersion = indexVersion;
        this.startTime = startTime;
        this.time = time;
        this.numberOfFiles = numberOfFiles;
        this.totalSize = totalSize;

        // Convert the raw file metadata to the FileMetadata class
        List<FileInfo> convertedFiles = new java.util.ArrayList<>();
        for (FileInfoRaw fileMetadataRaw : files) {
            convertedFiles.add(FileInfo.fromFileMetadataRaw(fileMetadataRaw));
        }
        this.files = convertedFiles;
    }

    @Override
    public String getSnapshotName() {
        return snapshotName;
    }

    @Override
    public String getIndexName() {
        return indexName;
    }

    @Override
    public String getIndexId() {
        return indexId;
    }

    @Override
    public int getShardId() {
        return shardId;
    }

    @Override
    public int getIndexVersion() {
        return indexVersion;
    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    @Override
    public long getTime() {
        return time;
    }

    @Override
    public int getNumberOfFiles() {
        return numberOfFiles;
    }

    @Override
    public long getTotalSizeBytes() {
        return totalSize;
    }

    @Override
    public List<ShardMetadata.FileInfo> getFiles() {
        List<ShardMetadata.FileInfo> convertedFiles = new ArrayList<>(files);
        return convertedFiles;
    }

    @Override
    public String toString() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            return "Error converting to string: " + e.getMessage();
        }
    }

    public static class DataRaw {
        public final String name;
        public final int indexVersion;
        public final long startTime;
        public final long time;
        public final int numberOfFiles;
        public final long totalSize;
        public final List<FileInfoRaw> files;

        @JsonCreator
        public DataRaw(
                @JsonProperty("name") String name,
                @JsonProperty("index_version") int indexVersion,
                @JsonProperty("start_time") long startTime,
                @JsonProperty("time") long time,
                @JsonProperty("number_of_files") int numberOfFiles,
                @JsonProperty("total_size") long totalSize,
                @JsonProperty("files") List<FileInfoRaw> files) {
            this.name = name;
            this.indexVersion = indexVersion;
            this.startTime = startTime;
            this.time = time;
            this.numberOfFiles = numberOfFiles;
            this.totalSize = totalSize;
            this.files = files;
        }
    }

    public static class FileInfo implements ShardMetadata.FileInfo {
        private String name;
        private String physicalName;
        private long length;
        private String checksum;
        private long partSize;
        private long numberOfParts;
        private String writtenBy;
        private BytesRef metaHash;

        public static FileInfo fromFileMetadataRaw(FileInfoRaw fileMetadataRaw) {
            return new FileInfo(
                    fileMetadataRaw.name,
                    fileMetadataRaw.physicalName,
                    fileMetadataRaw.length,
                    fileMetadataRaw.checksum,
                    fileMetadataRaw.partSize,
                    fileMetadataRaw.writtenBy,
                    fileMetadataRaw.metaHash
            );
        }

        public FileInfo(
                String name,
                String physicalName,
                long length,
                String checksum,
                long partSize,
                String writtenBy,
                BytesRef metaHash) {
            this.name = name;
            this.physicalName = physicalName;
            this.length = length;
            this.checksum = checksum;
            this.partSize = partSize;
            this.writtenBy = writtenBy;
            this.metaHash = metaHash;

            // Calculate the number of parts the file is chopped into; taken from Elasticsearch code
            // See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/index/snapshots/blobstore/BlobStoreIndexShardSnapshot.java#L68
            long partBytes = Long.MAX_VALUE;
            if (partSize != Long.MAX_VALUE) {
                partBytes = partSize;
            }

            long totalLength = length;
            long numberOfParts = totalLength / partBytes;
            if (totalLength % partBytes > 0) {
                numberOfParts++;
            }
            if (numberOfParts == 0) {
                numberOfParts++;
            }
            this.numberOfParts = numberOfParts;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getPhysicalName() {
            return physicalName;
        }

        @Override
        public long getLength() {
            return length;
        }

        @Override
        public String getChecksum() {
            return checksum;
        }

        @Override
        public long getPartSize() {
            return partSize;
        }

        @Override
        public String getWrittenBy() {
            return writtenBy;
        }

        @Override
        public BytesRef getMetaHash() {
            return metaHash;
        }

        @Override
        public long getNumberOfParts() {
            return numberOfParts;
        }

        // The Snapshot file may be split into multiple blobs; use this to find the correct file name
        @Override
        public String partName(long part) {
            if (numberOfParts > 1) {
                return name + ".part" + part;
            } else {
                return name;
            }
        }

        @Override
        public String toString() {
            try {
                return objectMapper.writeValueAsString(this);
            } catch (Exception e) {
                return "Error converting to string: " + e.getMessage();
            }
        }
    }

    public static class FileInfoRaw {
        public final String name;
        public final String physicalName;
        public final long length;
        public final String checksum;
        public final long partSize;
        public final String writtenBy;
        public final BytesRef metaHash;

        public FileInfoRaw(
                String name,
                String physicalName,
                long length,
                String checksum,
                long partSize,
                String writtenBy,
                BytesRef metaHash) {
            this.name = name;
            this.physicalName = physicalName;
            this.length = length;
            this.checksum = checksum;
            this.partSize = partSize;
            this.writtenBy = writtenBy;
            this.metaHash = metaHash;
        }
    }

    public static class FileInfoRawDeserializer extends JsonDeserializer<FileInfoRaw> {
        @Override
        public FileInfoRaw deserialize(JsonParser jp, DeserializationContext ctxt)
                throws IOException, JsonProcessingException {
                    
            JsonNode rootNode = jp.getCodec().readTree(jp);
            
            String name = rootNode.get("name").asText();
            String physicalName = rootNode.get("physical_name").asText();
            long length = rootNode.get("length").asLong();
            String checksum = rootNode.get("checksum").asText();
            long partSize = rootNode.get("part_size").asLong();
            String writtenBy = rootNode.get("written_by").asText();
            
            BytesRef metaHash = null;
            if (rootNode.has("meta_hash")) {
                metaHash = new BytesRef();
                metaHash.bytes = rootNode.get("meta_hash").binaryValue();
                metaHash.offset = 0;
                metaHash.length = metaHash.bytes.length;
            }
            
            return new FileInfoRaw(name, physicalName, length, checksum, partSize, writtenBy, metaHash);
        }
    }
}
