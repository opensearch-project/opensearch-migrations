package org.opensearch.migrations.bulkload.pipeline.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * with configurable size, DELETE operations, custom routing, and reports parsing metrics.
 *
 * <p>Supports multiple indices via a list of {@link SourcelessExtractionConfig} entries.
 *
 * <p>Opt into this mode by setting {@link SourceType#SOURCELESS} in the pipeline configuration.
 */
@Slf4j
public class SourcelessDocumentSource implements DocumentSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<SourcelessExtractionConfig> configs;
    private final Map<String, SourcelessExtractionConfig> configByIndex;
    private final SourceExtractionMetrics metrics;

    public SourcelessDocumentSource(List<SourcelessExtractionConfig> configs, SourceExtractionMetrics metrics) {
        if (configs == null || configs.isEmpty()) {
            throw new IllegalArgumentException("configs must not be null or empty");
        }
        this.configs = List.copyOf(configs);
        this.configByIndex = this.configs.stream()
            .collect(Collectors.toMap(SourcelessExtractionConfig::indexName, Function.identity()));
        this.metrics = metrics != null ? metrics : SourceExtractionMetrics.NOOP;
        for (var config : this.configs) {
            log.info("Sourceless extraction: index={}, shards={}, docsPerShard={}, docSizeBytes={}, deleteRatio={}, routing={}",
                config.indexName(), config.shardCount(), config.docsPerShard(),
                config.docSizeBytes(), config.deleteRatio(), config.enableRouting());
        }
    }

    public SourcelessDocumentSource(SourcelessExtractionConfig config, SourceExtractionMetrics metrics) {
        this(List.of(config), metrics);
    }

    public SourcelessDocumentSource(SourcelessExtractionConfig config) {
        this(config, SourceExtractionMetrics.NOOP);
    }

    @Override
    public List<String> listIndices() {
        return configs.stream().map(SourcelessExtractionConfig::indexName).toList();
    }

    @Override
    public List<ShardId> listShards(String indexName) {
        var config = requireConfig(indexName);
        return IntStream.range(0, config.shardCount())
            .mapToObj(i -> new ShardId("sourceless", indexName, i))
            .toList();
    }

    @Override
    public IndexMetadataSnapshot readIndexMetadata(String indexName) {
        var config = requireConfig(indexName);
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("number_of_shards", config.shardCount());
        settings.put("number_of_replicas", 1);
        return new IndexMetadataSnapshot(indexName, config.shardCount(), 1,
            MAPPER.createObjectNode(), settings, MAPPER.createObjectNode());
    }

    @Override
    public Flux<DocumentChange> readDocuments(ShardId shardId, long startingDocOffset) {
        var config = requireConfig(shardId.indexName());
        int count = config.docsPerShard() - (int) startingDocOffset;
        if (count <= 0) {
            return Flux.empty();
        }

        metrics.recordShardExtractionStarted(SourceType.SOURCELESS, shardId.indexName(), shardId.shardNumber());
        final long startNanos = System.nanoTime();
        final int[] docCount = {0};
        int deleteThreshold = (int) (config.docsPerShard() * (1.0 - config.deleteRatio()));

        return Flux.range((int) startingDocOffset, count)
            .map(docNum -> {
                boolean isDelete = docNum >= deleteThreshold;
                byte[] body = isDelete ? null : generateDocumentBody(config, shardId, docNum);
                String routing = config.enableRouting()
                    ? "route-" + (docNum % 3)
                    : null;

                if (!isDelete) {
                    metrics.recordDocumentParsed(SourceType.SOURCELESS, body.length);
                }
                docCount[0]++;

                return new DocumentChange(
                    shardId.indexName() + "-" + shardId.shardNumber() + "-" + docNum,
                    null,
                    body,
                    routing,
                    isDelete ? DocumentChange.ChangeType.DELETE : DocumentChange.ChangeType.INDEX
                );
            })
            .doOnComplete(() -> {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                metrics.recordBatchReadDuration(SourceType.SOURCELESS, durationMs, docCount[0]);
                metrics.recordShardExtractionCompleted(
                    SourceType.SOURCELESS, shardId.indexName(), shardId.shardNumber(), docCount[0]);
            });
    }

    private SourcelessExtractionConfig requireConfig(String indexName) {
        var config = configByIndex.get(indexName);
        if (config == null) {
            throw new IllegalArgumentException("Unknown index: " + indexName);
        }
        return config;
    }

    private byte[] generateDocumentBody(SourcelessExtractionConfig config, ShardId shardId, int docNum) {
        if (config.docSizeBytes() <= 0) {
            String body = "{\"field\":\"value-" + docNum + "\",\"shard\":" + shardId.shardNumber() + "}";
            return body.getBytes(StandardCharsets.UTF_8);
        }
        String prefix = "{\"field\":\"value-" + docNum
            + "\",\"shard\":" + shardId.shardNumber()
            + ",\"payload\":\"";
        String suffix = "\"}";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        int paddingLen = Math.max(0, config.docSizeBytes() - prefixBytes.length - suffixBytes.length);
        byte[] result = new byte[prefixBytes.length + paddingLen + suffixBytes.length];
        System.arraycopy(prefixBytes, 0, result, 0, prefixBytes.length);
        for (int i = 0; i < paddingLen; i++) {
            result[prefixBytes.length + i] = (byte) ('a' + (i % 26));
        }
        System.arraycopy(suffixBytes, 0, result, prefixBytes.length + paddingLen, suffixBytes.length);
        return result;
    }
}
