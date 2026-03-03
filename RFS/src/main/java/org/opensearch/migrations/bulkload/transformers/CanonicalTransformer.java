package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11;
import org.opensearch.migrations.bulkload.version_os_2_11.IndexMetadataData_OS_2_11;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Single unified transformer that replaces all version-specific transformers.
 * Applies TransformationRules first, then normalizes structural quirks,
 * then applies target-specific fixes.
 */
@Slf4j
public class CanonicalTransformer implements Transformer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SETTINGS_KEY = "settings";

    private final int awarenessAttributes;
    private final List<TransformationRule<Index>> indexTransformations;
    private final List<TransformationRule<Index>> indexTemplateTransformations;

    public CanonicalTransformer(
            int awarenessAttributes,
            List<TransformationRule<Index>> indexTransformations,
            List<TransformationRule<Index>> indexTemplateTransformations) {
        this.awarenessAttributes = awarenessAttributes;
        this.indexTransformations = indexTransformations;
        this.indexTemplateTransformations = indexTemplateTransformations;
    }

    public CanonicalTransformer(int awarenessAttributes) {
        this(awarenessAttributes, List.of(), List.of());
    }

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata globalData) {
        ObjectNode newRoot = MAPPER.createObjectNode();

        var templatesRoot = globalData.getTemplates();
        if (templatesRoot != null) {
            var templates = MAPPER.createObjectNode();
            templatesRoot.properties().forEach(entry -> {
                var templateCopy = (ObjectNode) entry.getValue().deepCopy();
                var indexTemplate = new InlineIndex(entry.getKey(), templateCopy);
                indexTemplateTransformations.forEach(rule -> rule.applyTransformation(indexTemplate));
                normalizeIndex(indexTemplate.getRawJson());
                templates.set(entry.getKey(), indexTemplate.getRawJson());
            });
            newRoot.set("templates", templates);
        }

        var indexTemplatesRoot = globalData.getIndexTemplates();
        ObjectNode indexTemplatesOut = MAPPER.createObjectNode();
        if (indexTemplatesRoot != null && !indexTemplatesRoot.isEmpty()) {
            indexTemplatesRoot.fieldNames().forEachRemaining(name -> {
                var template = (ObjectNode) indexTemplatesRoot.get(name).deepCopy();
                var templateSubRoot = (ObjectNode) template.get("template");
                if (templateSubRoot != null) {
                    TransformFunctions.removeIntermediateIndexSettingsLevel(templateSubRoot);
                    TransformFunctions.fixReplicasForDimensionality(templateSubRoot, awarenessAttributes);
                }
                indexTemplatesOut.set(name, template);
            });
        }
        ObjectNode indexTemplateWrapper = MAPPER.createObjectNode();
        indexTemplateWrapper.set("index_template", indexTemplatesOut);
        newRoot.set("index_template", indexTemplateWrapper);

        var componentTemplatesRoot = globalData.getComponentTemplates();
        ObjectNode componentTemplatesOut = MAPPER.createObjectNode();
        if (componentTemplatesRoot != null && !componentTemplatesRoot.isEmpty()) {
            componentTemplatesRoot.fieldNames().forEachRemaining(name -> {
                componentTemplatesOut.set(name, componentTemplatesRoot.get(name).deepCopy());
            });
        }
        ObjectNode componentTemplateWrapper = MAPPER.createObjectNode();
        componentTemplateWrapper.set("component_template", componentTemplatesOut);
        newRoot.set("component_template", componentTemplateWrapper);

        return new GlobalMetadataData_OS_2_11(newRoot);
    }

    @Override
    public List<IndexMetadata> transformIndexMetadata(IndexMetadata index) {
        var copy = index.deepCopy();
        var root = copy.getRawJson();

        indexTransformations.forEach(rule -> rule.applyTransformation(copy));
        normalizeIndex(root);

        return List.of(new IndexMetadataData_OS_2_11(root, copy.getId(), copy.getName()));
    }

    private void normalizeIndex(ObjectNode root) {
        TransformFunctions.removeIntermediateMappingsLevels(root);
        if (root.has(SETTINGS_KEY)) {
            root.set(SETTINGS_KEY, TransformFunctions.convertFlatSettingsToTree((ObjectNode) root.get(SETTINGS_KEY)));
        }
        TransformFunctions.removeIntermediateIndexSettingsLevel(root);
        TransformFunctions.fixReplicasForDimensionality(root, awarenessAttributes);
    }

    private static class InlineIndex implements Index {
        private final String name;
        private final ObjectNode rawJson;

        InlineIndex(String name, ObjectNode rawJson) {
            this.name = name;
            this.rawJson = rawJson;
        }

        @Override
        public String getName() { return name; }

        @Override
        public ObjectNode getRawJson() { return rawJson; }
    }
}
