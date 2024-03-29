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


public class GlobalMetadata {
    /**
    * Defines the behavior required to read a snapshot's global metadata as JSON and convert it into a Data object
    */
    public static interface Factory {
        private JsonNode getJsonNode(SourceRepo repo, SnapshotRepo.Provider repoDataProvider, String snapshotName, SmileFactory smileFactory) throws Exception {
            String snapshotId = repoDataProvider.getSnapshotId(snapshotName);

            if (snapshotId == null) {
                throw new Exception("Snapshot not found");
            }

            Path filePath = repo.getGlobalMetadataFilePath(snapshotId);

            try (InputStream fis = new FileInputStream(filePath.toFile())) {
                // Don't fully understand what the value of this code is, but it progresses the stream so we need to do it
                // See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/repositories/blobstore/ChecksumBlobStoreFormat.java#L100
                byte[] bytes = fis.readAllBytes();
                ByteArrayIndexInput indexInput = new ByteArrayIndexInput("global-metadata", bytes);
                CodecUtil.checksumEntireFile(indexInput);
                CodecUtil.checkHeader(indexInput, "metadata", 1, 1);
                int filePointer = (int) indexInput.getFilePointer();
                InputStream bis = new ByteArrayInputStream(bytes, filePointer, bytes.length - filePointer);

                ObjectMapper smileMapper = new ObjectMapper(smileFactory);
                return smileMapper.readTree(bis);
            }
        }

        default GlobalMetadata.Data fromRepo(SourceRepo repo, SnapshotRepo.Provider repoDataProvider, String snapshotName) throws Exception {
            SmileFactory smileFactory = getSmileFactory();
            JsonNode root = getJsonNode(repo, repoDataProvider, snapshotName, smileFactory);
            return fromJsonNode(root);
        }

        // Version-specific implementation
        public GlobalMetadata.Data fromJsonNode(JsonNode root) throws Exception;

        // Version-specific implementation
        public SmileFactory getSmileFactory();
    }

    /**
    * Defines the behavior expected of an object that will surface the global metadata of a snapshot
    * See: https://github.com/elastic/elasticsearch/blob/v7.10.2/server/src/main/java/org/elasticsearch/cluster/metadata/Metadata.java#L1622
    * See: https://github.com/elastic/elasticsearch/blob/v6.8.23/server/src/main/java/org/elasticsearch/cluster/metadata/MetaData.java#L1214
    */
    public static interface Data {
        public ObjectNode toObjectNode() throws Exception;
    }
    
}
