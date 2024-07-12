package com.rfs.version_es_6_8;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.models.GlobalMetadata;

public class GlobalMetadataData_ES_6_8 implements GlobalMetadata {
    private final ObjectNode root;

    public GlobalMetadataData_ES_6_8(ObjectNode root) {
        this.root = root;
    }

    @Override
    public ObjectNode toObjectNode() {
        return root;
    }

    public ObjectNode getTemplates() {
        return (ObjectNode) root.get("templates");
    }
}
