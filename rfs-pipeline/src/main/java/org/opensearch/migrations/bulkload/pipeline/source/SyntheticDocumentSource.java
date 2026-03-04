package org.opensearch.migrations.bulkload.pipeline.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;

/**
 * A synthetic {@link DocumentSource} for testing the writing side without any real snapshot.
 *
 * <p>This is the key enabler for N+M testing: target-side tests use this source
 * to produce known documents, then verify the target cluster state. No source cluster needed.
 *
 * <p>Document IDs follow the pattern {@code {indexName}-{shardNumber}-{docNumber}}.
 * Document bodies are JSON: {@code {"field":"value-{docNumber}","shard":{shardNumber}}}.
 */
public class SyntheticDocumentSource implements DocumentSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String indexName;
    private final int shardCount;
    private final int docsPerShard;

    /**
     * @param indexName    the index name to expose
     * @param shardCount   number of shards (must be >= 1)
     * @param docsPerShard documents per shard (must be >= 0)
     */
    public SyntheticDocumentSource(String indexName, int shardCount, int docsPerShard) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be >= 1");
        }
        if (docsPerShard < 0) {
            throw new IllegalArgumentException("docsPerShard must be >= 0");
        }
        this.indexName = indexName;
        this.shardCount = shardCount;
        this.docsPerShard = docsPerShard;
    }

    @Override
    public List<String> listIndices() {
        return List.of(indexName);
    }

    @Override
    public List<ShardId> listShards(String indexName) {
        return IntStream.range(0, shardCount)
            .mapToObj(i -> new ShardId("synthetic", indexName, i))
            .toList();
    }

    @Override
    public IndexMetadataSnapshot readIndexMetadata(String indexName) {
        ObjectNode mappings = MAPPER.createObjectNode();
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("number_of_shards", shardCount);
        settings.put("number_of_replicas", 1);
        ObjectNode aliases = MAPPER.createObjectNode();
        return new IndexMetadataSnapshot(indexName, shardCount, 1, mappings, settings, aliases);
    }

    @Override
    public Flux<DocumentChange> readDocuments(ShardId shardId, long startingDocOffset) {
        int count = docsPerShard - (int) startingDocOffset;
        if (count <= 0) {
            return Flux.empty();
        }
        return Flux.range((int) startingDocOffset, count)
            .map(docNum -> {
                String id = shardId.indexName() + "-" + shardId.shardNumber() + "-" + docNum;
                String body = "{\"field\":\"value-" + docNum + "\",\"shard\":" + shardId.shardNumber() + "}";
                return new DocumentChange(
                    id,
                    null,
                    body.getBytes(StandardCharsets.UTF_8),
                    null,
                    DocumentChange.ChangeType.INDEX
                );
            });
    }
}
