package org.opensearch.migrations.bulkload.transformers;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    public List<IndexMetadata> transformIndexMetadata(IndexMetadata indexData) {
        return Stream.of(transformers)
                .reduce(
                    Stream.of(indexData),
                    (stream, transformer) -> stream.flatMap(data -> transformer.transformIndexMetadata(data).stream()),
                    Stream::concat
                )
                .collect(Collectors.toList());
    }
}
