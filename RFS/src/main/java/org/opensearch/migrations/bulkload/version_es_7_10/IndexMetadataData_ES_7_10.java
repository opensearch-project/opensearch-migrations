package org.opensearch.migrations.bulkload.version_es_7_10;

import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.transformers.TransformFunctions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;

public class IndexMetadataData_ES_7_10 implements IndexMetadata {
    @Getter
    private final ObjectNode rawJson;
    private ObjectNode mappings;
    private ObjectNode settings;
    @Getter
    private final String id;
    @Getter
    private final String name;

    public IndexMetadataData_ES_7_10(ObjectNode root, String indexId, String indexName) {
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
