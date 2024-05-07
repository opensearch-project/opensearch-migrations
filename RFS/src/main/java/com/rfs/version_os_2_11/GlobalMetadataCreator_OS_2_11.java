package com.rfs.version_os_2_11;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.OpenSearchClient;

public class GlobalMetadataCreator_OS_2_11 {
    private static final Logger logger = LogManager.getLogger(GlobalMetadataCreator_OS_2_11.class);

    private final OpenSearchClient client;
    private final List<String> legacyTemplateWhitelist;
    private final List<String> componentTemplateWhitelist;
    private final List<String> indexTemplateWhitelist;

    public GlobalMetadataCreator_OS_2_11(OpenSearchClient client, List<String> legacyTemplateWhitelist, List<String> componentTemplateWhitelist, List<String> indexTemplateWhitelist) {
        this.client = client;
        this.legacyTemplateWhitelist = legacyTemplateWhitelist;
        this.componentTemplateWhitelist = componentTemplateWhitelist;
        this.indexTemplateWhitelist = indexTemplateWhitelist;
    }

    public void create(ObjectNode root) throws Exception {
        logger.info("Setting Global Metadata");

        GlobalMetadataData_OS_2_11 globalMetadata = new GlobalMetadataData_OS_2_11(root);
        createLegacyTemplates(globalMetadata, client, legacyTemplateWhitelist);
        createComponentTemplates(globalMetadata, client, componentTemplateWhitelist);
        createIndexTemplates(globalMetadata, client, indexTemplateWhitelist);
    }

    protected void createLegacyTemplates(GlobalMetadataData_OS_2_11 globalMetadata, OpenSearchClient client, List<String> templateWhitelist) throws Exception {
        logger.info("Setting Legacy Templates");
        ObjectNode templates = globalMetadata.getTemplates();

        if (templates == null){
            logger.info("No Legacy Templates");
            return;
        }

        if (templateWhitelist != null) {
            for (String templateName : templateWhitelist) {
                if (!templates.has(templateName) || templates.get(templateName) == null) {
                    logger.warn("Legacy Template not found: " + templateName);
                    continue;
                }

                logger.info("Setting Legacy Template: " + templateName);
                ObjectNode settings = (ObjectNode) globalMetadata.getTemplates().get(templateName);
                client.createLegacyTemplate(templateName, settings);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                logger.info("Setting Legacy Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                client.createLegacyTemplate(templateName, settings);
            }
        }
    }

    protected void createComponentTemplates(GlobalMetadataData_OS_2_11 globalMetadata, OpenSearchClient client, List<String> indexTemplateWhitelist) throws Exception {
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
                client.createComponentTemplate(templateName, settings);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                logger.info("Setting Component Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                client.createComponentTemplate(templateName, settings);
            }
        }
    }

    protected void createIndexTemplates(GlobalMetadataData_OS_2_11 globalMetadata, OpenSearchClient client, List<String> indexTemplateWhitelist) throws Exception {
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
                client.createIndexTemplate(templateName, settings);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                logger.info("Setting Index Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                client.createIndexTemplate(templateName, settings);
            }
        }
    }
}
