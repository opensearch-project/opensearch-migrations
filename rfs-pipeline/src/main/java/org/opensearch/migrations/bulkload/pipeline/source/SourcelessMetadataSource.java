package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A {@link MetadataSource} for sourceless mode — generates synthetic metadata
 * from {@link SourcelessExtractionConfig} entries without any real snapshot.
 *
 * <p>Enables running {@link org.opensearch.migrations.bulkload.pipeline.MetadataMigrationPipeline}
 * in sourceless mode for end-to-end testing of the metadata write path.
 */
public class SourcelessMetadataSource implements MetadataSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<SourcelessExtractionConfig> configs;
    private final Map<String, SourcelessExtractionConfig> configByIndex;

    public SourcelessMetadataSource(SourcelessExtractionConfig config) {
        this(List.of(config));
    }

    public SourcelessMetadataSource(List<SourcelessExtractionConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            throw new IllegalArgumentException("configs must not be null or empty");
        }
        this.configs = List.copyOf(configs);
        this.configByIndex = this.configs.stream()
            .collect(Collectors.toMap(SourcelessExtractionConfig::indexName, Function.identity(),
                (a, b) -> { throw new IllegalArgumentException("Duplicate index name: " + a.indexName()); }));
    }

    @Override
    public GlobalMetadataSnapshot readGlobalMetadata() {
        List<String> indices = configs.stream().map(SourcelessExtractionConfig::indexName).toList();
        return new GlobalMetadataSnapshot(null, null, null, indices);
    }

    @Override
    public IndexMetadataSnapshot readIndexMetadata(String indexName) {
        var config = configByIndex.get(indexName);
        if (config == null) {
            throw new IllegalArgumentException("Unknown index: " + indexName);
        }

        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("number_of_shards", config.shardCount());
        settings.put("number_of_replicas", 1);

        return new IndexMetadataSnapshot(
            indexName, config.shardCount(), 1,
            MAPPER.createObjectNode(), settings, MAPPER.createObjectNode()
        );
    }
}
