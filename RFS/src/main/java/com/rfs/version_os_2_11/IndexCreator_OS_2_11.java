package com.rfs.version_os_2_11;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.IndexMetadata;
import com.rfs.common.OpenSearchClient;

public class IndexCreator_OS_2_11 {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void create(String indexName, IndexMetadata.Data indexMetadata, OpenSearchClient client) throws Exception {
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

        // Idempotently create the index
        client.createIndex(indexName, body);
    }
}
