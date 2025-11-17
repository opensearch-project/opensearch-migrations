package org.opensearch.migrations.bulkload.models;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.common.SnapshotMetadataLoader;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

public interface GlobalMetadata {
    /**
    * Defines the behavior expected of an object that will surface the global metadata of a snapshot
    * See: https://github.com/elastic/elasticsearch/blob/v7.10.2/server/src/main/java/org/elasticsearch/cluster/metadata/Metadata.java#L1622
    * See: https://github.com/elastic/elasticsearch/blob/v6.8.23/server/src/main/java/org/elasticsearch/cluster/metadata/MetaData.java#L1214
    */
    ObjectNode toObjectNode();

    JsonPointer getTemplatesPath();

    JsonPointer getIndexTemplatesPath();

    JsonPointer getComponentTemplatesPath();

    default ObjectNode getTemplates() {
        return getObjectNodeWithPath(getTemplatesPath());
    }

    default ObjectNode getIndexTemplates() {
        return getObjectNodeWithPath(getIndexTemplatesPath());
    }

    default ObjectNode getComponentTemplates() {
        return getObjectNodeWithPath(getComponentTemplatesPath());
    }

    default ObjectNode getObjectNodeWithPath(JsonPointer path) {
        return toObjectNode().withObject(path, JsonNode.OverwriteMode.NULLS, false);
    }

    /**
    * Defines the behavior required to read a snapshot's global metadata as JSON and convert it into a Data object
    */
    interface Factory {
        private JsonNode getJsonNode(
            SnapshotRepo.Provider repoDataProvider,
            String snapshotName,
            SmileFactory smileFactory
        ) {
            String snapshotId = repoDataProvider.getSnapshotId(snapshotName);

            if (snapshotId == null) {
                throw new CantFindSnapshotInRepo(snapshotName);
            }

            Path filePath = repoDataProvider.getRepo().getGlobalMetadataFilePath(snapshotId);

            try (InputStream fis = new FileInputStream(filePath.toFile())) {
                byte[] bytes = fis.readAllBytes();
                InputStream bis = SnapshotMetadataLoader.processMetadataBytes(bytes, "metadata");

                ObjectMapper smileMapper = new ObjectMapper(smileFactory);
                return smileMapper.readTree(bis);
            } catch (Exception e) {
                throw new CantReadGlobalMetadataFromSnapshot(snapshotName, e);
            }
        }

        default GlobalMetadata fromRepo(String snapshotName) {
            SnapshotRepo.Provider repoDataProvider = getRepoDataProvider();
            SmileFactory smileFactory = getSmileFactory();
            JsonNode root = getJsonNode(repoDataProvider, snapshotName, smileFactory);
            return fromJsonNode(root);
        }

        // Version-specific implementation
        GlobalMetadata fromJsonNode(JsonNode root);

        // Version-specific implementation
        SmileFactory getSmileFactory();

        // Get the underlying SnapshotRepo Provider
        SnapshotRepo.Provider getRepoDataProvider();
    }

    class CantFindSnapshotInRepo extends RfsException {
        public CantFindSnapshotInRepo(String snapshotName) {
            super("Can't find snapshot in repo: " + snapshotName);
        }
    }

    class CantReadGlobalMetadataFromSnapshot extends RfsException {
        public CantReadGlobalMetadataFromSnapshot(String snapshotName, Throwable cause) {
            super("Can't read the global metadata from snapshot: " + snapshotName, cause);
        }
    }

}
