package com.rfs.version_os_2_11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import com.rfs.common.OpenSearchClient;
import com.rfs.models.GlobalMetadata;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class GlobalMetadataCreator_OS_2_11 implements GlobalMetadataCreator {

    private final OpenSearchClient client;
    private final List<String> legacyTemplateAllowlist;
    private final List<String> componentTemplateAllowlist;
    private final List<String> indexTemplateAllowlist;

    public GlobalMetadataCreatorResults create(
        GlobalMetadata root,
        MigrationMode mode,
        IClusterMetadataContext context) {
        log.info("Setting Global Metadata");

        var results = GlobalMetadataCreatorResults.builder();
        GlobalMetadataData_OS_2_11 globalMetadata = new GlobalMetadataData_OS_2_11(root.toObjectNode());
        results.legacyTemplates(createLegacyTemplates(globalMetadata, mode, context));
        results.componentTemplates(createComponentTemplates(globalMetadata, mode, context));
        results.indexTemplates(createIndexTemplates(globalMetadata, mode, context));
        return results.build();
    }

    public List<String> createLegacyTemplates(GlobalMetadataData_OS_2_11 metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createTemplates(
            metadata.getTemplates(),
            legacyTemplateAllowlist,
            TemplateTypes.LegacyIndexTemplate,
            mode,
            context
        );
    }

    public List<String> createComponentTemplates(GlobalMetadataData_OS_2_11 metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createTemplates(
            metadata.getComponentTemplates(),
            componentTemplateAllowlist,
            TemplateTypes.ComponentTemplates,
            mode,
            context
        );
    }

    public List<String> createIndexTemplates(GlobalMetadataData_OS_2_11 metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createTemplates(
            metadata.getIndexTemplates(),
            indexTemplateAllowlist,
            TemplateTypes.IndexTemplate,
            mode,
            context
        );
    }

    @AllArgsConstructor
    private enum TemplateTypes {
        IndexTemplate(
            (client, name, body, context) -> client.createIndexTemplate(name, body, context.createMigrateTemplateContext()),
            (client, name) -> client.hasIndexTemplate(name)
        ),

        LegacyIndexTemplate(
            (client, name, body, context) -> client.createLegacyTemplate(name, body, context.createMigrateLegacyTemplateContext()),
            (client, name) -> client.hasLegacyTemplate(name)
        ),

        ComponentTemplates(
            (client, name, body, context) -> client.createComponentTemplate(name, body, context.createComponentTemplateContext()),
            (client, name) -> client.hasComponentTemplate(name)
        );
        final TemplateCreator creator;
        final TemplateExistsCheck alreadyExistsCheck;
    }

    @FunctionalInterface
    interface TemplateCreator {
        Optional<ObjectNode> createTemplate(OpenSearchClient client, String name, ObjectNode body, IClusterMetadataContext context);
    }

    @FunctionalInterface
    interface TemplateExistsCheck {
        boolean templateAlreadyExists(OpenSearchClient client, String name);
    }


    private List<String> createTemplates(
        ObjectNode templates,
        List<String> templateAllowlist,
        TemplateTypes templateType,
        MigrationMode mode,
        IClusterMetadataContext context
    ) {

        var templatesToCreate = new HashMap<String, ObjectNode>();
        var templateList = new ArrayList<String>();
        log.info("Setting {} ...", templateType);

        if (templates == null) {
            log.info("No {} in Snapshot", templateType);
            return List.of();
        }

        if (templateAllowlist != null && templateAllowlist.size() == 0) {
            log.info("No {} in specified allowlist", templateType);
            return List.of();
        } else if (templateAllowlist != null) {
            for (String templateName : templateAllowlist) {
                if (!templates.has(templateName) || templates.get(templateName) == null) {
                    log.warn("{} not found: {}", templateType, templateName);
                    continue;
                }
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                templatesToCreate.put(templateName, settings);
            }
        } else {
            // Get the template names
            List<String> templateKeys = new ArrayList<>();
            templates.fieldNames().forEachRemaining(templateKeys::add);

            // Create each template
            for (String templateName : templateKeys) {
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                templatesToCreate.put(templateName, settings);
            }
        }

        templatesToCreate.forEach((templateName, templateBody) -> {
            log.info("Creating {}: {}", templateType, templateName);
            switch (mode) {
                case SIMULATE:
                    var alreadyExists = templateType.alreadyExistsCheck.templateAlreadyExists(client, templateName);
                    if (!alreadyExists) {
                        templateList.add(templateName);
                    } else {
                        log.warn("Template {} already exists on the target, it will not be created during a migration", templateName);
                    }
                    break;                    

                case PERFORM:
                    var createdTemplate = templateType.creator.createTemplate(client, templateName, templateBody, context);
                    if (createdTemplate.isPresent()) {
                        templateList.add(templateName);
                    } else {
                        log.warn("Template {} already exists on the target, unable to create", templateName);
                    }
                    break;
            }
        });

        return templateList;
    }
}
