package org.opensearch.migrations.bulkload.version_es_1_7;

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
import lombok.extern.slf4j.Slf4j;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

@Getter
@Slf4j
public class ShardMetadataData_ES_1_7 implements ShardMetadata {
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

    public ShardMetadataData_ES_1_7(
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
        this.indexVersion = requireNonNull("indexVersion", indexVersion);
        this.startTime = requireNonNull("startTime", startTime);
        this.time = requireNonNull("time", time);
        this.numberOfFiles = requireNonNull("numberOfFiles", numberOfFiles);
        this.totalSizeBytes = requireNonNull("totalSize", totalSize);

        // Convert raw file metadata to strongly typed FileInfo
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

            // Calculate number of parts, matching SnapshotReader_ES_2_4 logic
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

    private static <T> T requireNonNull(String name, T value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required in ES 1.7 shard metadata JSON but was missing!");
        }
        return value;
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
            String checksum = rootNode.has("checksum") ? rootNode.get("checksum").asText() : null;
            long partSize = rootNode.has("part_size") ? rootNode.get("part_size").asLong() : Long.MAX_VALUE;
            String writtenBy = rootNode.has("written_by") ? rootNode.get("written_by").asText() : null;

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
