package com.rfs.transformers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Transformer_ES_7_10_OS_2_11 implements Transformer {
    private static final Logger logger = LogManager.getLogger(Transformer_ES_7_10_OS_2_11.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private int awarenessAttributeDimensionality;

    public Transformer_ES_7_10_OS_2_11(int awarenessAttributeDimensionality) {
        this.awarenessAttributeDimensionality = awarenessAttributeDimensionality;
    }

    public ObjectNode transformGlobalMetadata(ObjectNode root){
        ObjectNode newRoot = mapper.createObjectNode();

        // Transform the legacy templates
        ObjectNode templatesRoot = (ObjectNode) root.get("templates").deepCopy();
        templatesRoot.fieldNames().forEachRemaining(templateName -> {
            ObjectNode template = (ObjectNode) templatesRoot.get(templateName);
            logger.info("Transforming template: " + templateName);
            logger.debug("Original template: " + template.toString());
            TransformFunctions.removeIntermediateIndexSettingsLevel(template); // run before fixNumberOfReplicas
            TransformFunctions.fixReplicasForDimensionality(templatesRoot, awarenessAttributeDimensionality);
            logger.debug("Transformed template: " + template.toString());
            templatesRoot.set(templateName, template);
        });
        newRoot.set("templates", templatesRoot);

        // Transform the index templates
        ObjectNode indexTemplatesRoot = (ObjectNode) root.get("index_template").deepCopy();
        ObjectNode indexTemplateValuesRoot = (ObjectNode) indexTemplatesRoot.get("index_template");
        indexTemplateValuesRoot.fieldNames().forEachRemaining(templateName -> {
            ObjectNode template = (ObjectNode) indexTemplateValuesRoot.get(templateName);
            ObjectNode templateSubRoot = (ObjectNode) template.get("template");

            if (templateSubRoot == null) {
                return;
            }

            logger.info("Transforming template: " + templateName);
            logger.debug("Original template: " + template.toString());
            TransformFunctions.removeIntermediateIndexSettingsLevel(templateSubRoot); // run before fixNumberOfReplicas
            TransformFunctions.fixReplicasForDimensionality(templateSubRoot, awarenessAttributeDimensionality);
            logger.debug("Transformed template: " + template.toString());
            indexTemplateValuesRoot.set(templateName, template);
        });
        newRoot.set("index_template", indexTemplatesRoot);

        // Transform the component templates
        ObjectNode componentTemplatesRoot = (ObjectNode) root.get("component_template").deepCopy();
        newRoot.set("component_template", componentTemplatesRoot);

        return newRoot;
    }

    public ObjectNode transformIndexMetadata(ObjectNode root){
        ObjectNode newRoot = root.deepCopy();

        TransformFunctions.removeIntermediateMappingsLevels(newRoot);

        newRoot.set("settings", TransformFunctions.convertFlatSettingsToTree((ObjectNode) newRoot.get("settings")));
        TransformFunctions.removeIntermediateIndexSettingsLevel(newRoot); // run before fixNumberOfReplicas
        TransformFunctions.fixReplicasForDimensionality(newRoot, awarenessAttributeDimensionality);

        logger.debug("Original Object: " + root.toString());
        logger.debug("Transformed Object: " + newRoot.toString());
        return newRoot;
    }
}
