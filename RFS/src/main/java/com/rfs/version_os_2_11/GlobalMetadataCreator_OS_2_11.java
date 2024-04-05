package com.rfs.version_os_2_11;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.ConnectionDetails;
import com.rfs.common.RestClient;

public class GlobalMetadataCreator_OS_2_11 {
    private static final Logger logger = LogManager.getLogger(GlobalMetadataCreator_OS_2_11.class);

    public static void create(ObjectNode root, ConnectionDetails connectionDetails, String[] componentTemplateWhitelist, String[] indexTemplateWhitelist) throws Exception {
        logger.info("Setting Global Metadata");

        GlobalMetadataData_OS_2_11 globalMetadata = new GlobalMetadataData_OS_2_11(root);
        createTemplates(globalMetadata, connectionDetails, indexTemplateWhitelist);
        createComponentTemplates(globalMetadata, connectionDetails, componentTemplateWhitelist);
        createIndexTemplates(globalMetadata, connectionDetails, indexTemplateWhitelist);
    }

    public static void createTemplates(GlobalMetadataData_OS_2_11 globalMetadata, ConnectionDetails connectionDetails, String[] indexTemplateWhitelist) throws Exception {
        logger.info("Setting Legacy Templates");
        ObjectNode templates = globalMetadata.getTemplates();

        if (templates == null){
            logger.info("No Legacy Templates");
            return;
        }

        if (indexTemplateWhitelist != null) {
            for (String templateName : indexTemplateWhitelist) {
                if (!templates.has(templateName) || templates.get(templateName) == null) {
                    logger.warn("Legacy Template not found: " + templateName);
                    continue;
                }

                logger.info("Setting Legacy Template: " + templateName);
                ObjectNode settings = (ObjectNode) globalMetadata.getTemplates().get(templateName);
                String path = "_template/" + templateName;
                createEntity(templateName, settings, connectionDetails, path);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                logger.info("Setting Legacy Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                String path = "_template/" + templateName;
                createEntity(templateName, settings, connectionDetails, path);
            }
        }
    }

    public static void createComponentTemplates(GlobalMetadataData_OS_2_11 globalMetadata, ConnectionDetails connectionDetails, String[] indexTemplateWhitelist) throws Exception {
        logger.info("Setting Component Templates");
        ObjectNode templates = globalMetadata.getComponentTemplates();

        if (templates == null){
            logger.info("No Component Templates");
            return;
        }

        if (indexTemplateWhitelist != null) {            
            for (String templateName : indexTemplateWhitelist) {
                if (!templates.has(templateName) || templates.get(templateName) == null) {
                    logger.warn("Component Template not found: " + templateName);
                    continue;
                }

                logger.info("Setting Component Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                String path = "_component_template/" + templateName;
                createEntity(templateName, settings, connectionDetails, path);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                logger.info("Setting Component Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                String path = "_component_template/" + templateName;
                createEntity(templateName, settings, connectionDetails, path);
            }
        }
    }

    public static void createIndexTemplates(GlobalMetadataData_OS_2_11 globalMetadata, ConnectionDetails connectionDetails, String[] indexTemplateWhitelist) throws Exception {
        logger.info("Setting Index Templates");
        ObjectNode templates = globalMetadata.getIndexTemplates();

        if (templates == null){
            logger.info("No Index Templates");
            return;
        }

        if (indexTemplateWhitelist != null) {
            for (String templateName : indexTemplateWhitelist) {
                if (!templates.has(templateName) || templates.get(templateName) == null) {
                    logger.warn("Index Template not found: " + templateName);
                    continue;
                }

                logger.info("Setting Index Template: " + templateName);
                ObjectNode settings = (ObjectNode) globalMetadata.getIndexTemplates().get(templateName);
                String path = "_index_template/" + templateName;
                createEntity(templateName, settings, connectionDetails, path);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                logger.info("Setting Index Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                String path = "_index_template/" + templateName;
                createEntity(templateName, settings, connectionDetails, path);
            }
        }
    }

    private static void createEntity(String entityName, ObjectNode settings, ConnectionDetails connectionDetails, String path) throws Exception {
        // Assemble the request details
        String body = settings.toString();

        // Confirm the index doesn't already exist, then create it
        RestClient client = new RestClient(connectionDetails);
        RestClient.Response response = client.get(path, true);
        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            String bodyString = body.toString();
            client.put(path, bodyString, false);
        } else if (response.code == HttpURLConnection.HTTP_OK) {
            logger.warn(entityName + " already exists. Skipping creation.");
        } else {
            logger.warn("Could not confirm that " + entityName + " does not already exist. Skipping creation.");
        }
    }
}
