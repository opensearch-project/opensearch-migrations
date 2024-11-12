package org.opensearch.migrations.bulkload.version_es_6_8;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.databind.node.ObjectNode;

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

    @Override
    public ObjectNode getIndexTemplates() {
        return null;
    }

    @Override
    public ObjectNode getComponentTemplates() {
        return null;
    }
}
