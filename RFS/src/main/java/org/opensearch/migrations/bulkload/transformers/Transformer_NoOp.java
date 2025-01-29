package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

public class Transformer_NoOp implements Transformer {

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata globalData) {
        return globalData;
    }

    @Override
    public List<IndexMetadata> transformIndexMetadata(IndexMetadata indexData) {
        return List.of(indexData);
    }
}
