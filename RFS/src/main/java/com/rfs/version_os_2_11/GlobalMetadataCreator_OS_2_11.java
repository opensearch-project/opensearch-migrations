package com.rfs.version_os_2_11;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import com.rfs.common.OpenSearchClient;
import com.rfs.models.GlobalMetadata;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class GlobalMetadataCreator_OS_2_11 implements GlobalMetadataCreator {
    private static final Logger logger = LogManager.getLogger(GlobalMetadataCreator_OS_2_11.class);

    private final OpenSearchClient client;
    private final List<String> legacyTemplateAllowlist;
    private final List<String> componentTemplateAllowlist;
    private final List<String> indexTemplateAllowlist;

    public GlobalMetadataCreatorResults create(GlobalMetadata root, IClusterMetadataContext context) {
        logger.info("Setting Global Metadata");

        var results = GlobalMetadataCreatorResults.builder();
        GlobalMetadataData_OS_2_11 globalMetadata = new GlobalMetadataData_OS_2_11(root.toObjectNode());
        results.legacyTemplates(createLegacyTemplates(globalMetadata, client, legacyTemplateAllowlist, context));
        results.componentTemplates(createComponentTemplates(globalMetadata, client, componentTemplateAllowlist, context));
        results.indexTemplates(createIndexTemplates(globalMetadata, client, indexTemplateAllowlist, context));
        return results.build();
    }

    protected List<String> createLegacyTemplates(
        GlobalMetadataData_OS_2_11 globalMetadata,
        OpenSearchClient client,
        List<String> templateAllowlist,
        IClusterMetadataContext context
    ) {
        var legacyTemplates = new ArrayList<String>();
        logger.info("Setting Legacy Templates...");
        ObjectNode templates = globalMetadata.getTemplates();

        if (templates == null) {
            logger.info("No Legacy Templates in Snapshot");
            return legacyTemplates;
        }

        if (templateAllowlist != null && templateAllowlist.size() == 0) {
            logger.info("No Legacy Templates in specified allowlist");
        } else if (templateAllowlist != null) {
            for (String templateName : templateAllowlist) {
                if (!templates.has(templateName) || templates.get(templateName) == null) {
                    logger.warn("Legacy Template not found: " + templateName);
                    continue;
                }

                logger.info("Setting Legacy Template: " + templateName);
                ObjectNode settings = (ObjectNode) globalMetadata.getTemplates().get(templateName);
                client.createLegacyTemplate(templateName, settings, context.createMigrateLegacyTemplateContext());
                legacyTemplates.add(templateName);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                logger.info("Setting Legacy Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                client.createLegacyTemplate(templateName, settings, context.createMigrateLegacyTemplateContext());
                legacyTemplates.add(templateName);
            }
        }
        return legacyTemplates;
    }

    protected List<String> createComponentTemplates(
        GlobalMetadataData_OS_2_11 globalMetadata,
        OpenSearchClient client,
        List<String> templateAllowlist,
        IClusterMetadataContext context
    ) {
        var componentTemplates = new ArrayList<String>();
        logger.info("Setting Component Templates...");
        ObjectNode templates = globalMetadata.getComponentTemplates();

        if (templates == null) {
            logger.info("No Component Templates in Snapshot");
            return componentTemplates;
        }

        if (templateAllowlist != null && templateAllowlist.size() == 0) {
            logger.info("No Component Templates in specified allowlist");
            return componentTemplates;
        } else if (templateAllowlist != null) {
            for (String templateName : templateAllowlist) {
                if (!templates.has(templateName) || templates.get(templateName) == null) {
                    logger.warn("Component Template not found: " + templateName);
                    continue;
                }

                logger.info("Setting Component Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                client.createComponentTemplate(templateName, settings, context.createComponentTemplateContext());
                componentTemplates.add(templateName);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                logger.info("Setting Component Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                client.createComponentTemplate(templateName, settings, context.createComponentTemplateContext());
                componentTemplates.add(templateName);
            }
        }
        return componentTemplates;
    }

    protected List<String> createIndexTemplates(
        GlobalMetadataData_OS_2_11 globalMetadata,
        OpenSearchClient client,
        List<String> templateAllowlist,
        IClusterMetadataContext context
    ) {
        var indexTemplates = new ArrayList<String>();
        logger.info("Setting Index Templates...");
        ObjectNode templates = globalMetadata.getIndexTemplates();

        if (templates == null) {
            logger.info("No Index Templates in Snapshot");
            return indexTemplates;
        }

        if (templateAllowlist != null && templateAllowlist.size() == 0) {
            logger.info("No Index Templates in specified allowlist");
            return indexTemplates;
        } else if (templateAllowlist != null) {
            for (String templateName : templateAllowlist) {
                if (!templates.has(templateName) || templates.get(templateName) == null) {
                    logger.warn("Index Template not found: " + templateName);
                    continue;
                }

                logger.info("Setting Index Template: " + templateName);
                ObjectNode settings = (ObjectNode) globalMetadata.getIndexTemplates().get(templateName);
                client.createIndexTemplate(templateName, settings, context.createMigrateTemplateContext());
                indexTemplates.add(templateName);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                logger.info("Setting Index Template: " + templateName);
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                client.createIndexTemplate(templateName, settings, context.createMigrateTemplateContext());
                indexTemplates.add(templateName);
            }
        }
        return indexTemplates;
    }
}
