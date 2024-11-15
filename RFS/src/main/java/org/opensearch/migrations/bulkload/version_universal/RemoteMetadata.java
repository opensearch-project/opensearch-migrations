package org.opensearch.migrations.bulkload.version_universal;

import java.util.Optional;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class RemoteMetadata implements GlobalMetadata {

    private ObjectNode sourceData;

    @Override
    public ObjectNode toObjectNode() {
        return sourceData;
    }

    @Override
    public ObjectNode getTemplates() {
        return Optional.ofNullable(sourceData)
            .map(node -> node.get("templates"))
            .map(node -> node.get("templates"))
            .filter(ObjectNode.class::isInstance)
            .map(ObjectNode.class::cast)
            .orElse(null);
    }

    @Override
    public ObjectNode getIndexTemplates() {
        return Optional.ofNullable(sourceData)
                .map(node -> node.get("index_template"))
                .map(node -> node.get("index_template"))
                .filter(ObjectNode.class::isInstance)
                .map(ObjectNode.class::cast)
                .orElse(null);    }

    @Override
    public ObjectNode getComponentTemplates() {
        return Optional.ofNullable(sourceData)
                .map(node -> node.get("component_template"))
                .map(node -> node.get("component_template"))
                .filter(ObjectNode.class::isInstance)
                .map(ObjectNode.class::cast)
                .orElse(null);    }
}
