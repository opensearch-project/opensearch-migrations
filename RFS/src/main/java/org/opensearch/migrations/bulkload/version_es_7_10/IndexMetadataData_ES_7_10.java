package org.opensearch.migrations.bulkload.version_es_7_10;

import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.transformers.TransformFunctions;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true) // For Jackson
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "type")
public class IndexMetadataData_ES_7_10 implements IndexMetadata {
    @Getter
    @JsonProperty("body")
    private final ObjectNode rawJson;
    private ObjectNode mappings;
    private ObjectNode settings;
    @Getter
    @JsonProperty("id")
    private final String id;
    @Getter
    @JsonProperty("name")
    private final String name;

    public IndexMetadataData_ES_7_10(ObjectNode root, String indexId, String indexName) {
        validateRawJson(root);
        this.rawJson = root;
        this.mappings = null;
        this.settings = null;
        this.id = indexId;
        this.name = indexName;
    }

    @Override
    public ObjectNode getAliases() {
        return (ObjectNode) rawJson.get("aliases");
    }

    @Override
    public ObjectNode getMappings() {
        if (mappings != null) {
            return mappings;
        }
        ObjectNode mappingsNode = (ObjectNode) rawJson.get("mappings");
        mappings = mappingsNode;
        return mappings;
    }

    @Override
    public int getNumberOfShards() {
        return this.getSettings().get("index").get("number_of_shards").asInt();
    }

    @Override
    public ObjectNode getSettings() {
        if (settings != null) {
            return settings;
        }
        ObjectNode treeSettings = TransformFunctions.convertFlatSettingsToTree((ObjectNode) rawJson.get("settings"));
        settings = treeSettings;
        return settings;
    }

    @Override
    public IndexMetadata deepCopy() {
        return new IndexMetadataData_ES_7_10(rawJson.deepCopy(), id, name);
    }
}
