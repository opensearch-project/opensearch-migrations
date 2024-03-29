package com.rfs.version_os_2_11;

import java.net.HttpURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.ConnectionDetails;
import com.rfs.common.IndexMetadata;
import com.rfs.common.RestClient;

public class IndexCreator_OS_2_11 {
    private static final Logger logger = LogManager.getLogger(IndexCreator_OS_2_11.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void create(String targetName, IndexMetadata.Data indexMetadata, ConnectionDetails connectionDetails) throws Exception {
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

        // Confirm the index doesn't already exist, then create it
        RestClient client = new RestClient(connectionDetails);
        int getResponseCode = client.get(targetName, true);
        if (getResponseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            String bodyString = body.toString();
            client.put(targetName, bodyString, false);
        } else if (getResponseCode == HttpURLConnection.HTTP_OK) {
            logger.warn("Index " + targetName + " already exists. Skipping creation.");
        } else {
            logger.warn("Could not confirm that index " + targetName + " does not already exist. Skipping creation.");
        }
    }
}
