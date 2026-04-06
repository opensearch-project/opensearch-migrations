package org.opensearch.migrations.bulkload.solr;

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
public class SolrIndexMetadata implements IndexMetadata {
    @JsonProperty("name")
    private String name;
    @JsonProperty("id")
    private String id;
    @JsonProperty("body")
    private ObjectNode rawJson;

    SolrIndexMetadata(String indexName, ObjectNode rawJson) {
        this.name = indexName;
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
        return rawJson.path("settings").path("index").path("number_of_shards").asInt(1);
    }

    @Override
    public JsonNode getSettings() {
        return rawJson.get("settings");
    }

    @Override
    public IndexMetadata deepCopy() {
        return new SolrIndexMetadata(name, rawJson.deepCopy());
    }
}
