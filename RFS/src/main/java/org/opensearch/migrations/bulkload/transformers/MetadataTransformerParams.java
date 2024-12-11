package org.opensearch.migrations.bulkload.transformers;

import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;

public interface MetadataTransformerParams {
    IndexMappingTypeRemoval.MultiTypeResolutionBehavior getMultiTypeResolutionBehavior();
}
