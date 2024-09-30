package org.opensearch.migrations.bulkload.version_universal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.opensearch.migrations.bulkload.models.IndexMetadata;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class RemoteIndexMetadata implements IndexMetadata {

    private String indexName;
    private ObjectNode sourceData;

    @Override
    public ObjectNode getRawJson() {
        return sourceData;
    }

    @Override
    public JsonNode getAliases() {
        return sourceData.get("aliases");
    }

    @Override
    public String getId() {
        // The ID is the name in this case
        return getName();
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
