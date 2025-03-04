package org.opensearch.migrations.bulkload.transformers;

import org.opensearch.migrations.transformation.rules.TemplateMatchClausePattern;

public class Transformer_ES_5_6_to_OS_2_11 extends Transformer_ES_6_8_to_OS_2_11 {

    public Transformer_ES_5_6_to_OS_2_11(int awarenessAttributeDimensionality, MetadataTransformerParams params) {
        super(awarenessAttributeDimensionality, params);
        
        indexTemplateTransformations.add(new TemplateMatchClausePattern());
    }    
}
