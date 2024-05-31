package com.rfs.version_os_2_11;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.IndexMetadata;
import com.rfs.common.OpenSearchClient;

public class IndexCreator_OS_2_11 {
    private static final ObjectMapper mapper = new ObjectMapper();
    protected final OpenSearchClient client;

    public IndexCreator_OS_2_11 (OpenSearchClient client) {
        this.client = client;
    }

    public Optional<ObjectNode> create(String indexName, IndexMetadata.Data indexMetadata) {
        // Remove some settings which will cause errors if you try to pass them to the API
        ObjectNode settings = indexMetadata.getSettings();

        String[] problemFields = {"creation_date", "provided_name", "uuid", "version"};
        for (String field : problemFields) {
            settings.remove(field);
        }

        // Assemble the request body
        ObjectNode body = mapper.createObjectNode();
        body.set("aliases", indexMetadata.getAliases());
        body.set("mappings", indexMetadata.getMappings());
        body.set("settings", settings);

        // Create the index; it's fine if it already exists
        return client.createIndex(indexName, body);
    }
}
