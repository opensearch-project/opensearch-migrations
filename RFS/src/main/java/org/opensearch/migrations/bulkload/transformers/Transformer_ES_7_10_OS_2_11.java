package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

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
    private final int awarenessAttributes;

    public Transformer_ES_7_10_OS_2_11(int awarenessAttributes) {
        this.awarenessAttributes = awarenessAttributes;
    }

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata metaData) {
        ObjectNode root = metaData.toObjectNode().deepCopy();

        // Transform the legacy templates
        if (root.get(TEMPLATES_KEY_STR) != null) {
            ObjectNode templatesRoot =  metaData.getTemplates().deepCopy();
            templatesRoot.fieldNames().forEachRemaining(templateName -> {
                ObjectNode template = (ObjectNode) templatesRoot.get(templateName);
                log.atInfo().setMessage("Transforming template: {}").addArgument(templateName).log();
                log.atDebug().setMessage("Original template: {}").addArgument(template).log();
                TransformFunctions.removeIntermediateIndexSettingsLevel(template); // run before fixNumberOfReplicas
                TransformFunctions.removeIntermediateMappingsLevels(template);
                TransformFunctions.fixReplicasForDimensionality(templatesRoot, awarenessAttributes);
                log.atDebug().setMessage("Transformed template: {}").addArgument(template).log();
                templatesRoot.set(templateName, template);
            });
            root.set(TEMPLATES_KEY_STR, templatesRoot);
        }

        // Transform the index templates
        if (root.get(INDEX_TEMPLATE_KEY_STR) != null) {
            ObjectNode indexTemplateValuesRoot = metaData.getIndexTemplates().deepCopy();
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
                TransformFunctions.fixReplicasForDimensionality(templateSubRoot, awarenessAttributes);
                log.atDebug().setMessage("Transformed index template: {}").addArgument(template).log();
                indexTemplateValuesRoot.set(templateName, template);
            });
            ((ObjectNode) root.get(INDEX_TEMPLATE_KEY_STR)).set(INDEX_TEMPLATE_KEY_STR, indexTemplateValuesRoot);
        }

        // Transform the component templates
        if (root.get(COMPONENT_TEMPLATE_KEY_STR) != null) {
            ObjectNode componentTemplateValuesRoot = metaData.getComponentTemplates().deepCopy();
            componentTemplateValuesRoot.fieldNames().forEachRemaining(templateName -> {
                ObjectNode template = (ObjectNode) componentTemplateValuesRoot.get(templateName);
                ObjectNode templateSubRoot = (ObjectNode) template.get("template");

                if (templateSubRoot == null) {
                    return;
                }

                log.atInfo().setMessage("Transforming component template: {}").addArgument(templateName).log();
                log.atDebug().setMessage("Original component template: {}").addArgument(template).log();
                log.atDebug().setMessage("Transformed component template: {}").addArgument(template).log();
                componentTemplateValuesRoot.set(templateName, template);
            });
            ((ObjectNode) root.get(COMPONENT_TEMPLATE_KEY_STR)).set(COMPONENT_TEMPLATE_KEY_STR, componentTemplateValuesRoot);
        }

        return new GlobalMetadataData_OS_2_11(root);
    }

    @Override
    public List<IndexMetadata> transformIndexMetadata(IndexMetadata indexData) {
        log.atDebug().setMessage("Original Object: {}").addArgument(indexData::getRawJson).log();
        var copy = indexData.deepCopy();
        var newRoot = copy.getRawJson();

        TransformFunctions.removeIntermediateMappingsLevels(newRoot);

        newRoot.set("settings", TransformFunctions.convertFlatSettingsToTree((ObjectNode) newRoot.get("settings")));
        TransformFunctions.removeIntermediateIndexSettingsLevel(newRoot); // run before fixNumberOfReplicas
        TransformFunctions.fixReplicasForDimensionality(newRoot, awarenessAttributes);

        log.atDebug().setMessage("Transformed Object: {}").addArgument(newRoot).log();
        return List.of(copy);
    }
}
