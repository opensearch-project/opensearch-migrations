package org.opensearch.migrations.bulkload.version_es_6_8;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

@Getter
public class ShardMetadataData_ES_6_8 implements ShardMetadata {
    private static final ObjectMapper objectMapper = ObjectMapperFactory.createDefaultMapper();

    private final String snapshotName;
    private final String indexName;
    private final String indexId;
    private final int shardId;
    private final int indexVersion;
    private final long startTime;
    private final long time;
    private final int numberOfFiles;
    private final long totalSizeBytes;
    private final List<ShardFileInfo> files;

    public ShardMetadataData_ES_6_8(
        String snapshotName,
        String indexName,
        String indexId,
        int shardId,
        Integer indexVersion,
        Long startTime,
        Long time,
        Integer numberOfFiles,
        Long totalSize,
        List<FileInfoRaw> files
    ) {
        this.snapshotName = snapshotName;
        this.indexName = indexName;
        this.indexId = indexId;
        this.shardId = shardId;
        this.indexVersion = indexVersion != null ? indexVersion : -1;
        this.startTime = startTime != null ? startTime : 0L;
        this.time = time != null ? time : 0L;
        this.numberOfFiles = numberOfFiles != null ? numberOfFiles : 0;
        this.totalSizeBytes = totalSize != null ? totalSize : 0L;

        // Convert the raw file metadata to the FileMetadata class
        List<FileInfo> convertedFiles = new java.util.ArrayList<>();
        if (files != null) {
            for (FileInfoRaw fileMetadataRaw : files) {
                convertedFiles.add(FileInfo.fromFileMetadataRaw(fileMetadataRaw));
            }
        }
        this.files = Collections.unmodifiableList(convertedFiles);
    }

    @Override
    public String toString() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            return "Error converting to string: " + e.getMessage();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataRaw {
        public final String name;
        public final Integer indexVersion;
        public final Long startTime;
        public final Long time;
        public final Integer numberOfFiles;
        public final Long totalSize;
        public final List<FileInfoRaw> files;

        @JsonCreator
        public DataRaw(
            @JsonProperty("name") String name,
            @JsonProperty("index_version") Integer indexVersion,
            @JsonProperty("start_time") Long startTime,
            @JsonProperty("time") Long time,
            @JsonProperty("number_of_files") Integer numberOfFiles,
            @JsonProperty("total_size") Long totalSize,
            @JsonProperty("files") List<FileInfoRaw> files
        ) {
            this.name = name;
            this.indexVersion = indexVersion;
            this.startTime = startTime;
            this.time = time;
            this.numberOfFiles = numberOfFiles;
            this.totalSize = totalSize;
            this.files = files;
        }
    }

    @Getter
    public static class FileInfo implements ShardFileInfo {
        private final String name;
        private final String physicalName;
        private final long length;
        private final String checksum;
        private final long partSize;
        private final long numberOfParts;
        private final String writtenBy;
        private final BytesRef metaHash;

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
            BytesRef metaHash
        ) {
            this.name = name;
            this.physicalName = physicalName;
            this.length = length;
            this.checksum = checksum;
            this.partSize = partSize;
            this.writtenBy = writtenBy;
            this.metaHash = metaHash;

            // Calculate the number of parts the file is chopped into; taken from Elasticsearch code.  When Elasticsearch makes
            // a snapshot and finds Lucene files over a specified size, it will split those files into multiple parts based on the
            // maximum part size.
            // See:
            // https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/index/snapshots/blobstore/BlobStoreIndexShardSnapshot.java#L68
            long partBytes = Long.MAX_VALUE;
            if (partSize != Long.MAX_VALUE) {
                partBytes = partSize;
            }

            long totalLength = length;
            long numberOfPartsTemp = totalLength / partBytes;
            if (totalLength % partBytes > 0) {
                numberOfPartsTemp++;
            }
            if (numberOfPartsTemp == 0) {
                numberOfPartsTemp++;
            }
            this.numberOfParts = numberOfPartsTemp;
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
            BytesRef metaHash
        ) {
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
        public FileInfoRaw deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {

            JsonNode rootNode = jp.getCodec().readTree(jp);

            String name = rootNode.get("name").asText();
            String physicalName = rootNode.get("physical_name").asText();
            long length = rootNode.get("length").asLong();
            String checksum = rootNode.get("checksum").asText();
            long partSize = rootNode.has("part_size") ? rootNode.get("part_size").asLong() : Long.MAX_VALUE;
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
