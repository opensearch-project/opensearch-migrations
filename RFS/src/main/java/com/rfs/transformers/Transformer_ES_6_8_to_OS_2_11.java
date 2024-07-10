package com.rfs.transformers;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.version_os_2_11.GlobalMetadataData_OS_2_11;
import com.rfs.version_os_2_11.IndexMetadataData_OS_2_11;

import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;

public class Transformer_ES_6_8_to_OS_2_11 implements Transformer {
    private static final Logger logger = LogManager.getLogger(Transformer_ES_6_8_to_OS_2_11.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final List<TransformationRule<Index>> indexTransformations = List.of(new IndexMappingTypeRemoval());
    private final List<TransformationRule<Index>> indexTemplateTransformations = List.of(new IndexMappingTypeRemoval());


    private int awarenessAttributeDimensionality;

    public Transformer_ES_6_8_to_OS_2_11(int awarenessAttributeDimensionality) {
        this.awarenessAttributeDimensionality = awarenessAttributeDimensionality;
    }

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata globalData) {
        var root = globalData.toObjectNode();
        ObjectNode newRoot = mapper.createObjectNode();

        // Transform the original "templates", but put them into the legacy "templates" bucket on the target
        var originalTemplates = root.get("templates");
        if (originalTemplates != null) {
            var templates = mapper.createObjectNode();
            originalTemplates.fieldNames().forEachRemaining(templateName -> {
                var templateCopy = (ObjectNode) originalTemplates.get(templateName).deepCopy();
                var indexTemplate = (Index) () -> templateCopy;
                transformIndex(indexTemplate, IndexType.Template);
                templates.set(templateName, indexTemplate.rawJson());
            });
            newRoot.set("templates", templates);
        }

        // Make empty index_templates
        ObjectNode indexTemplatesRoot = mapper.createObjectNode();
        ObjectNode indexTemplatesSubRoot = mapper.createObjectNode();
        indexTemplatesRoot.set("index_template", indexTemplatesSubRoot);
        newRoot.set("index_template", indexTemplatesRoot);

        // Make empty component_templates
        ObjectNode componentTemplatesRoot = mapper.createObjectNode();
        ObjectNode componentTemplatesSubRoot = mapper.createObjectNode();
        componentTemplatesRoot.set("component_template", componentTemplatesSubRoot);
        newRoot.set("component_template", componentTemplatesRoot);

        return new GlobalMetadataData_OS_2_11(newRoot);
    }

    @Override
    public IndexMetadata transformIndexMetadata(IndexMetadata index) {
        var copy = index.deepCopy();
        transformIndex(copy, IndexType.Concrete);
        return new IndexMetadataData_OS_2_11(copy.rawJson(), copy.getId(), copy.getName());
    }

    private void transformIndex(Index index, IndexType type) {
        logger.debug("Original Object: " + index.rawJson().toString());
        var newRoot = index.rawJson();

        switch (type) {
            case Concrete:
                indexTransformations.forEach(transformer -> transformer.applyTransformation(index));
                break;
            case Template:
                indexTemplateTransformations.forEach(transformer -> transformer.applyTransformation(index));
                break;
        }

        newRoot.set("settings", TransformFunctions.convertFlatSettingsToTree((ObjectNode) newRoot.get("settings")));
        TransformFunctions.removeIntermediateIndexSettingsLevel(newRoot); // run before fixNumberOfReplicas
        TransformFunctions.fixReplicasForDimensionality(newRoot, awarenessAttributeDimensionality);

        logger.debug("Transformed Object: " + newRoot.toString());
    }

    private enum IndexType {
        Concrete,
        Template;
    }
}
