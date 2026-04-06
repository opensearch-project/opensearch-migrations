package org.opensearch.migrations.bulkload.pipeline;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.ProgressCursor;
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
 * <p>Progress logging is handled externally by {@link PipelineProgressMonitor}, which polls
 * {@link #getProgressSnapshot()} on a fixed timer. The reactive chain contains no logging operators.
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

    private final DocumentSource source;
    private final DocumentSink sink;
    private final int maxDocsPerBatch;
    private final long maxBytesPerBatch;
    private final int partitionConcurrency;
    private final int batchConcurrency;

    // Observable state — polled by PipelineProgressMonitor
    private final AtomicLong totalDocs = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicInteger activeBatches = new AtomicInteger();
    private final AtomicReference<Partition> currentPartition = new AtomicReference<>();

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

    /** Snapshot of pipeline progress, safe to read from any thread. */
    public record ProgressSnapshot(
        Partition currentPartition,
        long totalDocs,
        long totalBytes,
        int activeBatches,
        int batchConcurrency
    ) {}

    /** Returns a point-in-time snapshot of pipeline progress for external monitoring. */
    public ProgressSnapshot getProgressSnapshot() {
        return new ProgressSnapshot(
            currentPartition.get(),
            totalDocs.get(),
            totalBytes.get(),
            activeBatches.get(),
            batchConcurrency
        );
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
        final long[] cumulativeOffset = { startingDocOffset };
        return Flux.defer(() -> {
            currentPartition.set(partition);
            return source.readDocuments(partition, startingDocOffset)
                .subscribeOn(Schedulers.boundedElastic())
                .bufferUntil(new BatchPredicate(maxDocsPerBatch, maxBytesPerBatch))
                .publishOn(Schedulers.boundedElastic(), batchConcurrency * 2)
                .flatMapSequential(batch -> {
                    activeBatches.incrementAndGet();
                    return sink.writeBatch(collectionName, batch)
                        .map(result -> {
                            cumulativeOffset[0] += result.docsInBatch();
                            totalDocs.addAndGet(result.docsInBatch());
                            totalBytes.addAndGet(result.bytesInBatch());
                            return new ProgressCursor(
                                partition,
                                cumulativeOffset[0],
                                result.docsInBatch(),
                                result.bytesInBatch()
                            );
                        })
                        .doFinally(s -> activeBatches.decrementAndGet());
                }, batchConcurrency)
                .onErrorMap(e -> !(e instanceof PipelineException),
                    e -> new PipelineException("Failed migrating partition " + partition, e));
        });
    }

    /**
     * Migrate all partitions for a collection. Creates the collection first, then migrates
     * partitions with the configured concurrency.
     *
     * @param collectionName the collection to migrate
     * @return a Flux of progress cursors across all partitions
     */
    public Flux<ProgressCursor> migrateCollection(String collectionName) {
        var metadata = source.readCollectionMetadata(collectionName);
        var partitions = source.listPartitions(collectionName);

        return Flux.from(sink.createCollection(metadata))
            .thenMany(
                Flux.fromIterable(partitions)
                    .flatMap(
                        partition -> migratePartition(partition, collectionName, 0),
                        partitionConcurrency
                    )
            );
    }

    /**
     * Migrate all collections from source to sink.
     *
     * @return a Flux of progress cursors across all collections and partitions
     */
    public Flux<ProgressCursor> migrateAll() {
        return Flux.fromIterable(source.listCollections())
            .concatMap(this::migrateCollection);
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
