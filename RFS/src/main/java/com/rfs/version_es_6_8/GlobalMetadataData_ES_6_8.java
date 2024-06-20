package com.rfs.version_es_6_8;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class GlobalMetadataData_ES_6_8 implements com.rfs.models.GlobalMetadata {
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
