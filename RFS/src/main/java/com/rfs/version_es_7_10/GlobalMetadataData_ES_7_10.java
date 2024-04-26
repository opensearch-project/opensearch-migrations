package com.rfs.version_es_7_10;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class GlobalMetadataData_ES_7_10 implements com.rfs.common.GlobalMetadata.Data {
    private final ObjectNode root;

    public GlobalMetadataData_ES_7_10(ObjectNode root) {
        this.root = root;
    }

    @Override
    public ObjectNode toObjectNode() throws Exception {
        return root;
    }

    public ObjectNode getTemplates() throws Exception {
        return (ObjectNode) root.get("templates");
    }

    public ObjectNode getIndexTemplates() throws Exception {
        return (ObjectNode) root.get("index_template").get("index_template");
    }

    public ObjectNode getComponentTemplates() throws Exception {
        return (ObjectNode) root.get("component_template").get("component_template");
    }
}
