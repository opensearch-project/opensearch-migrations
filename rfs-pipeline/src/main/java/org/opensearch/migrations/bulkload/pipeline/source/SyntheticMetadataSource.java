package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A synthetic {@link MetadataSource} for testing the metadata writing side.
 *
 * <p>Produces deterministic metadata with configurable index names and template content.
 */
public class SyntheticMetadataSource implements MetadataSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> indexNames;
    private final int shardsPerIndex;

    /**
     * @param indexNames     the index names to expose
     * @param shardsPerIndex number of shards per index
     */
    public SyntheticMetadataSource(List<String> indexNames, int shardsPerIndex) {
        this.indexNames = List.copyOf(indexNames);
        this.shardsPerIndex = shardsPerIndex;
    }

    @Override
    public GlobalMetadataSnapshot readGlobalMetadata() {
        ObjectNode templates = MAPPER.createObjectNode();
        templates.putObject("my-template")
            .put("index_patterns", "test-*")
            .putObject("settings")
            .put("number_of_shards", shardsPerIndex);
        return new GlobalMetadataSnapshot(templates, null, null, indexNames);
    }

    @Override
    public IndexMetadataSnapshot readIndexMetadata(String indexName) {
        ObjectNode mappings = MAPPER.createObjectNode();
        mappings.putObject("properties")
            .putObject("field")
            .put("type", "text");

        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("number_of_shards", shardsPerIndex);
        settings.put("number_of_replicas", 1);

        ObjectNode aliases = MAPPER.createObjectNode();
        aliases.putObject(indexName + "-alias");

        return new IndexMetadataSnapshot(indexName, shardsPerIndex, 1, mappings, settings, aliases);
    }
}
