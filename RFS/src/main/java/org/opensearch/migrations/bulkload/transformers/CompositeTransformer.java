package org.opensearch.migrations.bulkload.transformers;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

public class CompositeTransformer implements Transformer {
    private final Transformer[] transformers;

    public CompositeTransformer(Transformer... transformers) {
        this.transformers = transformers;
    }

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata globalData) {
        for (Transformer transformer : transformers) {
            globalData = transformer.transformGlobalMetadata(globalData);
        }
        return globalData;
    }

    @Override
    public IndexMetadata transformIndexMetadata(IndexMetadata indexData) {
        for (Transformer transformer : transformers) {
            indexData = transformer.transformIndexMetadata(indexData);
        }
        return indexData;
    }
}
