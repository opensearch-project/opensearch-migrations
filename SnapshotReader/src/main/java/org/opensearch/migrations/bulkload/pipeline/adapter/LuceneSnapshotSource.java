package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Real {@link DocumentSource} adapter that reads documents from a Lucene snapshot
 * via the existing {@link SnapshotExtractor}.
 *
 * <p>Converts Lucene-specific types to the clean pipeline IR, dropping
 * {@code luceneDocNumber} in favor of offset-based progress tracking.
 */
@Slf4j
public class LuceneSnapshotSource implements DocumentSource {

    private final SnapshotExtractor extractor;
    private final String snapshotName;
    private final Path workDir;
    /** Cache ShardEntry lookups to avoid repeated metadata reads */
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
        return extractor.listShards(snapshotName, indexName).stream()
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
    public Flux<DocumentChange> readDocuments(ShardId shardId, int startingDocOffset) {
        var entry = shardEntryCache.get(shardId);
        if (entry == null) {
            // Populate cache if not already done
            listShards(shardId.indexName());
            entry = shardEntryCache.get(shardId);
        }
        if (entry == null) {
            return Flux.error(new IllegalArgumentException("Shard not found: " + shardId));
        }

        log.info("Reading documents from {} starting at offset {}", shardId, startingDocOffset);
        return extractor.readDocuments(entry, workDir)
            .skip(startingDocOffset)
            .map(LuceneAdapter::fromLucene);
    }

    @Override
    public void close() {
        shardEntryCache.clear();
    }
}
