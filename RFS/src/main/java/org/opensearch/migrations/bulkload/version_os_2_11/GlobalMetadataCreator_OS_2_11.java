package org.opensearch.migrations.bulkload.version_os_2_11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        return create(root, mode, context, List.of());
    }

    @Override
    public GlobalMetadataCreatorResults create(
        GlobalMetadata root,
        MigrationMode mode,
        IClusterMetadataContext context,
        List<String> serverlessCollections) {
        log.info("Setting Global Metadata");

        // Templates aren't tied to an index, so a collection-routed target gets them in every collection
        List<String> collections = serverlessCollections.isEmpty()
            ? Collections.singletonList(null)
            : serverlessCollections;
        var results = GlobalMetadataCreatorResults.builder();
        results.legacyTemplates(createLegacyTemplates(root, mode, context, collections));
        results.componentTemplates(createComponentTemplates(root, mode, context, collections));
        results.indexTemplates(createIndexTemplates(root, mode, context, collections));
        return results.build();
    }

    public List<CreationResult> createLegacyTemplates(GlobalMetadata metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createLegacyTemplates(metadata, mode, context, Collections.singletonList(null));
    }

    private List<CreationResult> createLegacyTemplates(GlobalMetadata metadata, MigrationMode mode,
                                                       IClusterMetadataContext context, List<String> collections) {
        return createTemplates(
            metadata.getTemplates(),
            legacyTemplateAllowlist,
            TemplateTypes.LEGACY_INDEX_TEMPLATE,
            mode,
            context,
            collections
        );
    }

    public List<CreationResult> createComponentTemplates(GlobalMetadata metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createComponentTemplates(metadata, mode, context, Collections.singletonList(null));
    }

    private List<CreationResult> createComponentTemplates(GlobalMetadata metadata, MigrationMode mode,
                                                          IClusterMetadataContext context, List<String> collections) {
        return createTemplates(
            metadata.getComponentTemplates(),
            componentTemplateAllowlist,
            TemplateTypes.COMPONENT_TEMPLATE,
            mode,
            context,
            collections
        );
    }

    public List<CreationResult> createIndexTemplates(GlobalMetadata metadata, MigrationMode mode, IClusterMetadataContext context) {
        return createIndexTemplates(metadata, mode, context, Collections.singletonList(null));
    }

    private List<CreationResult> createIndexTemplates(GlobalMetadata metadata, MigrationMode mode,
                                                      IClusterMetadataContext context, List<String> collections) {
        return createTemplates(
            metadata.getIndexTemplates(),
            indexTemplateAllowlist,
            TemplateTypes.INDEX_TEMPLATE,
            mode,
            context,
            collections
        );
    }

    @AllArgsConstructor
    enum TemplateTypes {
        INDEX_TEMPLATE(
            (targetClient, name, body, context, collection) ->
                targetClient.createIndexTemplate(name, body, context.createMigrateTemplateContext(), collection),
            OpenSearchClient::hasIndexTemplate,
            FilterScheme.FilterContext.INDEX_TEMPLATE
        ),

        LEGACY_INDEX_TEMPLATE(
            (targetClient, name, body, context, collection) ->
                targetClient.createLegacyTemplate(name, body, context.createMigrateLegacyTemplateContext(), collection),
            OpenSearchClient::hasLegacyTemplate,
            FilterScheme.FilterContext.LEGACY_INDEX_TEMPLATE
        ),

        COMPONENT_TEMPLATE(
            (targetClient, name, body, context, collection) ->
                targetClient.createComponentTemplate(name, body, context.createComponentTemplateContext(), collection),
            OpenSearchClient::hasComponentTemplate,
            FilterScheme.FilterContext.COMPONENT_TEMPLATE
        );
        final TemplateCreator creator;
        final TemplateExistsCheck alreadyExistsCheck;
        final FilterScheme.FilterContext filterContext;
    }

    @FunctionalInterface
    interface TemplateCreator {
        Optional<ObjectNode> createTemplate(OpenSearchClient client, String name, ObjectNode body,
                                            IClusterMetadataContext context, String serverlessCollection);
    }

    @FunctionalInterface
    interface TemplateExistsCheck {
        boolean templateAlreadyExists(OpenSearchClient client, String name, String serverlessCollection);
    }


    private List<CreationResult> createTemplates(
        ObjectNode templates,
        List<String> templateAllowlist,
        TemplateTypes templateType,
        MigrationMode mode,
        IClusterMetadataContext context,
        List<String> collections
    ) {

        log.info("Setting {} ...", templateType);

        if (templates == null) {
            log.info("No {} in Snapshot", templateType);
            return List.of();
        }

        var templatesToCreate = getAllTemplates(templates);

        return processTemplateCreation(templatesToCreate, templateType, templateAllowlist, mode, context, collections);
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
            IClusterMetadataContext context,
            List<String> collections
        ) {
        var skipCreation = FilterScheme.filterByAllowList(templateAllowList, templateType.filterContext).negate();

        var results = new ArrayList<CreationResult>();
        templatesToCreate.forEach((templateName, templateBody) -> {
            String[] problemSettings = { "settings.mapping.single_type", "settings.mapper.dynamic" };
            for (var field : problemSettings) {
                ObjectNodeUtils.removeFieldsByPath(templateBody, field);
            }

            if (skipCreation.test(templateName)) {
                log.atInfo().setMessage("Template {} was skipped due to allowlist filter {}").addArgument(templateName).addArgument(templateAllowList).log();
                results.add(CreationResult.builder().name(templateName).failureType(CreationFailureType.SKIPPED_DUE_TO_FILTER).build());
                return;
            }

            for (var collection : collections) {
                results.add(createTemplateInCollection(templateType, templateName, templateBody, mode, context, collection));
            }
        });
        return results;
    }

    private CreationResult createTemplateInCollection(
        TemplateTypes templateType,
        String templateName,
        ObjectNode templateBody,
        MigrationMode mode,
        IClusterMetadataContext context,
        String collection
    ) {
        var displayName = collection == null ? templateName : templateName + " (collection " + collection + ")";
        var creationResult = CreationResult.builder().name(displayName);

        log.info("Creating {}: {}", templateType, displayName);
        try {
            if (mode == MigrationMode.SIMULATE) {
                if (templateType.alreadyExistsCheck.templateAlreadyExists(client, templateName, collection)) {
                    creationResult.failureType(CreationFailureType.METADATA_ALREADY_EXISTS);
                    log.warn("Template {} already exists on the target, it will not be created during a migration", displayName);
                }
            } else if (mode == MigrationMode.PERFORM) {
                createTemplateWithRetry(templateType, templateName, templateBody, context, creationResult, collection);
            }
        } catch (Exception e) {
            creationResult.failureType(CreationFailureType.TARGET_CLUSTER_FAILURE);
            creationResult.exception(e);
        }
        return creationResult.build();
    }

    private void createTemplateWithRetry(
        TemplateTypes templateType,
        String templateName,
        ObjectNode templateBody,
        IClusterMetadataContext context,
        CreationResult.CreationResultBuilder creationResult,
        String collection
    ) {
        while (true) {
            try {
                var createdTemplate = templateType.creator.createTemplate(client, templateName, templateBody, context, collection);
                if (createdTemplate.isEmpty()) {
                    creationResult.failureType(CreationFailureType.METADATA_ALREADY_EXISTS);
                    log.warn("Template {} already exists on the target, unable to create", templateName);
                }
                return;
            } catch (Exception e) {
                var removedTokenFilters = findRemovedTokenFilters(e);
                if (!removedTokenFilters.isEmpty()) {
                    ObjectNodeUtils.removeAnalyzerFilters(templateBody, removedTokenFilters);
                    log.info("Reattempting creation of template '{}' after removing removed token filters: {}", templateName, removedTokenFilters);
                    continue;
                }
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

    private static Set<String> findRemovedTokenFilters(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidResponse ir) {
                var filters = ir.getRemovedTokenFilters();
                if (!filters.isEmpty()) {
                    return filters;
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
