package org.opensearch.migrations.bulkload.version_os_2_11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;

import com.fasterxml.jackson.databind.node.ObjectNode;
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

    public List<CreationResult> createLegacyTemplates(GlobalMetadataData_OS_2_11 metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createTemplates(
            metadata.getTemplates(),
            legacyTemplateAllowlist,
            TemplateTypes.LEGACY_INDEX_TEMPLATE,
            mode,
            context
        );
    }

    public List<CreationResult> createComponentTemplates(GlobalMetadataData_OS_2_11 metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createTemplates(
            metadata.getComponentTemplates(),
            componentTemplateAllowlist,
            TemplateTypes.COMPONENT_TEMPLATE,
            mode,
            context
        );
    }

    public List<CreationResult> createIndexTemplates(GlobalMetadataData_OS_2_11 metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createTemplates(
            metadata.getIndexTemplates(),
            indexTemplateAllowlist,
            TemplateTypes.INDEX_TEMPLATE,
            mode,
            context
        );
    }

    @AllArgsConstructor
    private enum TemplateTypes {
        INDEX_TEMPLATE(
            (targetClient, name, body, context) -> targetClient.createIndexTemplate(name, body, context.createMigrateTemplateContext()),
            (targetClient, name) -> targetClient.hasIndexTemplate(name)
        ),

        LEGACY_INDEX_TEMPLATE(
            (targetClient, name, body, context) -> targetClient.createLegacyTemplate(name, body, context.createMigrateLegacyTemplateContext()),
            (targetClient, name) -> targetClient.hasLegacyTemplate(name)
        ),

        COMPONENT_TEMPLATE(
            (targetClient, name, body, context) -> targetClient.createComponentTemplate(name, body, context.createComponentTemplateContext()),
            (targetClient, name) -> targetClient.hasComponentTemplate(name)
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


    private List<CreationResult> createTemplates(
        ObjectNode templates,
        List<String> templateAllowlist,
        TemplateTypes templateType,
        MigrationMode mode,
        IClusterMetadataContext context
    ) {

        log.info("Setting {} ...", templateType);

        if (templates == null) {
            log.info("No {} in Snapshot", templateType);
            return List.of();
        }

        if (templateAllowlist != null && templateAllowlist.isEmpty()) {
            log.info("No {} in specified allowlist", templateType);
            return List.of();
        }

        var templatesToCreate = getTemplatesToCreate(templates, templateAllowlist, templateType);

        return processTemplateCreation(templatesToCreate, templateType, mode, context);
    }

    private Map<String, ObjectNode> getTemplatesToCreate(ObjectNode templates, List<String> templateAllowlist, TemplateTypes templateType) {
        var templatesToCreate = new HashMap<String, ObjectNode>();

        if (templateAllowlist != null) {
            for (String templateName : templateAllowlist) {
                if (!templates.has(templateName) || templates.get(templateName) == null) {
                    log.warn("{} not found: {}", templateType, templateName);
                    continue;
                }
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                templatesToCreate.put(templateName, settings);
            }
        } else {
            templates.fieldNames().forEachRemaining(templateName -> {
                ObjectNode settings = (ObjectNode) templates.get(templateName);
                templatesToCreate.put(templateName, settings);
            });
        }

        return templatesToCreate;
    }

    private List<CreationResult> processTemplateCreation(
            Map<String, ObjectNode> templatesToCreate,
            TemplateTypes templateType,
            MigrationMode mode,
            IClusterMetadataContext context
        ) {

        List<CreationResult> templateList = new ArrayList<>();

        templatesToCreate.forEach((templateName, templateBody) -> {
            var creationResult = CreationResult.builder().name(templateName);
            log.info("Creating {}: {}", templateType, templateName);
            try {
                if (mode == MigrationMode.SIMULATE) {
                    if (templateType.alreadyExistsCheck.templateAlreadyExists(client, templateName)) {
                        creationResult.failureType(CreationFailureType.ALREADY_EXISTS);
                        log.warn("Template {} already exists on the target, it will not be created during a migration", templateName);
                    }
                } else if (mode == MigrationMode.PERFORM) {
                    var createdTemplate = templateType.creator.createTemplate(client, templateName, templateBody, context);
                    if (createdTemplate.isEmpty()) {
                        creationResult.failureType(CreationFailureType.ALREADY_EXISTS);
                        log.warn("Template {} already exists on the target, unable to create", templateName);
                    }
                }
            } catch (Exception e) {
                creationResult.failureType(CreationFailureType.TARGET_CLUSTER_FAILURE);
                creationResult.exception(e);
            }
            templateList.add(creationResult.build());
        });

        return templateList;
    }
}
