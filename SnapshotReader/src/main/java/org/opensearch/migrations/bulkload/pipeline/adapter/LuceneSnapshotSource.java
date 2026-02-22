package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Real {@link DocumentSource} adapter that reads documents from a Lucene snapshot
 * via the existing {@link SnapshotExtractor}.
 *
 * <p>Converts Lucene-specific types to the clean pipeline IR, dropping
 * {@code luceneDocNumber} in favor of offset-based progress tracking.
 *
 * <p>Supports optional delta mode: when {@code previousSnapshotName} and {@code deltaMode}
 * are set, reads delta changes (deletions first, then additions) between two snapshots.
 */
@Slf4j
public class LuceneSnapshotSource implements DocumentSource {

    private final SnapshotExtractor extractor;
    private final String snapshotName;
    private final Path workDir;

    // Delta configuration (null = regular mode)
    private final String previousSnapshotName;
    private final DeltaMode deltaMode;
    private final Supplier<IRfsContexts.IDeltaStreamContext> deltaContextFactory;

    /** Cache ShardEntry lookups to avoid repeated metadata reads */
    private final Map<ShardId, SnapshotExtractor.ShardEntry> shardEntryCache = new ConcurrentHashMap<>();
    private final Map<ShardId, SnapshotExtractor.ShardEntry> previousShardEntryCache = new ConcurrentHashMap<>();

    /** Regular (non-delta) constructor. */
    public LuceneSnapshotSource(SnapshotExtractor extractor, String snapshotName, Path workDir) {
        this(extractor, snapshotName, workDir, null, null, null);
    }

    /** Delta-aware constructor. */
    public LuceneSnapshotSource(
        SnapshotExtractor extractor,
        String snapshotName,
        Path workDir,
        String previousSnapshotName,
        DeltaMode deltaMode,
        Supplier<IRfsContexts.IDeltaStreamContext> deltaContextFactory
    ) {
        this.extractor = extractor;
        this.snapshotName = snapshotName;
        this.workDir = workDir;
        this.previousSnapshotName = previousSnapshotName;
        this.deltaMode = deltaMode;
        this.deltaContextFactory = deltaContextFactory;
    }

    public boolean isDeltaMode() {
        return previousSnapshotName != null && deltaMode != null;
    }

    @Override
    public List<String> listIndices() {
        return extractor.listIndices(snapshotName);
    }

    @Override
    public List<ShardId> listShards(String indexName) {
        var entries = extractor.listShards(snapshotName, indexName);
        var result = entries.stream()
            .map(entry -> {
                var shardId = new ShardId(snapshotName, indexName, entry.shardId());
                shardEntryCache.put(shardId, entry);
                return shardId;
            })
            .toList();

        // Pre-cache previous snapshot shard entries for delta mode
        if (isDeltaMode()) {
            try {
                var previousEntries = extractor.listShards(previousSnapshotName, indexName);
                for (var entry : previousEntries) {
                    var shardId = new ShardId(snapshotName, indexName, entry.shardId());
                    previousShardEntryCache.put(shardId, entry);
                }
            } catch (Exception e) {
                log.warn("Could not list shards for previous snapshot {} index {}: {}",
                    previousSnapshotName, indexName, e.getMessage());
            }
        }

        return result;
    }

    @Override
    public IndexMetadataSnapshot readIndexMetadata(String indexName) {
        var meta = extractor.getSnapshotReader().getIndexMetadata()
            .fromRepo(snapshotName, indexName);
        return IndexMetadataConverter.convert(indexName, meta);
    }

    @Override
    public Flux<DocumentChange> readDocuments(ShardId shardId, int startingDocOffset) {
        var entry = resolveShardEntry(shardId, shardEntryCache);
        if (entry == null) {
            return Flux.error(new IllegalArgumentException("Shard not found: " + shardId));
        }

        if (isDeltaMode()) {
            var previousEntry = resolveShardEntry(shardId, previousShardEntryCache);
            if (previousEntry == null) {
                log.info("No previous shard for {} — treating as full read (all additions)", shardId);
                return readRegularDocuments(entry, shardId, startingDocOffset);
            }
            log.info("Reading delta documents from {} (mode={}, offset={})", shardId, deltaMode, startingDocOffset);
            return extractor.readDeltaDocuments(entry, previousEntry, deltaMode, workDir, deltaContextFactory)
                .skip(startingDocOffset)
                .map(LuceneAdapter::fromLucene);
        }

        return readRegularDocuments(entry, shardId, startingDocOffset);
    }

    private Flux<DocumentChange> readRegularDocuments(
        SnapshotExtractor.ShardEntry entry, ShardId shardId, int startingDocOffset
    ) {
        log.info("Reading documents from {} starting at offset {}", shardId, startingDocOffset);
        return extractor.readDocuments(entry, workDir)
            .skip(startingDocOffset)
            .map(LuceneAdapter::fromLucene);
    }

    private SnapshotExtractor.ShardEntry resolveShardEntry(
        ShardId shardId, Map<ShardId, SnapshotExtractor.ShardEntry> cache
    ) {
        var entry = cache.get(shardId);
        if (entry == null) {
            listShards(shardId.indexName());
            entry = cache.get(shardId);
        }
        return entry;
    }

    @Override
    public void close() {
        shardEntryCache.clear();
        previousShardEntryCache.clear();
    }
}
