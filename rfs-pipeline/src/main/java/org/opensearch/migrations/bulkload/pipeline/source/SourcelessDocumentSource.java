package org.opensearch.migrations.bulkload.pipeline.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.metrics.SourceExtractionMetrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * A {@link DocumentSource} for sourceless extraction mode — generates synthetic documents
 * with configurable size and reports parsing metrics.
 *
 * <p>This extends the {@link SyntheticDocumentSource} concept with:
 * <ul>
 *   <li>Configurable document body size for realistic benchmarking</li>
 *   <li>Integrated {@link SourceExtractionMetrics} for tracking parsing performance</li>
 *   <li>Shard-level extraction lifecycle events</li>
 * </ul>
 *
 * <p>Opt into this mode by setting {@link SourceType#SOURCELESS} in the pipeline configuration.
 */
@Slf4j
public class SourcelessDocumentSource implements DocumentSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SourcelessExtractionConfig config;
    private final SourceExtractionMetrics metrics;

    public SourcelessDocumentSource(SourcelessExtractionConfig config, SourceExtractionMetrics metrics) {
        this.config = config;
        this.metrics = metrics != null ? metrics : SourceExtractionMetrics.NOOP;
        log.info("Sourceless extraction mode enabled: index={}, shards={}, docsPerShard={}, docSizeBytes={}",
            config.indexName(), config.shardCount(), config.docsPerShard(), config.docSizeBytes());
    }

    public SourcelessDocumentSource(SourcelessExtractionConfig config) {
        this(config, SourceExtractionMetrics.NOOP);
    }

    @Override
    public List<String> listIndices() {
        return List.of(config.indexName());
    }

    @Override
    public List<ShardId> listShards(String indexName) {
        return IntStream.range(0, config.shardCount())
            .mapToObj(i -> new ShardId("sourceless", indexName, i))
            .toList();
    }

    @Override
    public IndexMetadataSnapshot readIndexMetadata(String indexName) {
        ObjectNode mappings = MAPPER.createObjectNode();
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("number_of_shards", config.shardCount());
        settings.put("number_of_replicas", 1);
        ObjectNode aliases = MAPPER.createObjectNode();
        return new IndexMetadataSnapshot(indexName, config.shardCount(), 1, mappings, settings, aliases);
    }

    @Override
    public Flux<DocumentChange> readDocuments(ShardId shardId, long startingDocOffset) {
        int count = config.docsPerShard() - (int) startingDocOffset;
        if (count <= 0) {
            return Flux.empty();
        }

        metrics.recordShardExtractionStarted(SourceType.SOURCELESS, shardId.indexName(), shardId.shardNumber());
        final long startTime = System.currentTimeMillis();
        final int[] batchCount = {0};

        return Flux.range((int) startingDocOffset, count)
            .map(docNum -> {
                byte[] body = generateDocumentBody(shardId, docNum);
                metrics.recordDocumentParsed(SourceType.SOURCELESS, body.length);
                batchCount[0]++;
                return new DocumentChange(
                    shardId.indexName() + "-" + shardId.shardNumber() + "-" + docNum,
                    null,
                    body,
                    null,
                    DocumentChange.ChangeType.INDEX
                );
            })
            .doOnComplete(() -> {
                long duration = System.currentTimeMillis() - startTime;
                metrics.recordBatchReadDuration(SourceType.SOURCELESS, duration, batchCount[0]);
                metrics.recordShardExtractionCompleted(
                    SourceType.SOURCELESS, shardId.indexName(), shardId.shardNumber(), batchCount[0]);
            });
    }

    private byte[] generateDocumentBody(ShardId shardId, int docNum) {
        if (config.docSizeBytes() <= 0) {
            // Default small document
            String body = "{\"field\":\"value-" + docNum + "\",\"shard\":" + shardId.shardNumber() + "}";
            return body.getBytes(StandardCharsets.UTF_8);
        }
        // Generate a document body of approximately the requested size
        StringBuilder sb = new StringBuilder(config.docSizeBytes());
        sb.append("{\"field\":\"value-").append(docNum)
            .append("\",\"shard\":").append(shardId.shardNumber())
            .append(",\"payload\":\"");
        int remaining = config.docSizeBytes() - sb.length() - 2; // account for closing "}
        for (int i = 0; i < remaining; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        sb.append("\"}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
