package com.rfs.version_universal;

import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.models.GlobalMetadata;
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
}
