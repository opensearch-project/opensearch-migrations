package org.opensearch.migrations.bulkload.version_es_7_10;

import java.util.Optional;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.databind.node.ObjectNode;

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
        return Optional.ofNullable(root)
                .map(node -> node.get("templates"))
                .filter(ObjectNode.class::isInstance)
                .map(ObjectNode.class::cast)
                .orElse(null);
    }

    public ObjectNode getIndexTemplates() {
        return (ObjectNode) root.get("index_template").get("index_template");
    }

    public ObjectNode getComponentTemplates() {
        return (ObjectNode) root.get("component_template").get("component_template");
    }
}
