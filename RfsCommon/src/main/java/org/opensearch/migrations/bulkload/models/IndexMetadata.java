package org.opensearch.migrations.bulkload.models;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.InvalidSnapshotFormatException;
import org.opensearch.migrations.bulkload.common.SnapshotMetadataLoader;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
     * Returns the {@code _source} configuration node from this index's mappings, or null if absent.
     * Version-specific subclasses should override this if their mappings format wraps
     * {@code _source} under a type name (ES 1.x–6.x).
     *
     * <p>The default implementation handles both typed and typeless layouts as a fallback.
     */
    @JsonIgnore
    default JsonNode getSourceNode() {
        JsonNode mappings = unwrapMappingsArrayIfNeeded(getMappings());
        if (mappings == null) {
            return null;
        }
        // Direct _source (ES 7+, OS, or already unwrapped)
        JsonNode sourceNode = mappings.path("_source");
        if (!sourceNode.isMissingNode()) {
            return sourceNode;
        }
        // Typed mappings (ES 1.x-6.x): {"type_name": {"_source": ...}}
        return findTypedSourceNode(mappings);
    }

    /**
     * Unwraps the ES 5.x/6.x snapshot format where typed mappings are wrapped in an array:
     * {@code mappings: [{"doc": {"_source": {...}, "properties": {...}}}]}.
     * Returns the first element if array-wrapped, the input if already an object, or null otherwise.
     */
    private static JsonNode unwrapMappingsArrayIfNeeded(JsonNode mappings) {
        if (mappings == null || mappings.isMissingNode()) {
            return null;
        }
        if (!mappings.isArray()) {
            return mappings;
        }
        if (mappings.isEmpty()) {
            return null;
        }
        JsonNode first = mappings.get(0);
        return (first != null && first.isObject()) ? first : null;
    }

    /**
     * Searches an object node's direct children for a {@code _source} sub-node
     * (typed mappings layout from ES 1.x-6.x). Returns null if not found.
     */
    private static JsonNode findTypedSourceNode(JsonNode mappings) {
        if (!mappings.isObject()) {
            return null;
        }
        var fields = mappings.fields();
        while (fields.hasNext()) {
            JsonNode typeMapping = fields.next().getValue();
            JsonNode typedSource = (typeMapping != null && typeMapping.isObject())
                ? typeMapping.path("_source")
                : null;
            if (typedSource != null && !typedSource.isMissingNode()) {
                return typedSource;
            }
        }
        return null;
    }

    /**
     * Returns whether _source is enabled for this index.
     * Returns true if _source is enabled or not explicitly set (ES default is enabled).
     */
    @JsonIgnore
    default boolean isSourceEnabled() {
        JsonNode sourceNode = getSourceNode();
        if (sourceNode == null || !sourceNode.has("enabled")) {
            return true;
        }
        return sourceNode.get("enabled").asBoolean(true);
    }

    /**
     * Returns whether _source has includes or excludes filtering (partial source).
     * When partial, some fields are missing from the stored _source and must be
     * reconstructed from doc_values or stored fields.
     */
    @JsonIgnore
    default boolean isSourcePartial() {
        JsonNode sourceNode = getSourceNode();
        if (sourceNode == null) {
            return false;
        }
        return hasNonEmptyArray(sourceNode, "includes") || hasNonEmptyArray(sourceNode, "excludes");
    }

    /**
     * Returns true if the index needs source reconstruction during migration.
     * This is the case when _source is disabled or when _source is partial
     * (has includes/excludes filtering).
     */
    @JsonIgnore
    default boolean needsSourceReconstruction() {
        return !isSourceEnabled() || isSourcePartial();
    }

    private static boolean hasNonEmptyArray(JsonNode node, String fieldName) {
        JsonNode arr = node.get(fieldName);
        return arr != null && arr.isArray() && arr.size() > 0;
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
