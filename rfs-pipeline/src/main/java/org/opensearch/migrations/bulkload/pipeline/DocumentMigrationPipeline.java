package org.opensearch.migrations.bulkload.pipeline;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.pipeline.ir.Document;
import org.opensearch.migrations.bulkload.pipeline.ir.Partition;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Wires a {@link DocumentSource} to a {@link DocumentSink} with batching and optional
 * parallel partition processing.
 *
 * <p>This is the core pipeline — it knows nothing about Lucene, snapshots, OpenSearch,
 * or any specific source/target. It moves {@link Document} records from source to sink
 * in batches, emitting {@link ProgressCursor} records for tracking.
 *
 * <h3>Concurrency model</h3>
 * <ul>
 *   <li>{@code partitionConcurrency = 1}: partitions are processed sequentially (default, safest)</li>
 *   <li>{@code partitionConcurrency > 1}: up to N partitions are processed in parallel</li>
 *   <li>{@code batchConcurrency}: max bulk write requests in flight per partition (default 10).
 *       Higher values improve throughput by overlapping network I/O with batch preparation.</li>
 * </ul>
 * Within a single partition, batch results are emitted in order (via {@code flatMapSequential})
 * even when multiple writes are in flight.
 */
@Slf4j
public class DocumentMigrationPipeline {

    private static final long PROGRESS_LOG_INTERVAL_MS = 30_000;

    private final DocumentSource source;
    private final DocumentSink sink;
    private final int maxDocsPerBatch;
    private final long maxBytesPerBatch;
    private final int partitionConcurrency;
    private final int batchConcurrency;

    /**
     * Create a pipeline with sequential partition processing and default batch concurrency.
     */
    public DocumentMigrationPipeline(DocumentSource source, DocumentSink sink, int maxDocsPerBatch, long maxBytesPerBatch) {
        this(source, sink, maxDocsPerBatch, maxBytesPerBatch, 1, 10);
    }

