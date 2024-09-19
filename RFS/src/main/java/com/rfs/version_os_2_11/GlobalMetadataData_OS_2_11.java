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
        String indexTemplateKey = "index_template";
        if (root.get(indexTemplateKey) != null) {
            return (ObjectNode) root.get(indexTemplateKey).get(indexTemplateKey);
        } else {
            return null;
        }
    }

    public ObjectNode getComponentTemplates() {
        String componentTemplateKey = "component_template";
        if (root.get(componentTemplateKey) != null) {
            return (ObjectNode) root.get(componentTemplateKey).get(componentTemplateKey);
        } else {
            return null;
        }
    }
}
