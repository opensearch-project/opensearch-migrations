package org.opensearch.migrations.bulkload.version_es_2_4;

import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static org.opensearch.migrations.bulkload.version_es_2_4.ElasticsearchConstants_ES_2_4.FIELD_MAPPINGS;
import static org.opensearch.migrations.bulkload.version_es_2_4.ElasticsearchConstants_ES_2_4.FIELD_SETTINGS;

@NoArgsConstructor(force = true) // For Jackson
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "type")
public class IndexMetadataData_ES_2_4 implements IndexMetadata {
    @Getter
    @JsonProperty("body")
    private final ObjectNode rawJson;

    @Getter
    @JsonProperty("name")
    private final String indexName;

    @Override
    public JsonNode getAliases() {
        return rawJson.get("aliases");        
    }

    public IndexMetadataData_ES_2_4(ObjectNode rawJson, String indexName) {
        validateRawJson(rawJson);
        this.rawJson = rawJson;
        this.indexName = indexName;
    }

    @Override
    public String getName() {
        return indexName;
    }

    @Override
    public JsonNode getMappings() {
        return rawJson.get(FIELD_MAPPINGS);
    }

    @Override
    public int getNumberOfShards() {
        return this.getSettings().get("index.number_of_shards").asInt();
    }

    @Override
    public JsonNode getSettings() {
        JsonNode settingsNode = rawJson.get(FIELD_SETTINGS);
        return (settingsNode != null && settingsNode.isObject()) ? settingsNode : rawJson.objectNode();
    }

    @Override
    public IndexMetadata deepCopy() {
        return new IndexMetadataData_ES_2_4(rawJson.deepCopy(), indexName);
    }

    @Override
    public String getId() {
        // In ES 2.4, the index ID is the same as the index name
        return getName();
    }
}
