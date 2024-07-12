package com.rfs.transformers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.version_os_2_11.GlobalMetadataData_OS_2_11;

public class Transformer_ES_7_10_OS_2_11 implements Transformer {
    private static final Logger logger = LogManager.getLogger(Transformer_ES_7_10_OS_2_11.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private int awarenessAttributeDimensionality;

    public Transformer_ES_7_10_OS_2_11(int awarenessAttributeDimensionality) {
        this.awarenessAttributeDimensionality = awarenessAttributeDimensionality;
    }

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata metaData) {
        ObjectNode root = metaData.toObjectNode().deepCopy();

        // Transform the legacy templates
        if (root.get("templates") != null) {
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
            root.set("templates", templatesRoot);
        }

        // Transform the index templates
        if (root.get("index_template") != null) {
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
                TransformFunctions.removeIntermediateIndexSettingsLevel(templateSubRoot); // run before
                                                                                          // fixNumberOfReplicas
                TransformFunctions.fixReplicasForDimensionality(templateSubRoot, awarenessAttributeDimensionality);
                logger.debug("Transformed template: " + template.toString());
                indexTemplateValuesRoot.set(templateName, template);
            });
            root.set("index_template", indexTemplatesRoot);
        }

        // Transform the component templates
        if (root.get("component_template") != null) {
            ObjectNode componentTemplatesRoot = (ObjectNode) root.get("component_template").deepCopy();
            ObjectNode componentTemplateValuesRoot = (ObjectNode) componentTemplatesRoot.get("component_template");
            componentTemplateValuesRoot.fieldNames().forEachRemaining(templateName -> {
                ObjectNode template = (ObjectNode) componentTemplateValuesRoot.get(templateName);
                ObjectNode templateSubRoot = (ObjectNode) template.get("template");

                if (templateSubRoot == null) {
                    return;
                }

                logger.info("Transforming template: " + templateName);
                logger.debug("Original template: " + template.toString());
                // No transformation needed for component templates
                logger.debug("Transformed template: " + template.toString());
                componentTemplateValuesRoot.set(templateName, template);
            });
            root.set("component_template", componentTemplatesRoot);
        }

        return new GlobalMetadataData_OS_2_11(root);
    }

    @Override
    public IndexMetadata transformIndexMetadata(IndexMetadata indexData) {
        logger.debug("Original Object: " + indexData.rawJson().toString());
        var copy = indexData.deepCopy();
        var newRoot = copy.rawJson();

        TransformFunctions.removeIntermediateMappingsLevels(newRoot);

        newRoot.set("settings", TransformFunctions.convertFlatSettingsToTree((ObjectNode) newRoot.get("settings")));
        TransformFunctions.removeIntermediateIndexSettingsLevel(newRoot); // run before fixNumberOfReplicas
        TransformFunctions.fixReplicasForDimensionality(newRoot, awarenessAttributeDimensionality);

        logger.debug("Transformed Object: " + newRoot.toString());
        return indexData;
    }
}
