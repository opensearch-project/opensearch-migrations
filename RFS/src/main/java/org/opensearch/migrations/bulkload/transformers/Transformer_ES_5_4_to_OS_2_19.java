package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemovalWithMergedSupport;
import org.opensearch.migrations.transformation.rules.TemplateMatchClausePattern;

/**
 * Transformer for ES 5.0 to 5.4 (inclusive) that supports merged type mappings,
 * needed due to behavior differences in older versions where types were pre-flattened.
 */
public class Transformer_ES_5_4_to_OS_2_19 extends Transformer_ES_6_8_to_OS_2_11 {

    public Transformer_ES_5_4_to_OS_2_19(int awarenessAttributes, MetadataTransformerParams params) {
        super(
            awarenessAttributes,
            List.of(
                new IndexMappingTypeRemovalWithMergedSupport(params.getMultiTypeResolutionBehavior()),
                new TemplateMatchClausePattern()
            ),
            List.of(
                new IndexMappingTypeRemovalWithMergedSupport(params.getMultiTypeResolutionBehavior()),
                new TemplateMatchClausePattern()
            )
        );
    }
}
