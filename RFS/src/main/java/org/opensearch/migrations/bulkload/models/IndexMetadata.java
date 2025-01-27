package org.opensearch.migrations.bulkload.models;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.ByteArrayIndexInput;
import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import org.apache.lucene.codecs.CodecUtil;

// All subclasses need to be annotated with this
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "type")
public abstract class IndexMetadata implements Index {
    /*
    * Defines the behavior expected of an object that will surface the metadata of an index stored in a snapshot
    * See: https://github.com/elastic/elasticsearch/blob/v7.10.2/server/src/main/java/org/elasticsearch/cluster/metadata/IndexMetadata.java#L1475
    * See: https://github.com/elastic/elasticsearch/blob/v6.8.23/server/src/main/java/org/elasticsearch/cluster/metadata/IndexMetaData.java#L1284
    */
    public abstract JsonNode getAliases();

    public abstract String getId();

    public abstract JsonNode getMappings();

    public abstract String getName();

    public abstract int getNumberOfShards();

    public abstract JsonNode getSettings();

    public abstract IndexMetadata deepCopy();

    /**
    * Defines the behavior required to read a snapshot's index metadata as JSON and convert it into a Data object
    */
    public static interface Factory {
        private JsonNode getJsonNode(String indexId, String indexFileId, SmileFactory smileFactory) {
            Path filePath = getRepoDataProvider().getRepo().getIndexMetadataFilePath(indexId, indexFileId);

            try (InputStream fis = new FileInputStream(filePath.toFile())) {
                // Don't fully understand what the value of this code is, but it progresses the stream so we need to do
                // it
                // See:
                // https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/repositories/blobstore/ChecksumBlobStoreFormat.java#L100
                byte[] bytes = fis.readAllBytes();
                ByteArrayIndexInput indexInput = new ByteArrayIndexInput("index-metadata", bytes);
                CodecUtil.checksumEntireFile(indexInput);
                CodecUtil.checkHeader(indexInput, "index-metadata", 1, 1);
                int filePointer = (int) indexInput.getFilePointer();
                InputStream bis = new ByteArrayInputStream(bytes, filePointer, bytes.length - filePointer);

                ObjectMapper smileMapper = new ObjectMapper(smileFactory);
                return smileMapper.readTree(bis);
            } catch (Exception e) {
                throw new RfsException("Could not load index metadata file: " + filePath.toString(), e);
            }
        }

        default IndexMetadata fromRepo(String snapshotName, String indexName) {
            SmileFactory smileFactory = getSmileFactory();
            String indexId = getRepoDataProvider().getIndexId(indexName);
            String indexFileId = getIndexFileId(snapshotName, indexName);
            JsonNode root = getJsonNode(indexId, indexFileId, smileFactory);
            return fromJsonNode(root, indexId, indexName);
        }

        // Version-specific implementation
        IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName);

        // Version-specific implementation
        SmileFactory getSmileFactory();

        // Version-specific implementation
        String getIndexFileId(String snapshotName, String indexName);

        // Get the underlying SnapshotRepo Provider
        SnapshotRepo.Provider getRepoDataProvider();
    }
}
