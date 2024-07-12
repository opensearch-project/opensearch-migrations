package com.rfs.version_os_2_11;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.common.OpenSearchClient;
import com.rfs.models.GlobalMetadata;

public class GlobalMetadataCreator_OS_2_11 {
    private static final Logger logger = LogManager.getLogger(GlobalMetadataCreator_OS_2_11.class);

    private final OpenSearchClient client;
    private final List<String> legacyTemplateAllowlist;
    private final List<String> componentTemplateAllowlist;
    private final List<String> indexTemplateAllowlist;

    public GlobalMetadataCreator_OS_2_11(
        OpenSearchClient client,
        List<String> legacyTemplateAllowlist,
        List<String> componentTemplateAllowlist,
        List<String> indexTemplateAllowlist
    ) {
        this.client = client;
        this.legacyTemplateAllowlist = legacyTemplateAllowlist;
        this.componentTemplateAllowlist = componentTemplateAllowlist;
        this.indexTemplateAllowlist = indexTemplateAllowlist;
    }

    public void create(GlobalMetadata root) {
        logger.info("Setting Global Metadata");

        GlobalMetadataData_OS_2_11 globalMetadata = new GlobalMetadataData_OS_2_11(root.toObjectNode());
        createLegacyTemplates(globalMetadata, client, legacyTemplateAllowlist);
        createComponentTemplates(globalMetadata, client, componentTemplateAllowlist);
        createIndexTemplates(globalMetadata, client, indexTemplateAllowlist);
    }

    protected void createLegacyTemplates(
        GlobalMetadataData_OS_2_11 globalMetadata,
        OpenSearchClient client,
        List<String> templateAllowlist
    ) {
        logger.info("Setting Legacy Templates...");
        ObjectNode templates = globalMetadata.getTemplates();

        if (templates == null) {
            logger.info("No Legacy Templates in Snapshot");
            return;
        }

        if (templateAllowlist != null && templateAllowlist.size() == 0) {
            logger.info("No Legacy Templates in specified allowlist");
            return;
        } else if (templateAllowlist != null) {
            for (String templateName : templateAllowlist) {
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

    protected void createComponentTemplates(
        GlobalMetadataData_OS_2_11 globalMetadata,
        OpenSearchClient client,
        List<String> templateAllowlist
    ) {
        logger.info("Setting Component Templates...");
        ObjectNode templates = globalMetadata.getComponentTemplates();

        if (templates == null) {
            logger.info("No Component Templates in Snapshot");
            return;
        }

        if (templateAllowlist != null && templateAllowlist.size() == 0) {
            logger.info("No Component Templates in specified allowlist");
            return;
        } else if (templateAllowlist != null) {
            for (String templateName : templateAllowlist) {
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

    protected void createIndexTemplates(
        GlobalMetadataData_OS_2_11 globalMetadata,
        OpenSearchClient client,
        List<String> templateAllowlist
    ) {
        logger.info("Setting Index Templates...");
        ObjectNode templates = globalMetadata.getIndexTemplates();

        if (templates == null) {
            logger.info("No Index Templates in Snapshot");
            return;
        }

        if (templateAllowlist != null && templateAllowlist.size() == 0) {
            logger.info("No Index Templates in specified allowlist");
            return;
        } else if (templateAllowlist != null) {
            for (String templateName : templateAllowlist) {
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
