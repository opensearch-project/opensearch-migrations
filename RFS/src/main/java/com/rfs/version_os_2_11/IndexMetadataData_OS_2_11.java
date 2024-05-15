package com.rfs.version_os_2_11;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.common.IndexMetadata;

public class IndexMetadataData_OS_2_11 implements IndexMetadata.Data {
    private ObjectNode root;
    private String indexId;
    private String indexName;

    public IndexMetadataData_OS_2_11(ObjectNode root, String indexId, String indexName) {
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
    public ObjectNode toObjectNode() {
        return root;
    }
}
