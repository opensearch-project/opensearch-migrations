package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;
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
 * <h3>Cursors</h3>
 * The two read modes count in different units, so each tags its cursor with the mode that
 * produced it:
 * <ul>
 *   <li>{@code lucene:<n>} — the Lucene doc index to resume at. A regular read seeks straight to
 *       the containing segment, and the index counts deleted docs, so it is <em>not</em> a count
 *       of emitted documents.</li>
 *   <li>{@code delta:<n>} — the number of delta records already emitted. The delta reader has no
 *       seekable position, so resuming skips that many records.</li>
 * </ul>
 * A cursor recorded in one mode is rejected in the other rather than being silently misread as
 * the wrong unit.
 *
 * <p>Use {@link #builder(SnapshotExtractor, String, Path)} to construct instances.
 */
@Slf4j
public class LuceneSnapshotSource implements DocumentSource {

    private static final String REGULAR_CURSOR_PREFIX = "lucene:";
    private static final String DELTA_CURSOR_PREFIX = "delta:";

    private final SnapshotExtractor extractor;
    private final String snapshotName;
    private final Path workDir;

    // Delta configuration (null = regular mode)
    private final String previousSnapshotName;
    private final DeltaMode deltaMode;
    private final Supplier<IRfsContexts.IDeltaStreamContext> deltaContextFactory;

    /** Cache ShardEntry lookups to avoid repeated metadata reads; concurrent because a source may
     *  be subscribed for several partitions at once. */
    private final Map<EsShardPartition, SnapshotExtractor.ShardEntry> shardEntryCache = new ConcurrentHashMap<>();
    private final Map<EsShardPartition, SnapshotExtractor.ShardEntry> previousShardEntryCache = new ConcurrentHashMap<>();

    // Max shard size enforcement (0 = no limit)
    private final long maxShardSizeBytes;

    // When non-null, provides FieldMappingContext for indices with _source disabled
    private final Function<String, FieldMappingContext> sourcelessMappingContextProvider;

    // When true, treat _recovery_source as _source if present
    private final boolean useRecoverySource;

    private final LuceneAdapter luceneAdapter;


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
        this.luceneAdapter = new LuceneAdapter(builder.emitDocType);
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
        private boolean emitDocType;

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

        public Builder emitDocType(boolean emitDocType) {
            this.emitDocType = emitDocType;
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
    public Optional<Partition> findPartition(String collectionName, String partitionName) {
        return listPartitions(collectionName).stream()
            .filter(p -> p.name().equals(partitionName))
            .findFirst();
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
    public Flux<PositionedDocument> readDocuments(Partition partition, String startingCursor) {
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
                return readRegularDocuments(entry, partition, startingCursor);
            }
            long alreadyEmitted = parseCursor(startingCursor, DELTA_CURSOR_PREFIX, partition);
            log.info("Reading delta documents from {} (mode={}, skipping={})", partition, deltaMode, alreadyEmitted);
            // The counter lives inside the defer so each subscription starts it over and replays
            // the same cursors.
            return Flux.defer(() -> {
                var emitted = new AtomicLong(alreadyEmitted);
                return extractor.readDeltaDocuments(entry, previousEntry, deltaMode, workDir, deltaContextFactory)
                    .skip(alreadyEmitted)
                    .map(luceneDoc -> new PositionedDocument(
                        luceneAdapter.fromLucene(luceneDoc),
                        DELTA_CURSOR_PREFIX + emitted.incrementAndGet()));
            });
        }

        return readRegularDocuments(entry, partition, startingCursor);
    }

    private Flux<PositionedDocument> readRegularDocuments(
        SnapshotExtractor.ShardEntry entry, Partition partition, String startingCursor
    ) {
        long startDocIdx = parseCursor(startingCursor, REGULAR_CURSOR_PREFIX, partition);
        log.info("Reading documents from {} starting at docIdx {}", partition, startDocIdx);
        var esPartition = (EsShardPartition) partition;
        FieldMappingContext mappingContext = sourcelessMappingContextProvider != null
            ? sourcelessMappingContextProvider.apply(esPartition.indexName())
            : null;
        return extractor.readDocuments(entry, workDir, Math.toIntExact(startDocIdx), mappingContext, useRecoverySource)
            // Resume at the doc after this one; the read starts *at* the index it is given.
            .map(luceneDoc -> new PositionedDocument(
                luceneAdapter.fromLucene(luceneDoc),
                REGULAR_CURSOR_PREFIX + (luceneDoc.getLuceneDocNumber() + 1L)));
    }

    private static long parseCursor(String cursor, String expectedPrefix, Partition partition) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        if (!cursor.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("Cursor '" + cursor + "' for partition " + partition
                + " was not produced by this read mode (expected prefix '" + expectedPrefix + "'). "
                + "Delta and regular reads count in different units and their cursors are not interchangeable.");
        }
        try {
            return Long.parseLong(cursor.substring(expectedPrefix.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed cursor '" + cursor + "' for partition " + partition, e);
        }
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
