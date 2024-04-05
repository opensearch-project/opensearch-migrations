package com.rfs.version_es_7_10;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.transformers.TransformFunctions;

public class IndexMetadataData_ES_7_10 implements com.rfs.common.IndexMetadata.Data {
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

    public ObjectNode getAliases() {
        return (ObjectNode) root.get("aliases");
    }

    public String getId() {
        return indexId;
    }

    public ObjectNode getMappings() {
        if (mappings != null) {
            return mappings;
        }

        ArrayNode mappingsArray = (ArrayNode) root.get("mappings");
        ObjectNode mappingsNode = TransformFunctions.getMappingsFromBeneathIntermediate(mappingsArray);
        mappings = mappingsNode;

        return mappings;
    }

    public String getName() {
        return indexName;
    }

    public int getNumberOfShards() {
        return this.getSettings().get("index").get("number_of_shards").asInt();
    }   

    public ObjectNode getSettings() {
        if (settings != null) {
            return settings;
        }

        ObjectNode treeSettings = TransformFunctions.convertFlatSettingsToTree(
            (ObjectNode) root.get("settings")
        );

        settings = treeSettings;

        return settings;
    }

    public ObjectNode toObjectNode() {
        return root;
    }
}
