package com.rfs.common;

import org.apache.lucene.codecs.CodecUtil;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

public class IndexMetadata {

    /**
    * Defines the behavior required to read a snapshot's index metadata as JSON and convert it into a Data object
    */
    public static interface Factory {
        private JsonNode getJsonNode(SourceRepo repo, SnapshotRepo.Provider repoDataProvider, String indexId, String indexFileId, SmileFactory smileFactory) throws Exception {
            Path filePath = repo.getIndexMetadataFilePath(indexId, indexFileId);
    
            try (InputStream fis = new FileInputStream(filePath.toFile())) {
                // Don't fully understand what the value of this code is, but it progresses the stream so we need to do it
                // See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/repositories/blobstore/ChecksumBlobStoreFormat.java#L100
                byte[] bytes = fis.readAllBytes();
                ByteArrayIndexInput indexInput = new ByteArrayIndexInput("index-metadata", bytes);
                CodecUtil.checksumEntireFile(indexInput);
                CodecUtil.checkHeader(indexInput, "index-metadata", 1, 1);
                int filePointer = (int) indexInput.getFilePointer();
                InputStream bis = new ByteArrayInputStream(bytes, filePointer, bytes.length - filePointer);

                ObjectMapper smileMapper = new ObjectMapper(smileFactory);
                return smileMapper.readTree(bis);
            }
        }

        default IndexMetadata.Data fromRepo(SourceRepo repo, SnapshotRepo.Provider repoDataProvider, String snapshotName, String indexName) throws Exception {
            SmileFactory smileFactory = getSmileFactory();
            String indexId = repoDataProvider.getIndexId(indexName);
            String indexFileId = getIndexFileId(repoDataProvider, snapshotName, indexName);
            JsonNode root = getJsonNode(repo, repoDataProvider, indexId, indexFileId, smileFactory);            
            return fromJsonNode(root, indexId, indexName);
        }
        public IndexMetadata.Data fromJsonNode(JsonNode root, String indexId, String indexName) throws Exception;
        public SmileFactory getSmileFactory();
        public String getIndexFileId(SnapshotRepo.Provider repoDataProvider, String snapshotName, String indexName);
    }

    /**
    * Defines the behavior expected of an object that will surface the metadata of an index stored in a snapshot
    * See: https://github.com/elastic/elasticsearch/blob/v7.10.2/server/src/main/java/org/elasticsearch/cluster/metadata/IndexMetadata.java#L1475
    * See: https://github.com/elastic/elasticsearch/blob/v6.8.23/server/src/main/java/org/elasticsearch/cluster/metadata/IndexMetaData.java#L1284
    */
    public static interface Data {
        public ObjectNode getAliases();
        public String getId();
        public ObjectNode getMappings();
        public String getName();
        public int getNumberOfShards();
        public ObjectNode getSettings();
        public ObjectNode toObjectNode();
    }
    
}
