package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;
import org.opensearch.migrations.transformation.rules.TemplateMatchClausePattern;

public class Transformer_ES_5_6_to_OS_2_11 extends Transformer_ES_6_8_to_OS_2_11 {

    public Transformer_ES_5_6_to_OS_2_11(int awarenessAttributeDimensionality, MetadataTransformerParams params) {
        super(awarenessAttributeDimensionality, params);
        this.indexTransformations = List.of(
            new IndexMappingTypeRemoval(
                params.getMultiTypeResolutionBehavior()
            ),
            new TemplateMatchClausePattern()
        );
        this.indexTemplateTransformations = List.of(
            new IndexMappingTypeRemoval(
                params.getMultiTypeResolutionBehavior()
            ),
            new TemplateMatchClausePattern()
        );
    }    
}
