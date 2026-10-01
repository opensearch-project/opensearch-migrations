package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

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
    private final Map<String, Set<String>> snapshotIndices = new HashMap<>();
    private final Set<String> loadedCollections = new HashSet<>();

    // Max shard size enforcement (0 = no limit)
    private final long maxShardSizeBytes;

    // When non-null, provides FieldMappingContext for indices with _source disabled
    private final Function<String, FieldMappingContext> sourcelessMappingContextProvider;

    // When true, treat _recovery_source as _source if present
    private final boolean useRecoverySource;

    private final LuceneAdapter luceneAdapter;

    // Unpacking waits for work on the shared bounded-elastic pool. Keep its caller on
    // a separately owned blocking scheduler, serializing initialization of the source caches.
    private final Scheduler initializationScheduler = Schedulers.newBoundedElastic(
        1, Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE, "snapshot-initialization", 60, true);


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
        var indices = new TreeSet<>(indicesInSnapshot(snapshotName));
        if (isDeltaMode()) {
            indices.addAll(indicesInSnapshot(previousSnapshotName));
        }
        return List.copyOf(indices);
    }

    private Set<String> indicesInSnapshot(String name) {
        return snapshotIndices.computeIfAbsent(name, snapshot -> {
            // Some repository providers return an empty index list for a nonexistent snapshot.
            // A missing baseline must never be mistaken for an empty baseline.
            if (isDeltaMode() && !extractor.listSnapshots().contains(snapshot)) {
                throw new IllegalArgumentException("Snapshot not found: " + snapshot);
            }
            return new TreeSet<>(extractor.listIndices(snapshot));
        });
    }

    @Override
    public List<Partition> listPartitions(String collectionName) {
        // Work-item shard numbers resolve by list position, including persisted sessions.
        var result = new TreeSet<EsShardPartition>(Comparator.comparingInt(EsShardPartition::shardNumber));
        if (indicesInSnapshot(snapshotName).contains(collectionName)) {
            if (isDeltaMode()) {
                extractor.validateDeltaSource(snapshotName, collectionName);
            }
            for (var entry : extractor.listShards(snapshotName, collectionName)) {
                var partition = new EsShardPartition(snapshotName, collectionName, entry.shardId());
                shardEntryCache.put(partition, entry);
                result.add(partition);
            }
        }

        if (isDeltaMode() && indicesInSnapshot(previousSnapshotName).contains(collectionName)) {
            extractor.validateDeltaSource(previousSnapshotName, collectionName);
            for (var entry : extractor.listShards(previousSnapshotName, collectionName)) {
                var partition = new EsShardPartition(snapshotName, collectionName, entry.shardId());
                previousShardEntryCache.put(partition, entry);
                result.add(partition);
            }
        }
        loadedCollections.add(collectionName);
        return List.copyOf(result);
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
        var metadataSnapshot = isDeltaMode() && !indicesInSnapshot(snapshotName).contains(collectionName)
            ? previousSnapshotName : snapshotName;
        var meta = extractor.getSnapshotReader().getIndexMetadata().fromRepo(metadataSnapshot, collectionName);
        return IndexMetadataConverter.convert(collectionName, meta);
    }

    @Override
    public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
        return Flux.defer(() -> readPartition(partition, startingDocOffset))
            .subscribeOn(initializationScheduler);
    }

    private Flux<Document> readPartition(Partition partition, long startingDocOffset) {
        var esPartition = (EsShardPartition) partition;
        var entry = resolveShardEntry(esPartition, shardEntryCache);
        var previousEntry = isDeltaMode() ? resolveShardEntry(esPartition, previousShardEntryCache) : null;
        if (entry == null && previousEntry == null) {
            return Flux.error(new IllegalArgumentException("Partition not found: " + partition));
        }

        // Delta readers hold both snapshots on disk at once.
        if (maxShardSizeBytes > 0) {
            long shardSize = (entry == null ? 0 : entry.metadata().getTotalSizeBytes())
                + (previousEntry == null ? 0 : previousEntry.metadata().getTotalSizeBytes());
            if (shardSize > maxShardSizeBytes) {
                return Flux.error(new ShardTooLargeException(partition, shardSize, maxShardSizeBytes));
            }
        }

        if (isDeltaMode()) {
            return readDeltaDocuments(entry, previousEntry, partition, startingDocOffset);
        }

        return readRegularDocuments(entry, partition, startingDocOffset);
    }

    private Flux<Document> readDeltaDocuments(
        SnapshotExtractor.ShardEntry entry, SnapshotExtractor.ShardEntry previousEntry,
        Partition partition, long startingDocOffset
    ) {
        if (previousEntry == null) {
            return deltaMode == DeltaMode.DELETES_ONLY ? Flux.empty()
                : readRegularDocuments(entry, partition, startingDocOffset);
        }
        if (entry == null) {
            return deltaMode == DeltaMode.UPDATES_ONLY ? Flux.empty()
                : readRegularDocuments(previousEntry, partition, startingDocOffset)
                    .map(doc -> new Document(doc.id(), doc.source(), Document.Operation.DELETE,
                        doc.hints(), doc.sourceMetadata()));
        }
        log.info("Reading delta documents from {} (mode={}, offset={})", partition, deltaMode, startingDocOffset);
        return extractor.readDeltaDocuments(entry, previousEntry, deltaMode, workDir, deltaContextFactory)
            .skip(startingDocOffset)
            .map(luceneAdapter::fromLucene);
    }

    private Flux<Document> readRegularDocuments(
        SnapshotExtractor.ShardEntry entry, Partition partition, long startingDocOffset
    ) {
        log.info("Reading documents from {} starting at docIdx {}", partition, startingDocOffset);
        var esPartition = (EsShardPartition) partition;
        FieldMappingContext mappingContext = sourcelessMappingContextProvider != null
            ? sourcelessMappingContextProvider.apply(esPartition.indexName())
            : null;
        // Pipeline checkpoints count emitted documents, not Lucene doc IDs (which have gaps
        // for deleted and nested documents). Use the same coordinate system on every retry.
        return extractor.readDocuments(entry, workDir, 0, mappingContext, useRecoverySource)
            .skip(startingDocOffset)
            .map(luceneAdapter::fromLucene);
    }

    private SnapshotExtractor.ShardEntry resolveShardEntry(
        EsShardPartition partition, Map<EsShardPartition, SnapshotExtractor.ShardEntry> cache
    ) {
        if (!loadedCollections.contains(partition.indexName())) {
            listPartitions(partition.indexName());
        }
        return cache.get(partition);
    }

    @Override
    public void close() {
        initializationScheduler.dispose();
        shardEntryCache.clear();
        previousShardEntryCache.clear();
        snapshotIndices.clear();
        loadedCollections.clear();
    }
}
