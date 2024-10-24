package org.opensearch.migrations.bulkload.transformers;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Transformer_ES_7_10_OS_2_11 implements Transformer {
    public static final String INDEX_TEMPLATE_KEY_STR = "index_template";
    public static final String TEMPLATES_KEY_STR = "templates";
    public static final String COMPONENT_TEMPLATE_KEY_STR = "component_template";
    private final int awarenessAttributeDimensionality;

    public Transformer_ES_7_10_OS_2_11(int awarenessAttributeDimensionality) {
        this.awarenessAttributeDimensionality = awarenessAttributeDimensionality;
    }

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata metaData) {
        ObjectNode root = metaData.toObjectNode().deepCopy();

        // Transform the legacy templates
        if (root.get(TEMPLATES_KEY_STR) != null) {
            ObjectNode templatesRoot = (ObjectNode) root.get(TEMPLATES_KEY_STR).deepCopy();
            templatesRoot.fieldNames().forEachRemaining(templateName -> {
                ObjectNode template = (ObjectNode) templatesRoot.get(templateName);
                log.atInfo().setMessage("Transforming template: {}").addArgument(templateName).log();
                log.atDebug().setMessage("Original template: {}").addArgument(template).log();
                TransformFunctions.removeIntermediateIndexSettingsLevel(template); // run before fixNumberOfReplicas
                TransformFunctions.fixReplicasForDimensionality(templatesRoot, awarenessAttributeDimensionality);
                log.atDebug().setMessage("Transformed template: {}").addArgument(template).log();
                templatesRoot.set(templateName, template);
            });
            root.set(TEMPLATES_KEY_STR, templatesRoot);
        }

        // Transform the index templates
        if (root.get(INDEX_TEMPLATE_KEY_STR) != null) {
            ObjectNode indexTemplatesRoot = (ObjectNode) root.get(INDEX_TEMPLATE_KEY_STR).deepCopy();
            ObjectNode indexTemplateValuesRoot = (ObjectNode) indexTemplatesRoot.get(INDEX_TEMPLATE_KEY_STR);
            indexTemplateValuesRoot.fieldNames().forEachRemaining(templateName -> {
                ObjectNode template = (ObjectNode) indexTemplateValuesRoot.get(templateName);
                ObjectNode templateSubRoot = (ObjectNode) template.get("template");

                if (templateSubRoot == null) {
                    return;
                }

                log.atInfo().setMessage("Transforming index template: {}").addArgument(templateName).log();
                log.atDebug().setMessage("Original index template: {}").addArgument(template).log();
                TransformFunctions.removeIntermediateIndexSettingsLevel(templateSubRoot); // run before
                                                                                          // fixNumberOfReplicas
                TransformFunctions.fixReplicasForDimensionality(templateSubRoot, awarenessAttributeDimensionality);
                log.atDebug().setMessage("Transformed index template: {}").addArgument(template).log();
                indexTemplateValuesRoot.set(templateName, template);
            });
            root.set(INDEX_TEMPLATE_KEY_STR, indexTemplatesRoot);
        }

        // Transform the component templates
        if (root.get(COMPONENT_TEMPLATE_KEY_STR) != null) {
            ObjectNode componentTemplatesRoot = (ObjectNode) root.get(COMPONENT_TEMPLATE_KEY_STR).deepCopy();
            ObjectNode componentTemplateValuesRoot = (ObjectNode) componentTemplatesRoot.get(COMPONENT_TEMPLATE_KEY_STR);
            componentTemplateValuesRoot.fieldNames().forEachRemaining(templateName -> {
                ObjectNode template = (ObjectNode) componentTemplateValuesRoot.get(templateName);
                ObjectNode templateSubRoot = (ObjectNode) template.get("template");

                if (templateSubRoot == null) {
                    return;
                }

                log.atInfo().setMessage("Transforming component template: {}").addArgument(templateName).log();
                log.atDebug().setMessage("Original component template: {}").addArgument(template).log();
                // No transformation needed for component templates
                log.atDebug().setMessage("Transformed component template: {}").addArgument(template).log();
                componentTemplateValuesRoot.set(templateName, template);
            });
            root.set(COMPONENT_TEMPLATE_KEY_STR, componentTemplatesRoot);
        }

        return new GlobalMetadataData_OS_2_11(root);
    }

    @Override
    public IndexMetadata transformIndexMetadata(IndexMetadata indexData) {
        log.atDebug().setMessage("Original Object: {}").addArgument(indexData::getRawJson).log();
        var copy = indexData.deepCopy();
        var newRoot = copy.getRawJson();

        TransformFunctions.removeIntermediateMappingsLevels(newRoot);

        newRoot.set("settings", TransformFunctions.convertFlatSettingsToTree((ObjectNode) newRoot.get("settings")));
        TransformFunctions.removeIntermediateIndexSettingsLevel(newRoot); // run before fixNumberOfReplicas
        TransformFunctions.fixReplicasForDimensionality(newRoot, awarenessAttributeDimensionality);

        log.atDebug().setMessage("Transformed Object: {}").addArgument(newRoot).log();
        return copy;
    }
}
