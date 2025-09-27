package org.opensearch.migrations.bulkload.version_os_2_11;

import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true) // For Jackson
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "type")
public class IndexMetadataData_OS_2_11 implements IndexMetadata {
    @JsonProperty("body")
    private ObjectNode root;
    @JsonProperty("id")
    private String indexId;
    @JsonProperty("name")
    private String indexName;

    public IndexMetadataData_OS_2_11(ObjectNode root, String indexId, String indexName) {
        validateRawJson(root);
        this.root = root;
        this.indexId = indexId;
        this.indexName = indexName;
    }

    @Override
    public ObjectNode getAliases() {
        return (ObjectNode) root.get("aliases");
    }

    @Override
    public String getId() {
        return indexId;
    }

    @Override
    public ObjectNode getMappings() {
        return (ObjectNode) root.get("mappings");
    }

    @Override
    public String getName() {
        return indexName;
    }

    @Override
    public int getNumberOfShards() {
        return this.getSettings().get("number_of_shards").asInt();
    }

    @Override
    public ObjectNode getSettings() {
        return (ObjectNode) root.get("settings");
    }

    @Override
    public ObjectNode getRawJson() {
        return root;
    }

    @Override
    public IndexMetadata deepCopy() {
        return new IndexMetadataData_OS_2_11(root.deepCopy(), indexId, indexName);
    }
}
