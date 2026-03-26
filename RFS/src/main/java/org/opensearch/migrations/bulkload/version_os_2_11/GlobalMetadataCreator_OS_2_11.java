package org.opensearch.migrations.bulkload.version_os_2_11;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.FilterScheme;
import org.opensearch.migrations.bulkload.common.InvalidResponse;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.IClusterMetadataContext;
import org.opensearch.migrations.parsing.ObjectNodeUtils;

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
        results.legacyTemplates(createLegacyTemplates(root, mode, context));
        results.componentTemplates(createComponentTemplates(root, mode, context));
        results.indexTemplates(createIndexTemplates(root, mode, context));
        return results.build();
    }

    public List<CreationResult> createLegacyTemplates(GlobalMetadata metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createTemplates(
            metadata.getTemplates(),
            legacyTemplateAllowlist,
            TemplateTypes.LEGACY_INDEX_TEMPLATE,
            mode,
            context
        );
    }

    public List<CreationResult> createComponentTemplates(GlobalMetadata metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createTemplates(
            metadata.getComponentTemplates(),
            componentTemplateAllowlist,
            TemplateTypes.COMPONENT_TEMPLATE,
            mode,
            context
        );
    }

    public List<CreationResult> createIndexTemplates(GlobalMetadata metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createTemplates(
            metadata.getIndexTemplates(),
            indexTemplateAllowlist,
            TemplateTypes.INDEX_TEMPLATE,
            mode,
            context
        );
    }

    @AllArgsConstructor
    enum TemplateTypes {
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

        var templatesToCreate = getAllTemplates(templates);

        return processTemplateCreation(templatesToCreate, templateType, templateAllowlist, mode, context);
    }

    Map<String, ObjectNode> getAllTemplates(ObjectNode templates) {
        var templatesToCreate = new HashMap<String, ObjectNode>();

        templates.fieldNames().forEachRemaining(templateName -> {
            ObjectNode settings = (ObjectNode) templates.get(templateName);
            templatesToCreate.put(templateName, settings);
        });

        return templatesToCreate;
    }

    private List<CreationResult> processTemplateCreation(
            Map<String, ObjectNode> templatesToCreate,
            TemplateTypes templateType,
            List<String> templateAllowList,
            MigrationMode mode,
            IClusterMetadataContext context
        ) {
        var skipCreation = FilterScheme.filterByAllowList(templateAllowList).negate();

        return templatesToCreate.entrySet().stream().map(kvp -> {
            var templateName = kvp.getKey();
            var templateBody = kvp.getValue();

            String[] problemSettings = { "settings.mapping.single_type", "settings.mapper.dynamic" };
            for (var field : problemSettings) {
                ObjectNodeUtils.removeFieldsByPath(templateBody, field);
            }

            var creationResult = CreationResult.builder().name(templateName);

            if (skipCreation.test(templateName)) {
                log.atInfo().setMessage("Template {} was skipped due to allowlist filter {}").addArgument(templateName).addArgument(templateAllowList).log();
                return creationResult.failureType(CreationFailureType.SKIPPED_DUE_TO_FILTER).build();
            }

            log.info("Creating {}: {}", templateType, templateName);
            try {
                if (mode == MigrationMode.SIMULATE) {
                    if (templateType.alreadyExistsCheck.templateAlreadyExists(client, templateName)) {
                        creationResult.failureType(CreationFailureType.ALREADY_EXISTS);
                        log.warn("Template {} already exists on the target, it will not be created during a migration", templateName);
                    }
                } else if (mode == MigrationMode.PERFORM) {
                    createTemplateWithRetry(templateType, templateName, templateBody, context, creationResult);
                }
            } catch (Exception e) {
                creationResult.failureType(CreationFailureType.TARGET_CLUSTER_FAILURE);
                creationResult.exception(e);
            }
            return creationResult.build();
        }).collect(Collectors.toList());
    }

    private void createTemplateWithRetry(
        TemplateTypes templateType,
        String templateName,
        ObjectNode templateBody,
        IClusterMetadataContext context,
        CreationResult.CreationResultBuilder creationResult
    ) {
        while (true) {
            try {
                var createdTemplate = templateType.creator.createTemplate(client, templateName, templateBody, context);
                if (createdTemplate.isEmpty()) {
                    creationResult.failureType(CreationFailureType.ALREADY_EXISTS);
                    log.warn("Template {} already exists on the target, unable to create", templateName);
                }
                return;
            } catch (Exception e) {
                var unsupportedParams = findUnsupportedMappingParams(e);
                if (unsupportedParams.isEmpty()) {
                    throw e;
                }
                removeUnsupportedMappingParams(templateName, templateBody, unsupportedParams);
            }
        }
    }

    private static Set<String> findUnsupportedMappingParams(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidResponse ir) {
                var params = ir.getUnsupportedMappingParameters();
                if (!params.isEmpty()) {
                    return params;
                }
            }
        }
        return Set.of();
    }

    private void removeUnsupportedMappingParams(String templateName, ObjectNode templateBody, Set<String> params) {
        // Legacy templates: mappings at top level; index/component templates: mappings under "template"
        var mappings = templateBody.get("mappings");
        if (mappings == null) {
            var template = templateBody.get("template");
            if (template != null) {
                mappings = template.get("mappings");
            }
        }
        if (mappings != null && mappings.isObject()) {
            for (var param : params) {
                ((ObjectNode) mappings).remove(param);
            }
        }
        log.info("Reattempting creation of template '{}' after removing unsupported mapping parameters: {}", templateName, params);
    }
}
