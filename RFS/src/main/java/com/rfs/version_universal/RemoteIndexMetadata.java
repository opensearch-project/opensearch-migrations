package com.rfs.version_universal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.models.IndexMetadata;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class RemoteIndexMetadata implements IndexMetadata {

    private String indexName;
    private ObjectNode sourceData;

    @Override
    public ObjectNode rawJson() {
        return sourceData;
    }

    @Override
    public JsonNode getAliases() {
        return sourceData.get("aliases");
    }

    @Override
    public String getId() {
        return indexName;
    }

    @Override
    public JsonNode getMappings() {
        return sourceData.get("mappings");
    }

    @Override
    public String getName() {
        return indexName;
    }

    @Override
    public int getNumberOfShards() {
        throw new UnsupportedOperationException("Unimplemented method 'getNumberOfShards'");
    }

    @Override
    public JsonNode getSettings() {
        return sourceData.get("settings");
    }

    @Override
    public IndexMetadata deepCopy() {
        return new RemoteIndexMetadata(indexName, sourceData.deepCopy());
    }
    
}
