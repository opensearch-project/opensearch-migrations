package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Real {@link DocumentSource} adapter that reads documents from a Lucene snapshot
 * via the existing {@link SnapshotExtractor}.
 *
 * <p>Converts Lucene-specific types to the clean pipeline IR, populating
 * {@link Document#hints()} and {@link Document#sourceMetadata()} via {@link LuceneAdapter}.
 *
 * <p>Supports optional delta mode: when {@code previousSnapshotName} and {@code deltaMode}
 * are set, reads delta changes between two snapshots.
 *
 * <p>Use {@link #builder(SnapshotExtractor, String, Path)} to construct instances.
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
    private final Map<EsShardPartition, SnapshotExtractor.ShardEntry> shardEntryCache = new HashMap<>();
    private final Map<EsShardPartition, SnapshotExtractor.ShardEntry> previousShardEntryCache = new HashMap<>();

    // Max shard size enforcement (0 = no limit)
    private final long maxShardSizeBytes;

    // When non-null, provides FieldMappingContext for indices with _source disabled
    private final Function<String, FieldMappingContext> sourcelessMappingContextProvider;

    // When true, treat _recovery_source as _source if present
    private final boolean useRecoverySource;

    private LuceneSnapshotSource(Builder builder) {
        this.extractor = builder.extractor;
        this.snapshotName = builder.snapshotName;
        this.workDir = builder.workDir;
        this.maxShardSizeBytes = builder.maxShardSizeBytes;
        this.previousSnapshotName = builder.previousSnapshotName;
        this.deltaMode = builder.deltaMode;
        this.deltaContextFactory = builder.deltaContextFactory;
        this.sourcelessMappingContextProvider = builder.sourcelessMappingContextProvider;
        this.useRecoverySource = builder.useRecoverySource;
    }

    public static Builder builder(SnapshotExtractor extractor, String snapshotName, Path workDir) {
        return new Builder(extractor, snapshotName, workDir);
    }

    public static class Builder {
        private final SnapshotExtractor extractor;
        private final String snapshotName;
        private final Path workDir;
        private long maxShardSizeBytes;
        private String previousSnapshotName;
        private DeltaMode deltaMode;
        private Supplier<IRfsContexts.IDeltaStreamContext> deltaContextFactory;
        private Function<String, FieldMappingContext> sourcelessMappingContextProvider;
        private boolean useRecoverySource;

        private Builder(SnapshotExtractor extractor, String snapshotName, Path workDir) {
            this.extractor = extractor;
            this.snapshotName = snapshotName;
            this.workDir = workDir;
        }

        public Builder maxShardSizeBytes(long maxShardSizeBytes) {
            this.maxShardSizeBytes = maxShardSizeBytes;
            return this;
        }

        public Builder delta(String previousSnapshotName, DeltaMode deltaMode,
                Supplier<IRfsContexts.IDeltaStreamContext> deltaContextFactory) {
            this.previousSnapshotName = previousSnapshotName;
            this.deltaMode = deltaMode;
            this.deltaContextFactory = deltaContextFactory;
            return this;
        }

        /**
         * When set, enables sourceless document reconstruction. The function receives
         * an index name and returns a FieldMappingContext for that index (or null if
         * the index has _source enabled and doesn't need reconstruction).
         */
        public Builder sourcelessMappingContextProvider(Function<String, FieldMappingContext> provider) {
            this.sourcelessMappingContextProvider = provider;
            return this;
        }

        public Builder useRecoverySource(boolean useRecoverySource) {
            this.useRecoverySource = useRecoverySource;
            return this;
        }

        public LuceneSnapshotSource build() {
            return new LuceneSnapshotSource(this);
        }
    }

    public boolean isDeltaMode() {
        return previousSnapshotName != null && deltaMode != null;
    }

    @Override
    public List<String> listCollections() {
        return extractor.listIndices(snapshotName);
    }

    @Override
    public List<Partition> listPartitions(String collectionName) {
        var entries = extractor.listShards(snapshotName, collectionName);
        var result = entries.stream()
            .map(entry -> {
                var partition = new EsShardPartition(snapshotName, collectionName, entry.shardId());
                shardEntryCache.put(partition, entry);
                return (Partition) partition;
            })
            .toList();

        // Pre-cache previous snapshot shard entries for delta mode
        if (isDeltaMode()) {
            try {
                var previousEntries = extractor.listShards(previousSnapshotName, collectionName);
                for (var entry : previousEntries) {
                    var partition = new EsShardPartition(snapshotName, collectionName, entry.shardId());
                    previousShardEntryCache.put(partition, entry);
                }
            } catch (Exception e) {
                log.warn("Could not list shards for previous snapshot {} collection {}: {}",
                    previousSnapshotName, collectionName, e.getMessage());
            }
        }

        return result;
    }

    @Override
    public CollectionMetadata readCollectionMetadata(String collectionName) {
        var indexMeta = readEsIndexMetadata(collectionName);
        return IndexMetadataConverter.toCollectionMetadata(indexMeta);
    }

    /**
     * Read ES-specific index metadata. Used internally and by the ES metadata migration pipeline.
     */
    public IndexMetadataSnapshot readEsIndexMetadata(String collectionName) {
        var meta = extractor.getSnapshotReader().getIndexMetadata()
            .fromRepo(snapshotName, collectionName);
        return IndexMetadataConverter.convert(collectionName, meta);
    }

    @Override
    public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
        var esPartition = (EsShardPartition) partition;
        var entry = resolveShardEntry(esPartition, shardEntryCache);
        if (entry == null) {
            return Flux.error(new IllegalArgumentException("Partition not found: " + partition));
        }

        // Enforce shard size limit to prevent disk overflow
        if (maxShardSizeBytes > 0) {
            long shardSize = entry.metadata().getTotalSizeBytes();
            if (shardSize > maxShardSizeBytes) {
                return Flux.error(new ShardTooLargeException(partition, shardSize, maxShardSizeBytes));
            }
        }

        if (isDeltaMode()) {
            var previousEntry = resolveShardEntry(esPartition, previousShardEntryCache);
            if (previousEntry == null) {
                log.info("No previous partition for {} — treating as full read (all additions)", partition);
                return readRegularDocuments(entry, partition, startingDocOffset);
            }
            log.info("Reading delta documents from {} (mode={}, offset={})", partition, deltaMode, startingDocOffset);
            return extractor.readDeltaDocuments(entry, previousEntry, deltaMode, workDir, deltaContextFactory)
                .skip(startingDocOffset)
                .map(LuceneAdapter::fromLucene);
        }

        return readRegularDocuments(entry, partition, startingDocOffset);
    }

    private Flux<Document> readRegularDocuments(
        SnapshotExtractor.ShardEntry entry, Partition partition, long startingDocOffset
    ) {
        log.info("Reading documents from {} starting at docIdx {}", partition, startingDocOffset);
        var esPartition = (EsShardPartition) partition;
        FieldMappingContext mappingContext = sourcelessMappingContextProvider != null
            ? sourcelessMappingContextProvider.apply(esPartition.indexName())
            : null;
        return extractor.readDocuments(entry, workDir, Math.toIntExact(startingDocOffset), mappingContext, useRecoverySource)
            .map(LuceneAdapter::fromLucene);
    }

    private SnapshotExtractor.ShardEntry resolveShardEntry(
        EsShardPartition partition, Map<EsShardPartition, SnapshotExtractor.ShardEntry> cache
    ) {
        var entry = cache.get(partition);
        if (entry == null) {
            listPartitions(partition.indexName());
            entry = cache.get(partition);
        }
        return entry;
    }

    @Override
    public void close() {
        shardEntryCache.clear();
        previousShardEntryCache.clear();
    }
}