    /**
     * Create a pipeline with configurable concurrency.
     *
     * @param source               the document source
     * @param sink                 the document sink
     * @param maxDocsPerBatch      max documents per batch (must be >= 1)
     * @param maxBytesPerBatch     max bytes per batch (must be >= 1)
     * @param partitionConcurrency max partitions to process in parallel (must be >= 1)
     * @param batchConcurrency     max bulk write requests in flight per partition (must be >= 1)
     */
    public DocumentMigrationPipeline(
        DocumentSource source,
        DocumentSink sink,
        int maxDocsPerBatch,
        long maxBytesPerBatch,
        int partitionConcurrency,
        int batchConcurrency
    ) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        if (maxDocsPerBatch < 1) {
            throw new IllegalArgumentException("maxDocsPerBatch must be >= 1, got " + maxDocsPerBatch);
        }
        if (maxBytesPerBatch < 1) {
            throw new IllegalArgumentException("maxBytesPerBatch must be >= 1, got " + maxBytesPerBatch);
        }
        if (partitionConcurrency < 1) {
            throw new IllegalArgumentException("partitionConcurrency must be >= 1, got " + partitionConcurrency);
        }
        if (batchConcurrency < 1) {
            throw new IllegalArgumentException("batchConcurrency must be >= 1, got " + batchConcurrency);
        }
        this.maxDocsPerBatch = maxDocsPerBatch;
        this.maxBytesPerBatch = maxBytesPerBatch;
        this.partitionConcurrency = partitionConcurrency;
        this.batchConcurrency = batchConcurrency;
    }

    /**
     * Migrate all documents for a single partition from source to sink.
     *
     * @param partition         the partition to migrate
     * @param collectionName    the target collection name
     * @param startingDocOffset the document offset to resume from (0 for start)
     * @return a Flux of progress cursors, one per batch written
     */
    public Flux<ProgressCursor> migratePartition(Partition partition, String collectionName, long startingDocOffset) {
        log.info("Starting partition migration: {} from offset {} (batchConcurrency={})", partition, startingDocOffset, batchConcurrency);
        final long[] cumulativeOffset = { startingDocOffset };
        final long[] cumulativeBytes = { 0 };
        final long[] lastLogTime = { System.currentTimeMillis() };
        final AtomicInteger activeBatches = new AtomicInteger(0);
        return source.readDocuments(partition, startingDocOffset)
            .subscribeOn(Schedulers.boundedElastic())
            .bufferUntil(new BatchPredicate(maxDocsPerBatch, maxBytesPerBatch))
            .flatMapSequential(batch -> {
                long batchStart = System.nanoTime();
                int batchDocs = batch.size();
                long batchBytes = batch.stream().mapToLong(Document::sourceLength).sum();
                int inflight = activeBatches.incrementAndGet();
                return sink.writeBatch(collectionName, batch)
                    .map(result -> {
                        cumulativeOffset[0] += result.docsInBatch();
                        cumulativeBytes[0] += result.bytesInBatch();
                        return new ProgressCursor(
                            partition,
                            cumulativeOffset[0],
                            result.docsInBatch(),
                            result.bytesInBatch()
                        );
                    })
                    .doOnNext(cursor -> {
                        int remaining = activeBatches.decrementAndGet();
                        long totalMs = (System.nanoTime() - batchStart) / 1_000_000;
                        long now = System.currentTimeMillis();
                        if (now - lastLogTime[0] >= PROGRESS_LOG_INTERVAL_MS) {
                            lastLogTime[0] = now;
                            log.info("{} batch: {}ms, {} docs, {} KB, active {}/{} | progress: {} docs, {} MB total",
                                partition, totalMs, batchDocs, batchBytes / 1024,
                                remaining + 1, batchConcurrency,
                                cumulativeOffset[0], cumulativeBytes[0] / (1024 * 1024));
                        }
                    })
                    .doOnError(e -> activeBatches.decrementAndGet());
            }, batchConcurrency)
            .onErrorMap(e -> !(e instanceof PipelineException),
                e -> new PipelineException("Failed migrating partition " + partition, e))
            .doOnComplete(() -> log.info("Completed partition migration: {} — {} docs, {} MB total",
                partition, cumulativeOffset[0], cumulativeBytes[0] / (1024 * 1024)));
    }

    /**
     * Migrate all partitions for a collection. Creates the collection first, then migrates
     * partitions with the configured concurrency.
     *
     * @param collectionName the collection to migrate
     * @return a Flux of progress cursors across all partitions
     */
    public Flux<ProgressCursor> migrateCollection(String collectionName) {
        log.info("Starting collection migration: {} (concurrency={})", collectionName, partitionConcurrency);
        var metadata = source.readCollectionMetadata(collectionName);
        var partitions = source.listPartitions(collectionName);
        log.info("Collection {} has {} partitions", collectionName, partitions.size());

        return Flux.from(sink.createCollection(metadata))
            .thenMany(
                Flux.fromIterable(partitions)
                    .flatMap(
                        partition -> migratePartition(partition, collectionName, 0),
                        partitionConcurrency
                    )
            )
            .doOnComplete(() -> log.info("Completed collection migration: {}", collectionName));
    }

    /**
     * Migrate all collections from source to sink.
     *
     * @return a Flux of progress cursors across all collections and partitions
     */
    public Flux<ProgressCursor> migrateAll() {
        var collections = source.listCollections();
        log.info("Starting full migration: {} collections", collections.size());
        return Flux.fromIterable(collections)
            .concatMap(this::migrateCollection)
            .doOnComplete(() -> log.info("Full migration complete"));
    }

    /**
     * Batching predicate that groups documents by count and byte size.
     */
    static class BatchPredicate implements java.util.function.Predicate<Document> {
        private final int maxDocs;
        private final long maxBytes;
        private int currentCount;
        private long currentBytes;

        BatchPredicate(int maxDocs, long maxBytes) {
            this.maxDocs = maxDocs;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean test(Document doc) {
            currentCount++;
            currentBytes += doc.sourceLength();

            if (currentCount >= maxDocs || currentBytes >= maxBytes) {
                currentCount = 0;
                currentBytes = 0;
                return true;
            }
            return false;
        }
    }
}
