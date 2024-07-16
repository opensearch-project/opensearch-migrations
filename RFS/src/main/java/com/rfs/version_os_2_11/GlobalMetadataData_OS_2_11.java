package com.rfs.version_os_2_11;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.models.GlobalMetadata;

public class GlobalMetadataData_OS_2_11 implements GlobalMetadata {
    private final ObjectNode root;

    public GlobalMetadataData_OS_2_11(ObjectNode root) {
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
        if (root.get("index_template") != null) {
            return (ObjectNode) root.get("index_template").get("index_template");
        } else {
            return null;
        }
    }

    public ObjectNode getComponentTemplates() {
        if (root.get("component_template") != null) {
            return (ObjectNode) root.get("component_template").get("component_template");
        } else {
            return null;
        }
    }
}
