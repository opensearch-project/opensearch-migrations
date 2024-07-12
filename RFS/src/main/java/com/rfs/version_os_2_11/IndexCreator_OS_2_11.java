package com.rfs.version_os_2_11;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.common.OpenSearchClient;
import com.rfs.models.IndexMetadata;

public class IndexCreator_OS_2_11 {
    private static final ObjectMapper mapper = new ObjectMapper();
    protected final OpenSearchClient client;

    public IndexCreator_OS_2_11(OpenSearchClient client) {
        this.client = client;
    }

    public Optional<ObjectNode> create(IndexMetadata index, String indexName, String indexId) {
        IndexMetadataData_OS_2_11 indexMetadata = new IndexMetadataData_OS_2_11(index.rawJson(), indexId, indexName);

        // Remove some settings which will cause errors if you try to pass them to the API
        ObjectNode settings = indexMetadata.getSettings();

        String[] problemFields = { "creation_date", "provided_name", "uuid", "version" };
        for (String field : problemFields) {
            settings.remove(field);
        }

        // Assemble the request body
        ObjectNode body = mapper.createObjectNode();
        body.set("aliases", indexMetadata.getAliases());
        // Working around for missing OS_1_3 definition
        body.set("mappings", index.getMappings());
        body.set("settings", settings);

        // Create the index; it's fine if it already exists
        return client.createIndex(indexName, body);
    }
}
