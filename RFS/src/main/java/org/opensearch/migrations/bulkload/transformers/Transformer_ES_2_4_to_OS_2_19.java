package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11;
import org.opensearch.migrations.bulkload.version_os_2_11.IndexMetadataData_OS_2_11;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemovalWithMergedSupport;
import org.opensearch.migrations.transformation.rules.TemplateMatchClausePattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Transformer_ES_2_4_to_OS_2_19 implements Transformer {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final List<TransformationRule<Index>> indexTransformations;
    private final List<TransformationRule<Index>> indexTemplateTransformations;
    private final int awarenessAttributes;

    public Transformer_ES_2_4_to_OS_2_19(int awarenessAttributes, MetadataTransformerParams params) {
        this.awarenessAttributes = awarenessAttributes;
        log.atInfo().setMessage("Initialized Transformer_ES_2_4_to_OS_2_19 with awarenessAttributes={}")
            .addArgument(awarenessAttributes).log();
        this.indexTransformations = List.of(
            new IndexMappingTypeRemovalWithMergedSupport(params.getMultiTypeResolutionBehavior()),
            new TemplateMatchClausePattern()
        );
        this.indexTemplateTransformations = List.of(
            new IndexMappingTypeRemovalWithMergedSupport(params.getMultiTypeResolutionBehavior()),
            new TemplateMatchClausePattern()
        );
    }
 
    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata globalData) {
        ObjectNode newRoot = mapper.createObjectNode();

        // Transform the original "templates", but put them into the legacy "templates" bucket on the target
        var templatesRoot = globalData.getTemplates();
        if (templatesRoot != null) {
            var templates = mapper.createObjectNode();
            templatesRoot.properties().forEach(template -> {
                var templateCopy = (ObjectNode) template.getValue().deepCopy();
                var indexTemplate = new Index() {
                    @Override
                    public String getName() {
                        return template.getKey();
                    }

                    @Override
                    public ObjectNode getRawJson() {
                        return templateCopy;
                    }
                };

                try {
                    transformIndex(indexTemplate, IndexType.TEMPLATE);
                    templates.set(template.getKey(), indexTemplate.getRawJson());
                }  catch (Exception e) {
                    log.atError()
                        .setMessage("Unable to transform object: {}")
                        .addArgument(indexTemplate::getRawJson)
                        .setCause(e)
                        .log();
                    throw e;
                }
            });
            newRoot.set("templates", templates);
        }

        // Make empty index_templates section to match target structure
        ObjectNode indexTemplatesRoot = mapper.createObjectNode();
        ObjectNode indexTemplatesSubRoot = mapper.createObjectNode();
        indexTemplatesRoot.set("index_template", indexTemplatesSubRoot);
        newRoot.set("index_template", indexTemplatesRoot);

        // Make empty component_templates section to match target structure
        ObjectNode componentTemplatesRoot = mapper.createObjectNode();
        ObjectNode componentTemplatesSubRoot = mapper.createObjectNode();
        componentTemplatesRoot.set("component_template", componentTemplatesSubRoot);
        newRoot.set("component_template", componentTemplatesRoot);

        return new GlobalMetadataData_OS_2_11(newRoot);
    }

    @Override
    public List<IndexMetadata> transformIndexMetadata(IndexMetadata index) {
        var copy = index.deepCopy();
        transformIndex(copy, IndexType.CONCRETE);
        return List.of(new IndexMetadataData_OS_2_11(copy.getRawJson(), copy.getId(), copy.getName()));
    }

    private void transformIndex(Index index, IndexType type) {
        log.atDebug().setMessage("Original Object: {}").addArgument(index::getRawJson).log();
        var newRoot = index.getRawJson();

        switch (type) {
            case CONCRETE:
                indexTransformations.forEach(transformer -> transformer.applyTransformation(index));
                break;
            case TEMPLATE:
                indexTemplateTransformations.forEach(transformer -> transformer.applyTransformation(index));
                break;
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }

        
        newRoot.set("settings", TransformFunctions.convertFlatSettingsToTree((ObjectNode) newRoot.get("settings")));
        TransformFunctions.removeIntermediateIndexSettingsLevel(newRoot); // run before fixNumberOfReplicas
        TransformFunctions.fixReplicasForDimensionality(newRoot, awarenessAttributes);

        log.atDebug().setMessage("Transformed Object: {}").addArgument(newRoot).log();
    }

    private enum IndexType {
        CONCRETE,
        TEMPLATE;
    }

}
