package com.rfs.version_es_6_8;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class GlobalMetadataData_ES_6_8 implements com.rfs.common.GlobalMetadata.Data {
    private final ObjectNode root;

    public GlobalMetadataData_ES_6_8(ObjectNode root) {
        this.root = root;
    }

    public ObjectNode toObjectNode() throws Exception {
        return root;
    }

    public ObjectNode getTemplates() throws Exception {
        return (ObjectNode) root.get("templates");
    }
    
}
