package org.opensearch.migrations.bulkload.transformers;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

public class FanOutCompositeTransformer implements Transformer {
    private final Transformer[] transformers;

    public FanOutCompositeTransformer(Transformer... transformers) {
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
    public List<IndexMetadata> transformIndexMetadata(IndexMetadata indexData) {
        var indexDataStream = Stream.of(indexData);
        for (Transformer transformer : transformers) {
            indexDataStream = indexDataStream.flatMap(data -> transformer.transformIndexMetadata(data).stream());
        }
        return indexDataStream.collect(Collectors.toList());
    }
}
