package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Real {@link DocumentSource} adapter that reads documents from a Lucene snapshot
 * via {@link SnapshotExtractor}. Converts Lucene-specific types to pipeline IR.
 */
@Slf4j
public class LuceneSnapshotSource implements DocumentSource {

    private final SnapshotExtractor extractor;
    private final String snapshotName;
    private final Path workDir;
    private final Map<ShardId, SnapshotExtractor.ShardEntry> shardEntryCache = new ConcurrentHashMap<>();

    public LuceneSnapshotSource(SnapshotExtractor extractor, String snapshotName, Path workDir) {
        this.extractor = extractor;
        this.snapshotName = snapshotName;
        this.workDir = workDir;
    }

    @Override
    public List<String> listIndices() {
        return extractor.listIndices(snapshotName);
    }

    @Override
    public List<ShardId> listShards(String indexName) {
        var entries = extractor.listShards(snapshotName, indexName);
        return entries.stream()
            .map(entry -> {
                var shardId = new ShardId(snapshotName, indexName, entry.shardId());
                shardEntryCache.put(shardId, entry);
                return shardId;
            })
            .toList();
    }

    @Override
    public IndexMetadataSnapshot readIndexMetadata(String indexName) {
        var meta = extractor.getSnapshotReader().getIndexMetadata()
            .fromRepo(snapshotName, indexName);
        return IndexMetadataConverter.convert(indexName, meta);
    }

    @Override
    public Flux<DocumentChange> readDocuments(ShardId shardId, long startingDocOffset) {
        var entry = resolveShardEntry(shardId);
        if (entry == null) {
            return Flux.error(new IllegalArgumentException("Shard not found: " + shardId));
        }
        log.info("Reading documents from {} starting at offset {}", shardId, startingDocOffset);
        return extractor.readDocuments(entry, workDir)
            .skip(startingDocOffset)
            .map(LuceneAdapter::fromLucene);
    }

    private SnapshotExtractor.ShardEntry resolveShardEntry(ShardId shardId) {
        var entry = shardEntryCache.get(shardId);
        if (entry == null) {
            listShards(shardId.indexName());
            entry = shardEntryCache.get(shardId);
        }
        return entry;
    }

    @Override
    public void close() {
        shardEntryCache.clear();
    }
}
