package com.rfs.version_es_7_10;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.models.GlobalMetadata;

public class GlobalMetadataData_ES_7_10 implements GlobalMetadata {
    private final ObjectNode root;

    public GlobalMetadataData_ES_7_10(ObjectNode root) {
        this.root = root;
    }

    @Override
    public ObjectNode toObjectNode() {
        return root;
    }

    public ObjectNode getTemplates() {
        return (ObjectNode) root.get("templates");
    }

    public ObjectNode getIndexTemplates() {
        return (ObjectNode) root.get("index_template").get("index_template");
    }

    public ObjectNode getComponentTemplates() {
        return (ObjectNode) root.get("component_template").get("component_template");
    }
}
