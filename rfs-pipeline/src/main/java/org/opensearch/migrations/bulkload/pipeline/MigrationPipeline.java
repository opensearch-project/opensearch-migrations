package org.opensearch.migrations.bulkload.pipeline;

import java.util.Objects;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Wires a {@link DocumentSource} to a {@link DocumentSink} with batching and optional
 * parallel shard processing.
 *
 * <p>This is the core pipeline — it knows nothing about Lucene, snapshots, OpenSearch,
 * or any specific source/target. It moves {@link DocumentChange} records from source to sink
 * in batches, emitting {@link ProgressCursor} records for tracking.
 *
 * <h3>Concurrency model</h3>
 * <ul>
 *   <li>{@code shardConcurrency = 1}: shards are processed sequentially (default, safest)</li>
 *   <li>{@code shardConcurrency > 1}: up to N shards are processed in parallel</li>
 * </ul>
 * Within a single shard, batches are always sequential to preserve document ordering.
 */
@Slf4j
public class MigrationPipeline {

    private final DocumentSource source;
    private final DocumentSink sink;
    private final int maxDocsPerBatch;
    private final long maxBytesPerBatch;
    private final int shardConcurrency;

    /**
     * Create a pipeline with sequential shard processing.
     */
    public MigrationPipeline(DocumentSource source, DocumentSink sink, int maxDocsPerBatch, long maxBytesPerBatch) {
        this(source, sink, maxDocsPerBatch, maxBytesPerBatch, 1);
    }

    /**
     * Create a pipeline with configurable shard concurrency.
     *
     * @param source            the document source
     * @param sink              the document sink
     * @param maxDocsPerBatch   max documents per batch (must be >= 1)
     * @param maxBytesPerBatch  max bytes per batch (must be >= 1)
     * @param shardConcurrency  max shards to process in parallel (must be >= 1)
     */
    public MigrationPipeline(
        DocumentSource source,
        DocumentSink sink,
        int maxDocsPerBatch,
        long maxBytesPerBatch,
        int shardConcurrency
    ) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        if (maxDocsPerBatch < 1) {
            throw new IllegalArgumentException("maxDocsPerBatch must be >= 1, got " + maxDocsPerBatch);
        }
        if (maxBytesPerBatch < 1) {
            throw new IllegalArgumentException("maxBytesPerBatch must be >= 1, got " + maxBytesPerBatch);
        }
        if (shardConcurrency < 1) {
            throw new IllegalArgumentException("shardConcurrency must be >= 1, got " + shardConcurrency);
        }
        this.maxDocsPerBatch = maxDocsPerBatch;
        this.maxBytesPerBatch = maxBytesPerBatch;
        this.shardConcurrency = shardConcurrency;
    }

    /**
     * Migrate all documents for a single shard from source to sink.
     * Batches are processed sequentially to preserve document ordering.
     *
     * <p>The emitted {@link ProgressCursor} tracks cumulative document offset from
     * {@code startingDocOffset}, enabling resumability — a pipeline can restart from
     * the last cursor's {@code lastDocProcessed} value.
     *
     * @param shardId           the shard to migrate
     * @param indexName         the target index name
     * @param startingDocOffset the document offset to resume from (0 for start)
     * @return a Flux of progress cursors, one per batch written
     */
    public Flux<ProgressCursor> migrateShard(ShardId shardId, String indexName, int startingDocOffset) {
        log.info("Starting shard migration: {} from offset {}", shardId, startingDocOffset);
        final int[] cumulativeOffset = { startingDocOffset };
        return source.readDocuments(shardId, startingDocOffset)
            .bufferUntil(new BatchPredicate(maxDocsPerBatch, maxBytesPerBatch))
            .flatMapSequential(batch -> sink.writeBatch(shardId, indexName, batch)
                .map(cursor -> {
                    cumulativeOffset[0] += (int) cursor.docsInBatch();
                    return new ProgressCursor(
                        shardId,
                        cumulativeOffset[0],
                        cursor.docsInBatch(),
                        cursor.bytesInBatch()
                    );
                })
                .doOnNext(cursor -> log.debug(
                    "Batch written for {}: {} docs, {} bytes, cumulative offset {}",
                    shardId, cursor.docsInBatch(), cursor.bytesInBatch(), cursor.lastDocProcessed()
                ))
            )
            .onErrorMap(e -> !(e instanceof PipelineException),
                e -> new PipelineException("Failed migrating shard " + shardId, e))
            .doOnComplete(() -> log.info("Completed shard migration: {}", shardId));
    }

    /**
     * Migrate all shards for an index. Creates the index first, then migrates shards
     * with the configured concurrency.
     *
     * @param indexName the index to migrate
     * @return a Flux of progress cursors across all shards
     */
    public Flux<ProgressCursor> migrateIndex(String indexName) {
        log.info("Starting index migration: {} (concurrency={})", indexName, shardConcurrency);
        var metadata = source.readIndexMetadata(indexName);
        var shards = source.listShards(indexName);
        log.info("Index {} has {} shards", indexName, shards.size());

        return Flux.from(sink.createIndex(metadata))
            .thenMany(
                Flux.fromIterable(shards)
                    .flatMap(
                        shardId -> migrateShard(shardId, indexName, 0),
                        shardConcurrency
                    )
            )
            .doOnComplete(() -> log.info("Completed index migration: {}", indexName));
    }

    /**
     * Migrate all indices from source to sink.
     *
     * @return a Flux of progress cursors across all indices and shards
     */
    public Flux<ProgressCursor> migrateAll() {
        var indices = source.listIndices();
        log.info("Starting full migration: {} indices", indices.size());
        return Flux.fromIterable(indices)
            .concatMap(this::migrateIndex)
            .doOnComplete(() -> log.info("Full migration complete"));
    }

    /**
     * Batching predicate that groups documents by count and byte size.
     * Stateful — tracks current batch metrics and resets on batch boundary.
     *
     * <p>Used with {@link Flux#bufferUntil} to create batches that respect both
     * document count and byte size limits.
     */
    static class BatchPredicate implements java.util.function.Predicate<DocumentChange> {
        private final int maxDocs;
        private final long maxBytes;
        private int currentCount;
        private long currentBytes;

        BatchPredicate(int maxDocs, long maxBytes) {
            this.maxDocs = maxDocs;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean test(DocumentChange doc) {
            currentCount++;
            currentBytes += doc.source() != null ? doc.source().length : 0;

            if (currentCount >= maxDocs || currentBytes >= maxBytes) {
                currentCount = 0;
                currentBytes = 0;
                return true; // End of batch
            }
            return false;
        }
    }
}
