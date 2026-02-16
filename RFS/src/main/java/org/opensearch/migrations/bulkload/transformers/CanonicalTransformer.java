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
 * Applies TransformationRules first (semantic transforms like type removal),
 * then normalizes structural quirks (flat→tree settings, type wrappers),
 * then applies target-specific fixes (replica dimensionality).
 */
@Slf4j
public class CanonicalTransformer implements Transformer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /** Convenience: no transformation rules (e.g. ES 7.10+ → OS) */
    public CanonicalTransformer(int awarenessAttributes) {
        this(awarenessAttributes, List.of(), List.of());
    }

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata globalData) {
        ObjectNode newRoot = MAPPER.createObjectNode();

        // Transform legacy templates
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

        // Transform index templates (ES 7.8+)
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

        // Transform component templates (ES 7.8+)
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

        // Apply semantic transformation rules (e.g. type removal)
        indexTransformations.forEach(rule -> rule.applyTransformation(copy));

        // Normalize structural quirks
        normalizeIndex(root);

        return List.of(new IndexMetadataData_OS_2_11(root, copy.getId(), copy.getName()));
    }

    /** Normalize settings (flat→tree, remove intermediate level) and mappings (strip type wrapper), then fix replicas */
    private void normalizeIndex(ObjectNode root) {
        // Normalize mappings — strip intermediate type wrapper
        TransformFunctions.removeIntermediateMappingsLevels(root);

        // Normalize settings — flat→tree, remove intermediate index level
        if (root.has("settings")) {
            root.set("settings", TransformFunctions.convertFlatSettingsToTree((ObjectNode) root.get("settings")));
        }
        TransformFunctions.removeIntermediateIndexSettingsLevel(root);

        // Target-specific: fix replica count for awareness dimensionality
        TransformFunctions.fixReplicasForDimensionality(root, awarenessAttributes);
    }

    /** Simple Index implementation for template transformation */
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
