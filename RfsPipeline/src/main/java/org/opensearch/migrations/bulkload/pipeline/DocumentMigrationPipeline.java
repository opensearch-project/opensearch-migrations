package org.opensearch.migrations.bulkload.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.sink.GroupedDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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

    private static final int MAX_PREPARATION_CHUNK_DOCUMENTS = 4 * 1024;
    private static final int PREPARED_CHUNKS_PER_PREPARATION_RAIL = 3;

    private final DocumentSource source;
    private final DocumentSink sink;
    private final int maxDocsPerBatch;
    private final long maxBytesPerBatch;
    private final int partitionConcurrency;
    private final int batchConcurrency;
    private final int preparationConcurrency;
    private final int preparationChunkDocuments;
    private final boolean requiresOrderedWrites;
    private final GroupedDocumentSink<?> groupedSink;
    private final int sourceBatchGroupSize;
    private final int requestConcurrencyPerGroup;
    private final int batchGroupConcurrency;

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
        this.preparationConcurrency = Math.min(
            batchConcurrency,
            Math.max(1, Runtime.getRuntime().availableProcessors())
        );
        this.preparationChunkDocuments = Math.min(
            MAX_PREPARATION_CHUNK_DOCUMENTS,
            1 + (maxDocsPerBatch - 1) / 4
        );
        this.requiresOrderedWrites = source.requiresOrderedWrites();
        this.groupedSink = sink instanceof GroupedDocumentSink<?> grouped ? grouped : null;
        this.sourceBatchGroupSize = groupedSink != null ? groupedSink.preferredSourceBatchGroupSize() : 1;
        if (sourceBatchGroupSize < 1) {
            throw new IllegalArgumentException(
                "GroupedDocumentSink.preferredSourceBatchGroupSize() must be >= 1, got " + sourceBatchGroupSize
            );
        }
        this.requestConcurrencyPerGroup = Math.min(this.batchConcurrency, sourceBatchGroupSize);
        this.batchGroupConcurrency =
            (this.batchConcurrency + requestConcurrencyPerGroup - 1) / requestConcurrencyPerGroup;
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
     * @param partition        the partition to migrate
     * @param collectionName   the target collection name
     * @param startingPosition the source position to resume from (0 for start)
     * @return a Flux of progress cursors, one per batch written
     */
    public Flux<ProgressCursor> migratePartition(Partition partition, String collectionName, long startingPosition) {
        return Flux.defer(() -> {
            currentPartition.set(partition);
            var documents = source.readDocuments(partition, startingPosition)
                .subscribeOn(Schedulers.boundedElastic());
            var completedBatches = writePhases(documents)
                .concatMap(phase -> groupedSink == null
                    ? migrateDocumentBatches(phase, collectionName)
                    : migratePreparedBatches(groupedSink, phase, collectionName), 1);
            return completedBatches
                // Advance checkpoints in emission order; writes may complete out of order.
                .scan(new ProgressCursor(partition, startingPosition, 0, 0, MigrationMetrics.ZERO), (previous, batch) -> {
                    var result = batch.result();
                    var sourceMetrics = batch.sourceMetrics();
                    totalDocs.addAndGet(result.docsInBatch());
                    totalBytes.addAndGet(result.bytesInBatch());
                    return new ProgressCursor(
                        partition,
                        batch.lastPosition().isPresent()
                            ? Math.addExact(batch.lastPosition().getAsLong(), 1)
                            : Math.addExact(previous.nextPosition(), Math.addExact(sourceMetrics.upserts(), sourceMetrics.deletes())),
                        result.docsInBatch(),
                        result.bytesInBatch(),
                        previous.metrics().plus(new MigrationMetrics(sourceMetrics, result.target(), result.requests(),
                            result.failures()))
                    );
                })
                .skip(1)
                .onErrorMap(e -> !(e instanceof PipelineException),
                    e -> new PipelineException("Failed migrating partition " + partition, e));
        });
    }

    private Flux<CompletedBatch> migrateDocumentBatches(
        Flux<Document> documents, String collectionName
    ) {
        return documents
            .bufferUntil(new BatchSizePredicate<>(maxDocsPerBatch, maxBytesPerBatch, Document::sourceLength), true)
            .flatMapSequential(sourceBatch -> {
                activeBatches.incrementAndGet();
                var lastPosition = sourceBatch.get(sourceBatch.size() - 1).position();
                var sourceMetrics = MigrationMetrics.Operations.fromDocuments(sourceBatch);
                return Mono.defer(() -> sink.writeBatch(collectionName, sourceBatch))
                    .map(result -> new CompletedBatch(lastPosition, sourceMetrics, result))
                    .doFinally(s -> activeBatches.decrementAndGet());
            }, batchConcurrency, 1);
    }

    private <P> Flux<CompletedBatch> migratePreparedBatches(
        GroupedDocumentSink<P> preparedSink,
        Flux<Document> documents,
        String collectionName
    ) {
        return prepareAndPackBatches(preparedSink, documents, collectionName)
            .buffer(sourceBatchGroupSize)
            .flatMapSequential(sourceBatches -> {
                activeBatches.incrementAndGet();
                // Preserve source positions and transform-batch boundaries while allowing the sink to repack
                // transformed output into exact target requests across this bounded checkpoint window.
                var lastBatch = sourceBatches.get(sourceBatches.size() - 1);
                var lastPosition = lastBatch.get(lastBatch.size() - 1).document().position();
                var sourceMetrics = sourceBatches.stream()
                    .map(DocumentMigrationPipeline::sourceMetrics)
                    .reduce(MigrationMetrics.Operations.ZERO, MigrationMetrics.Operations::plus);
                return Mono.defer(() -> preparedSink.writePreparedBatchGroup(
                        collectionName,
                        sourceBatches,
                        Math.min(requestConcurrencyPerGroup, sourceBatches.size())
                    ))
                    .map(result -> new CompletedBatch(lastPosition, sourceMetrics, result))
                    .doFinally(s -> activeBatches.decrementAndGet());
            }, batchGroupConcurrency, 1);
    }

    private <P> Flux<List<GroupedDocumentSink.PreparedDocument<P>>> prepareAndPackBatches(
        GroupedDocumentSink<P> preparedSink,
        Flux<Document> documents,
        String collectionName
    ) {
        return Flux.defer(() -> {
            var packer = new PreparedBatchPacker<P>(maxDocsPerBatch, maxBytesPerBatch);
            var preparedChunks = documents
                // Document count determines normal fan-out granularity. The byte cap is a secondary memory
                // bound for unusually large source documents.
                .bufferUntil(new BatchSizePredicate<>(
                    preparationChunkDocuments,
                    maxBytesPerBatch,
                    Document::sourceLength
                ), true)
                .flatMapSequential(sourceChunk -> Mono.fromCallable(() -> sourceChunk.stream()
                        .map(document -> preparedSink.prepareSourceDocument(collectionName, document))
                        .toList())
                    .subscribeOn(Schedulers.parallel()), preparationConcurrency, 1);

            // Packing is synchronous on whichever preparation rail produced the next ordered chunk. Keeping
            // prepared documents as block elements avoids a Reactor signal per document and avoids a publishOn
            // handoff merely to obtain buffering. Prefetch keeps several chunks available per preparation rail
            // while bounding the reservoir by a known number of document- and source-byte-bounded chunks.
            return preparedChunks
                .concatMapIterable(
                    packer::add,
                    preparationConcurrency * PREPARED_CHUNKS_PER_PREPARATION_RAIL
                )
                .concatWith(Flux.defer(() -> Flux.fromIterable(packer.finish())));
        });
    }

    /**
     * Ordered sources may contain independent phases whose requests can run concurrently within a phase but
     * must not overlap the following phase. Delta snapshot streams use this for deletes followed by upserts.
     */
    private Flux<Flux<Document>> writePhases(Flux<Document> documents) {
        if (!requiresOrderedWrites) {
            return Flux.just(documents);
        }
        return Flux.defer(() -> {
            var currentOperation = new AtomicReference<Document.Operation>();
            return documents.windowUntil(document -> {
                var previousOperation = currentOperation.getAndSet(document.operation());
                return previousOperation != null && previousOperation != document.operation();
            }, true, 1);
        });
    }

    private static MigrationMetrics.Operations sourceMetrics(
        List<? extends GroupedDocumentSink.PreparedDocument<?>> batch
    ) {
        long upserts = 0;
        long bytes = 0;
        for (var preparedDocument : batch) {
            var document = preparedDocument.document();
            if (document.operation() == Document.Operation.UPSERT) {
                upserts++;
                bytes += document.sourceLength();
            }
        }
        return new MigrationMetrics.Operations(upserts, batch.size() - upserts, bytes);
    }

    private static final class PreparedBatchPacker<P> {
        private final BatchSizePredicate<GroupedDocumentSink.PreparedDocument<P>> startsNewBatch;
        private List<GroupedDocumentSink.PreparedDocument<P>> pending = new ArrayList<>();

        private PreparedBatchPacker(int maxDocuments, long maxBytes) {
            startsNewBatch = new BatchSizePredicate<>(
                maxDocuments,
                maxBytes,
                GroupedDocumentSink.PreparedDocument::sizeBytes
            );
        }

        private List<List<GroupedDocumentSink.PreparedDocument<P>>> add(
            List<GroupedDocumentSink.PreparedDocument<P>> preparedChunk
        ) {
            List<List<GroupedDocumentSink.PreparedDocument<P>>> completed = null;
            for (var document : preparedChunk) {
                if (startsNewBatch.test(document)) {
                    if (completed == null) {
                        completed = new ArrayList<>();
                    }
                    completed.add(pending);
                    pending = new ArrayList<>();
                }
                pending.add(document);
            }
            return completed == null ? List.of() : completed;
        }

        private List<List<GroupedDocumentSink.PreparedDocument<P>>> finish() {
            if (pending.isEmpty()) {
                return List.of();
            }
            var finalBatch = pending;
            pending = new ArrayList<>();
            return List.of(finalBatch);
        }
    }

    // Sonar flags the compiler-generated record constructor parameters as unused.
    @SuppressWarnings("java:S1172")
    private record CompletedBatch(
        OptionalLong lastPosition, MigrationMetrics.Operations sourceMetrics, BatchResult result
    ) {}

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

}
