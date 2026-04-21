package org.opensearch.migrations.bulkload.models;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.InvalidSnapshotFormatException;
import org.opensearch.migrations.bulkload.common.SnapshotMetadataLoader;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

// All subclasses need to be annotated with this
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "type")
public interface IndexMetadata extends Index {
    /*
    * Defines the behavior expected of an object that will surface the metadata of an index stored in a snapshot
    * See: https://github.com/elastic/elasticsearch/blob/v7.10.2/server/src/main/java/org/elasticsearch/cluster/metadata/IndexMetadata.java#L1475
    * See: https://github.com/elastic/elasticsearch/blob/v6.8.23/server/src/main/java/org/elasticsearch/cluster/metadata/IndexMetaData.java#L1284
    */
    JsonNode getAliases();

    String getId();

    JsonNode getMappings();

    int getNumberOfShards();

    JsonNode getSettings();

    IndexMetadata deepCopy();

    /**
     * Returns whether _source is enabled for this index. Inspects the mappings JSON
     * for `_source.enabled` field. Returns true if _source is enabled or not explicitly set
     * (ES default is enabled).
     *
     * Handles multiple mapping formats:
     * - ES 1.x-5.x: {"type_name": {"_source": {"enabled": false}, "properties": {...}}}
     * - ES 6.x: {"_doc": {"_source": {"enabled": false}, "properties": {...}}}
     * - ES 7.x+: {"_source": {"enabled": false}, "properties": {...}}
     */
    default boolean isSourceEnabled() {
        JsonNode mappings = getMappings();
        if (mappings == null || mappings.isMissingNode()) {
            return true;
        }
        // Try direct _source (ES 7+ or already unwrapped)
        JsonNode sourceNode = mappings.path("_source");
        if (!sourceNode.isMissingNode() && sourceNode.has("enabled")) {
            return sourceNode.get("enabled").asBoolean(true);
        }
        // Try typed mappings (ES 1.x-6.x): {"type_name": {"_source": ...}}
        if (mappings.isObject()) {
            var fields = mappings.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode typeMapping = entry.getValue();
                if (typeMapping.isObject()) {
                    JsonNode typedSource = typeMapping.path("_source");
                    if (!typedSource.isMissingNode() && typedSource.has("enabled")) {
                        return typedSource.get("enabled").asBoolean(true);
                    }
                }
            }
        }
        return true; // default: source is enabled
    }

    default void validateRawJson(ObjectNode rawJson) {
        if (rawJson == null) {
            throw new InvalidSnapshotFormatException();
        }
    }

    /**
    * Defines the behavior required to read a snapshot's index metadata as JSON and convert it into a Data object
    */
    interface Factory {
        private JsonNode getJsonNode(String indexId, String indexFileId, SmileFactory smileFactory) {
            Path filePath = getRepoDataProvider().getRepo().getIndexMetadataFilePath(indexId, indexFileId);

            try (InputStream fis = new FileInputStream(filePath.toFile())) {
                byte[] bytes = fis.readAllBytes();
                InputStream bis = SnapshotMetadataLoader.processMetadataBytes(bytes, "index-metadata");

                ObjectMapper smileMapper = new ObjectMapper(smileFactory);
                return smileMapper.readTree(bis);
            } catch (Exception e) {
                throw new InvalidSnapshotFormatException("File: " + filePath.toString(), e);
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
