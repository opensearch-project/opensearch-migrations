package org.opensearch.migrations.bulkload.version_universal;

import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true) // For Jackson
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "type")
@Getter
public class RemoteIndexMetadata implements IndexMetadata {
    @JsonProperty("name")
    private String name;
    @JsonProperty("id")
    private String id;
    @JsonProperty("body")
    private ObjectNode rawJson;

    RemoteIndexMetadata(String indexName, ObjectNode rawJson) {
        this.name = indexName;
        // ID is the same as name in remote metadata
        this.id = indexName;
        this.rawJson = rawJson;
    }

    @Override
    public JsonNode getAliases() {
        return rawJson.get("aliases");
    }

    @Override
    public JsonNode getMappings() {
        return rawJson.get("mappings");
    }

    @Override
    public int getNumberOfShards() {
        return getSettings().get("index").get("number_of_shards").asInt();
    }

    @Override
    public JsonNode getSettings() {
        return rawJson.get("settings");
    }

    @Override
    public IndexMetadata deepCopy() {
        return new RemoteIndexMetadata(name, rawJson.deepCopy());
    }
    
}
