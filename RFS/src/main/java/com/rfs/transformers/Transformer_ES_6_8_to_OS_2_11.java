package com.rfs.transformers;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.version_os_2_11.GlobalMetadataData_OS_2_11;

import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;

public class Transformer_ES_6_8_to_OS_2_11 implements Transformer {
    private static final Logger logger = LogManager.getLogger(Transformer_ES_6_8_to_OS_2_11.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final List<TransformationRule<Index>> indexTransformations = List.of(new IndexMappingTypeRemoval());
    private int awarenessAttributeDimensionality;

    public Transformer_ES_6_8_to_OS_2_11(int awarenessAttributeDimensionality) {
        this.awarenessAttributeDimensionality = awarenessAttributeDimensionality;
    }

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata globalData) {
        var root = globalData.toObjectNode();
        ObjectNode newRoot = mapper.createObjectNode();

        // Transform the original "templates", but put them into the legacy "templates" bucket on the target
        if (root.get("templates") != null) {
            ObjectNode templatesRoot = (ObjectNode) root.get("templates").deepCopy();
            templatesRoot.fieldNames().forEachRemaining(templateName -> {
                ObjectNode template = (ObjectNode) templatesRoot.get(templateName);
                logger.info("Transforming template: " + templateName);
                logger.debug("Original template: " + template.toString());
                TransformFunctions.removeIntermediateMappingsLevels(template);
                TransformFunctions.removeIntermediateIndexSettingsLevel(template); // run before fixNumberOfReplicas
                TransformFunctions.fixReplicasForDimensionality(template, awarenessAttributeDimensionality);
                logger.debug("Transformed template: " + template.toString());
                templatesRoot.set(templateName, template);
            });
            newRoot.set("templates", templatesRoot);
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
        logger.debug("Original Object: " + index.raw().toString());
        var copy = index.deepCopy();
        var newRoot = copy.raw();

        indexTransformations.forEach(transformer -> transformer.applyTransformation(index));

        TransformFunctions.removeIntermediateMappingsLevels(newRoot);

        newRoot.set("settings", TransformFunctions.convertFlatSettingsToTree((ObjectNode) newRoot.get("settings")));
        TransformFunctions.removeIntermediateIndexSettingsLevel(newRoot); // run before fixNumberOfReplicas
        TransformFunctions.fixReplicasForDimensionality(newRoot, awarenessAttributeDimensionality);

        logger.debug("Transformed Object: " + newRoot.toString());
        return copy;
    }    
}
