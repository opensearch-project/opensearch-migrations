package com.rfs.version_es_7_10;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.models.IndexMetadata;
import com.rfs.transformers.TransformFunctions;

public class IndexMetadataData_ES_7_10 implements IndexMetadata {
    private ObjectNode root;
    private ObjectNode mappings;
    private ObjectNode settings;
    private String indexId;
    private String indexName;

    public IndexMetadataData_ES_7_10(ObjectNode root, String indexId, String indexName) {
        this.root = root;
        this.mappings = null;
        this.settings = null;
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
        if (mappings != null) {
            return mappings;
        }

        ArrayNode mappingsArray = (ArrayNode) root.get("mappings");
        ObjectNode mappingsNode = TransformFunctions.getMappingsFromBeneathIntermediate(mappingsArray);
        mappings = mappingsNode;

        return mappings;
    }

    @Override
    public String getName() {
        return indexName;
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

        ObjectNode treeSettings = TransformFunctions.convertFlatSettingsToTree((ObjectNode) root.get("settings"));

        settings = treeSettings;

        return settings;
    }

    @Override
    public ObjectNode rawJson() {
        return root;
    }

    @Override
    public IndexMetadata deepCopy() {
        return new IndexMetadataData_ES_7_10(root.deepCopy(), indexId, indexName);
    }
}
