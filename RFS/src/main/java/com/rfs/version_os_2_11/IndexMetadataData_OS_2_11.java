package com.rfs.version_os_2_11;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class IndexMetadataData_OS_2_11 implements com.rfs.common.IndexMetadata.Data {
    private ObjectNode root;
    private String indexId;
    private String indexName;

    public IndexMetadataData_OS_2_11(ObjectNode root, String indexId, String indexName) {
        this.root = root;
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
        return (ObjectNode) root.get("mappings");
    }

    public String getName() {
        return indexName;
    }

    public int getNumberOfShards() {
        return this.getSettings().get("number_of_shards").asInt();
    }   

    public ObjectNode getSettings() {
        return (ObjectNode) root.get("settings");
    }

    public ObjectNode toObjectNode() {
        return root;
    }
}
