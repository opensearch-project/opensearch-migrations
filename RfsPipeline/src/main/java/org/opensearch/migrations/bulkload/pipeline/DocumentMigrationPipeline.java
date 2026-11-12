package org.opensearch.migrations.bulkload.pipeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
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
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
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
    private final PipelineMetrics pipelineMetrics;

    // Observable state — polled by PipelineProgressMonitor
    private final AtomicLong totalDocs = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicInteger activeBatches = new AtomicInteger();
    private final AtomicReference<Partition> currentPartition = new AtomicReference<>();

    /**
     * Create a pipeline with sequential partition processing and default batch concurrency.
     */
    public DocumentMigrationPipeline(DocumentSource source, DocumentSink sink, int maxDocsPerBatch, long maxBytesPerBatch) {
        this(source, sink, maxDocsPerBatch, maxBytesPerBatch, 1, 10, PipelineMetrics.NONE);
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
        this(
            source,
            sink,
            maxDocsPerBatch,
            maxBytesPerBatch,
            partitionConcurrency,
            batchConcurrency,
            PipelineMetrics.NONE
        );
    }

    /**
     * Create a pipeline with configurable concurrency and stage instrumentation.
     */
    public DocumentMigrationPipeline(
        DocumentSource source,
        DocumentSink sink,
        int maxDocsPerBatch,
        long maxBytesPerBatch,
        int partitionConcurrency,
        int batchConcurrency,
        PipelineMetrics pipelineMetrics
    ) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        this.pipelineMetrics = Objects.requireNonNull(pipelineMetrics, "pipelineMetrics must not be null");
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
        pipelineMetrics.onConfigured(
            maxDocsPerBatch,
            maxBytesPerBatch,
            batchConcurrency,
            preparationConcurrency,
            preparationChunkDocuments
        );
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
                .concatMap(phase -> migratePhase(phase, collectionName), 1);
            return completedBatches
                // Advance checkpoints in emission order; writes may complete out of order.
                .scan(new ProgressCursor(partition, startingPosition, 0, 0, MigrationMetrics.ZERO), (previous, batch) -> {
                    var result = batch.result();
                    var sourceMetrics = batch.sourceMetrics();
                    totalDocs.addAndGet(result.docsInBatch());
                    totalBytes.addAndGet(result.bytesInBatch());
                    pipelineMetrics.onCheckpoint(result.docsInBatch(), sourceMetrics.bytes());
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

    private Flux<CompletedBatch> migratePhase(Flux<Document> documents, String collectionName) {
        if (groupedSink == null) {
            return migrateDocumentBatches(documents, collectionName);
        }
        return groupedSink.supportsPersistentPreparationLanes()
            ? migratePersistentLaneBatches(groupedSink, documents, collectionName)
            : migratePreparedBatches(groupedSink, documents, collectionName);
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

    /**
     * Preserve source-batch boundaries for sinks whose transforms can add, remove, or reorder documents. Prepared
     * source batches are packed together only at the grouped sink, after each transform has seen its original batch.
     */
    private <P> Flux<CompletedBatch> migratePreparedBatches(
        GroupedDocumentSink<P> preparedSink,
        Flux<Document> documents,
        String collectionName
    ) {
        return prepareAndPackBatches(preparedSink, documents, collectionName)
            .buffer(sourceBatchGroupSize)
            .flatMapSequential(packedBatches -> {
                activeBatches.incrementAndGet();
                var sourceBatches = packedBatches.stream().map(PackedBatch::documents).toList();
                var lastBatch = sourceBatches.get(sourceBatches.size() - 1);
                var lastPosition = lastBatch.get(lastBatch.size() - 1).document().position();
                var sourceMetrics = sourceBatches.stream()
                    .map(DocumentMigrationPipeline::sourceMetrics)
                    .reduce(MigrationMetrics.Operations.ZERO, MigrationMetrics.Operations::plus);
                long preparedBytes = packedBatches.stream().mapToLong(PackedBatch::preparedBytes).sum();
                long oldestReadyNanos = packedBatches.stream().mapToLong(PackedBatch::readyNanos).min().orElseThrow();
                return Mono.defer(() -> {
                    long startedNanos = System.nanoTime();
                    pipelineMetrics.onRequestGroupStarted(
                        sourceBatches.size(),
                        sourceMetrics.upserts() + sourceMetrics.deletes(),
                        preparedBytes,
                        nanosToMillis(startedNanos - oldestReadyNanos)
                    );
                    return preparedSink.writePreparedBatchGroup(
                            collectionName,
                            sourceBatches,
                            Math.min(requestConcurrencyPerGroup, sourceBatches.size())
                        )
                        .map(result -> new CompletedBatch(lastPosition, sourceMetrics, result))
                        .doFinally(signal -> pipelineMetrics.onRequestGroupFinished(
                            sourceMetrics.upserts() + sourceMetrics.deletes(),
                            preparedBytes,
                            nanosToMillis(System.nanoTime() - startedNanos),
                            signal == reactor.core.publisher.SignalType.ON_COMPLETE
                        ));
                }).doFinally(signal -> activeBatches.decrementAndGet());
            }, batchGroupConcurrency, 1);
    }

    private <P> Flux<PackedBatch<P>> prepareAndPackBatches(
        GroupedDocumentSink<P> preparedSink,
        Flux<Document> documents,
        String collectionName
    ) {
        return Flux.defer(() -> {
            var packer = new PreparedBatchPacker<P>(maxDocsPerBatch, maxBytesPerBatch);
            var preparedChunks = documents
                .bufferUntil(new BatchSizePredicate<>(
                    preparationChunkDocuments,
                    maxBytesPerBatch,
                    Document::sourceLength
                ), true)
                .map(sourceChunk -> {
                    long sourceBytes = sourceChunk.stream().mapToLong(Document::sourceLength).sum();
                    long queuedNanos = System.nanoTime();
                    pipelineMetrics.onPreparationChunkQueued(sourceChunk.size(), sourceBytes);
                    return new SourceChunk(sourceChunk, sourceBytes, queuedNanos);
                })
                .flatMapSequential(sourceChunk -> Mono.fromCallable(
                    () -> prepareChunk(preparedSink, collectionName, sourceChunk)
                ).subscribeOn(Schedulers.parallel()), preparationConcurrency, 1);

            return preparedChunks
                .concatMapIterable(
                    preparedChunk -> releaseAndPack(packer, preparedChunk),
                    preparationConcurrency * PREPARED_CHUNKS_PER_PREPARATION_RAIL
                )
                .concatWith(Flux.defer(() -> Flux.fromIterable(toPackedBatches(packer.finish()))));
        });
    }

    private <P> List<PackedBatch<P>> releaseAndPack(
        PreparedBatchPacker<P> packer,
        PreparedChunk<P> preparedChunk
    ) {
        long releasedNanos = System.nanoTime();
        pipelineMetrics.onPreparationChunkReleased(
            preparedChunk.documents().size(),
            preparedChunk.preparedBytes(),
            nanosToMillis(releasedNanos - preparedChunk.completedNanos())
        );
        long packingStartedNanos = System.nanoTime();
        var completed = packer.add(preparedChunk.documents());
        pipelineMetrics.onPackingChunk(
            preparedChunk.documents().size(),
            nanosToMillis(System.nanoTime() - packingStartedNanos)
        );
        return toPackedBatches(completed);
    }

    private <P> List<PackedBatch<P>> toPackedBatches(List<PreparedBatch<P>> batches) {
        return batches.stream().map(batch -> {
            pipelineMetrics.onBatchPacked(
                batch.documents().size(),
                batch.preparedBytes(),
                100.0 * batch.documents().size() / maxDocsPerBatch,
                100.0 * batch.preparedBytes() / maxBytesPerBatch
            );
            return new PackedBatch<>(batch.documents(), batch.preparedBytes(), System.nanoTime());
        }).toList();
    }

    /**
     * Split a source phase into stable CPU lanes. Each lane owns preparation, exact packing, and asynchronous
     * request submission for its lifetime. Lane data does not merge or regroup before the sink submits it to
     * Netty. A shared asynchronous permit gate bounds the number of outstanding batches without taking ownership
     * of or regrouping lane data; any lane can use any free permit. The sink's process-wide adaptive limiter remains
     * shared across every lane and every HTTP attempt. Completed source chunks merge back into source order only
     * where checkpoints advance.
     */
    private <P> Flux<CompletedBatch> migratePersistentLaneBatches(
        GroupedDocumentSink<P> preparedSink,
        Flux<Document> documents,
        String collectionName
    ) {
        return Flux.defer(() -> {
            int laneCount = preparationConcurrency;
            var laneSchedulers = new ArrayList<Scheduler>(laneCount);
            for (int lane = 0; lane < laneCount; lane++) {
                laneSchedulers.add(Schedulers.newSingle("rfs-preparation-lane-" + lane));
            }
            var nextSequence = new AtomicLong();
            var requestGate = new RequestConcurrencyGate(batchConcurrency);

            var sourceChunks = documents
                .bufferUntil(new BatchSizePredicate<>(
                    preparationChunkDocuments,
                    maxBytesPerBatch,
                    Document::sourceLength
                ), true)
                .map(sourceChunk -> newLaneSourceChunk(nextSequence.getAndIncrement(), sourceChunk));

            var unorderedCompletions = sourceChunks
                .groupBy(sourceChunk -> (int) (sourceChunk.state().sequence() % laneCount), laneCount)
                .flatMap(
                    lane -> migratePersistentLane(
                        preparedSink,
                        lane,
                        collectionName,
                        laneSchedulers.get(lane.key()),
                        requestGate
                    ),
                    laneCount,
                    1
                );

            return restoreSourceOrder(unorderedCompletions)
                .doFinally(signal -> laneSchedulers.forEach(Scheduler::dispose));
        });
    }

    private LaneSourceChunk newLaneSourceChunk(long sequence, List<Document> documents) {
        long sourceBytes = documents.stream().mapToLong(Document::sourceLength).sum();
        long queuedNanos = System.nanoTime();
        pipelineMetrics.onPreparationChunkQueued(documents.size(), sourceBytes);
        var lastPosition = documents.get(documents.size() - 1).position();
        var state = new LaneChunkState(
            sequence,
            lastPosition,
            MigrationMetrics.Operations.fromDocuments(documents),
            documents.size(),
            sourceBytes
        );
        return new LaneSourceChunk(state, new SourceChunk(documents, sourceBytes, queuedNanos));
    }

    private <P> Flux<CompletedBatch> migratePersistentLane(
        GroupedDocumentSink<P> preparedSink,
        Flux<LaneSourceChunk> sourceChunks,
        String collectionName,
        Scheduler laneScheduler,
        RequestConcurrencyGate requestGate
    ) {
        return Flux.defer(() -> {
            var packer = new LanePreparedBatchPacker<P>(maxDocsPerBatch, maxBytesPerBatch);
            return sourceChunks
                .concatMap(sourceChunk -> Mono.fromCallable(
                    () -> prepareLaneChunk(preparedSink, collectionName, sourceChunk)
                ).subscribeOn(laneScheduler), 1)
                .concatMapIterable(preparedChunk -> releaseAndPack(packer, preparedChunk), 1)
                .concatWith(Flux.defer(() -> Flux.fromIterable(toLanePackedBatches(packer.finish()))))
                // Acquire before subscribing to the request Mono. With concatMap, each lane retains at most one
                // packed batch while waiting, but a productive lane can consume every otherwise-idle permit.
                .concatMap(
                    packedBatch -> requestGate.acquire()
                        .map(permit -> new PermittedLanePackedBatch<>(packedBatch, permit)),
                    1
                )
                .flatMap(
                    permittedBatch -> writeLaneRequest(preparedSink, collectionName, permittedBatch.batch())
                        .doFinally(signal -> permittedBatch.permit().release()),
                    batchConcurrency,
                    1
                )
                .concatMapIterable(completed -> completed);
        });
    }

    private <P> LanePreparedChunk<P> prepareLaneChunk(
        GroupedDocumentSink<P> preparedSink,
        String collectionName,
        LaneSourceChunk sourceChunk
    ) {
        var preparedChunk = prepareChunk(preparedSink, collectionName, sourceChunk.source());
        var prepared = preparedChunk.documents().stream()
            .map(document -> new LanePreparedDocument<>(sourceChunk.state(), document))
            .toList();
        return new LanePreparedChunk<>(
            prepared,
            preparedChunk.preparedBytes(),
            preparedChunk.completedNanos()
        );
    }

    private <P> List<LanePackedBatch<P>> releaseAndPack(
        LanePreparedBatchPacker<P> packer,
        LanePreparedChunk<P> preparedChunk
    ) {
        long releasedNanos = System.nanoTime();
        pipelineMetrics.onPreparationChunkReleased(
            preparedChunk.documents().size(),
            preparedChunk.preparedBytes(),
            nanosToMillis(releasedNanos - preparedChunk.completedNanos())
        );
        long packingStartedNanos = System.nanoTime();
        var completed = packer.add(preparedChunk.documents());
        pipelineMetrics.onPackingChunk(
            preparedChunk.documents().size(),
            nanosToMillis(System.nanoTime() - packingStartedNanos)
        );
        return toLanePackedBatches(completed);
    }

    private <P> List<LanePackedBatch<P>> toLanePackedBatches(
        List<LanePreparedBatch<P>> batches
    ) {
        return batches.stream().map(batch -> {
            pipelineMetrics.onBatchPacked(
                batch.documents().size(),
                batch.preparedBytes(),
                100.0 * batch.documents().size() / maxDocsPerBatch,
                100.0 * batch.preparedBytes() / maxBytesPerBatch
            );
            return new LanePackedBatch<>(batch.documents(), batch.preparedBytes(), System.nanoTime());
        }).toList();
    }

    private <P> Mono<List<CompletedBatch>> writeLaneRequest(
        GroupedDocumentSink<P> preparedSink,
        String collectionName,
        LanePackedBatch<P> packedBatch
    ) {
        activeBatches.incrementAndGet();
        var sourceBatch = packedBatch.documents().stream()
            .map(LanePreparedDocument::document)
            .toList();
        long preparedBytes = packedBatch.preparedBytes();
        long documentCount = sourceBatch.size();

        return Mono.defer(() -> {
            long startedNanos = System.nanoTime();
            pipelineMetrics.onRequestGroupStarted(
                1,
                documentCount,
                preparedBytes,
                nanosToMillis(startedNanos - packedBatch.readyNanos())
            );
            return preparedSink.writePreparedBatchGroup(
                    collectionName,
                    List.of(sourceBatch),
                    1
                )
                .map(result -> completeLaneDocuments(List.of(packedBatch), result))
                .doFinally(signal -> pipelineMetrics.onRequestGroupFinished(
                    documentCount,
                    preparedBytes,
                    nanosToMillis(System.nanoTime() - startedNanos),
                    signal == reactor.core.publisher.SignalType.ON_COMPLETE
                ));
        }).doFinally(signal -> activeBatches.decrementAndGet());
    }

    private static <P> List<CompletedBatch> completeLaneDocuments(
        List<LanePackedBatch<P>> packedBatches,
        BatchResult result
    ) {
        var completedByChunk = new IdentityHashMap<LaneChunkState, Integer>();
        for (var batch : packedBatches) {
            for (var document : batch.documents()) {
                completedByChunk.merge(document.chunk(), 1, Integer::sum);
            }
        }
        var metricOwner = completedByChunk.keySet().stream()
            .max(Comparator.comparingLong(LaneChunkState::sequence))
            .orElseThrow();
        var completed = new ArrayList<CompletedBatch>();
        completedByChunk.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(LaneChunkState::sequence)))
            .forEach(entry -> {
                var chunk = entry.getKey();
                var contribution = chunk == metricOwner ? result : new BatchResult(0, 0);
                chunk.completeDocuments(entry.getValue(), contribution).ifPresent(completed::add);
            });
        return completed;
    }

    private static Flux<CompletedBatch> restoreSourceOrder(Flux<CompletedBatch> unordered) {
        return unordered
            .scanWith(
                () -> new ReorderEmission(new CompletionReorderBuffer(), List.of()),
                (previous, completed) -> new ReorderEmission(
                    previous.buffer(),
                    previous.buffer().accept(completed)
                )
            )
            .skip(1)
            .concatMapIterable(ReorderEmission::ready);
    }

    private <P> PreparedChunk<P> prepareChunk(
        GroupedDocumentSink<P> preparedSink,
        String collectionName,
        SourceChunk sourceChunk
    ) {
        long startedNanos = System.nanoTime();
        pipelineMetrics.onPreparationChunkStarted(
            sourceChunk.documents().size(),
            sourceChunk.sourceBytes(),
            nanosToMillis(startedNanos - sourceChunk.queuedNanos())
        );
        try {
            var prepared = new ArrayList<GroupedDocumentSink.PreparedDocument<P>>(sourceChunk.documents().size());
            long preparedBytes = 0;
            for (var document : sourceChunk.documents()) {
                var preparedDocument = preparedSink.prepareSourceDocument(collectionName, document);
                prepared.add(preparedDocument);
                preparedBytes += preparedDocument.sizeBytes();
            }
            long completedNanos = System.nanoTime();
            pipelineMetrics.onPreparationChunkFinished(
                prepared.size(),
                sourceChunk.sourceBytes(),
                preparedBytes,
                nanosToMillis(completedNanos - startedNanos),
                true
            );
            return new PreparedChunk<>(prepared, preparedBytes, completedNanos);
        } catch (RuntimeException | Error e) {
            pipelineMetrics.onPreparationChunkFinished(
                sourceChunk.documents().size(),
                sourceChunk.sourceBytes(),
                0,
                nanosToMillis(System.nanoTime() - startedNanos),
                false
            );
            throw e;
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
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
        private long pendingBytes;

        private PreparedBatchPacker(int maxDocuments, long maxBytes) {
            startsNewBatch = new BatchSizePredicate<>(
                maxDocuments,
                maxBytes,
                GroupedDocumentSink.PreparedDocument::sizeBytes
            );
        }

        private List<PreparedBatch<P>> add(
            List<GroupedDocumentSink.PreparedDocument<P>> preparedChunk
        ) {
            List<PreparedBatch<P>> completed = null;
            for (var document : preparedChunk) {
                if (startsNewBatch.test(document)) {
                    if (completed == null) {
                        completed = new ArrayList<>();
                    }
                    completed.add(new PreparedBatch<>(pending, pendingBytes));
                    pending = new ArrayList<>();
                    pendingBytes = 0;
                }
                pending.add(document);
                pendingBytes += document.sizeBytes();
            }
            return completed == null ? List.of() : completed;
        }

        private List<PreparedBatch<P>> finish() {
            if (pending.isEmpty()) {
                return List.of();
            }
            var finalBatch = pending;
            long finalBytes = pendingBytes;
            pending = new ArrayList<>();
            pendingBytes = 0;
            return List.of(new PreparedBatch<>(finalBatch, finalBytes));
        }
    }

    private static final class LanePreparedBatchPacker<P> {
        private final BatchSizePredicate<LanePreparedDocument<P>> startsNewBatch;
        private List<LanePreparedDocument<P>> pending = new ArrayList<>();
        private long pendingBytes;

        private LanePreparedBatchPacker(int maxDocuments, long maxBytes) {
            startsNewBatch = new BatchSizePredicate<>(
                maxDocuments,
                maxBytes,
                document -> document.document().sizeBytes()
            );
        }

        private List<LanePreparedBatch<P>> add(List<LanePreparedDocument<P>> preparedChunk) {
            List<LanePreparedBatch<P>> completed = null;
            for (var document : preparedChunk) {
                if (startsNewBatch.test(document)) {
                    if (completed == null) {
                        completed = new ArrayList<>();
                    }
                    completed.add(new LanePreparedBatch<>(pending, pendingBytes));
                    pending = new ArrayList<>();
                    pendingBytes = 0;
                }
                pending.add(document);
                pendingBytes += document.document().sizeBytes();
            }
            return completed == null ? List.of() : completed;
        }

        private List<LanePreparedBatch<P>> finish() {
            if (pending.isEmpty()) {
                return List.of();
            }
            var finalBatch = pending;
            long finalBytes = pendingBytes;
            pending = new ArrayList<>();
            pendingBytes = 0;
            return List.of(new LanePreparedBatch<>(finalBatch, finalBytes));
        }
    }

    private static final class LaneChunkState {
        private final long sequence;
        private final OptionalLong lastPosition;
        private final MigrationMetrics.Operations sourceMetrics;
        private final long sourceDocuments;
        private final long sourceBytes;
        private int remainingDocuments;
        private MigrationMetrics.Operations target = MigrationMetrics.Operations.ZERO;
        private MigrationMetrics.Requests requests = MigrationMetrics.Requests.ZERO;
        private MigrationMetrics.Failures failures = MigrationMetrics.Failures.ZERO;

        private LaneChunkState(
            long sequence,
            OptionalLong lastPosition,
            MigrationMetrics.Operations sourceMetrics,
            int sourceDocuments,
            long sourceBytes
        ) {
            this.sequence = sequence;
            this.lastPosition = lastPosition;
            this.sourceMetrics = sourceMetrics;
            this.sourceDocuments = sourceDocuments;
            this.sourceBytes = sourceBytes;
            this.remainingDocuments = sourceDocuments;
        }

        private long sequence() {
            return sequence;
        }

        private synchronized Optional<CompletedBatch> completeDocuments(int count, BatchResult result) {
            target = target.plus(result.target());
            requests = requests.plus(result.requests());
            failures = failures.plus(result.failures());
            remainingDocuments -= count;
            if (remainingDocuments < 0) {
                throw new IllegalStateException(
                    "Persistent lane completed more documents than source chunk " + sequence + " contains"
                );
            }
            if (remainingDocuments > 0) {
                return Optional.empty();
            }
            return Optional.of(new CompletedBatch(
                sequence,
                lastPosition,
                sourceMetrics,
                new BatchResult(sourceDocuments, sourceBytes, target, requests, failures)
            ));
        }
    }

    private static final class CompletionReorderBuffer {
        private final TreeMap<Long, CompletedBatch> pending = new TreeMap<>();
        private long nextSequence;

        private List<CompletedBatch> accept(CompletedBatch completed) {
            var previous = pending.put(completed.sequence(), completed);
            if (previous != null) {
                throw new IllegalStateException("Duplicate persistent-lane completion " + completed.sequence());
            }
            List<CompletedBatch> ready = null;
            while (true) {
                var next = pending.remove(nextSequence);
                if (next == null) {
                    break;
                }
                if (ready == null) {
                    ready = new ArrayList<>();
                }
                ready.add(next);
                nextSequence++;
            }
            return ready == null ? List.of() : ready;
        }
    }

    // Sonar flags the compiler-generated record constructor parameters as unused.
    @SuppressWarnings("java:S1172")
    private record CompletedBatch(
        long sequence, OptionalLong lastPosition, MigrationMetrics.Operations sourceMetrics, BatchResult result
    ) {
        private CompletedBatch(
            OptionalLong lastPosition, MigrationMetrics.Operations sourceMetrics, BatchResult result
        ) {
            this(-1, lastPosition, sourceMetrics, result);
        }
    }

    private record SourceChunk(List<Document> documents, long sourceBytes, long queuedNanos) {}

    private record LaneSourceChunk(LaneChunkState state, SourceChunk source) {}

    private record PreparedChunk<P>(
        List<GroupedDocumentSink.PreparedDocument<P>> documents,
        long preparedBytes,
        long completedNanos
    ) {}

    private record PreparedBatch<P>(
        List<GroupedDocumentSink.PreparedDocument<P>> documents,
        long preparedBytes
    ) {}

    private record PackedBatch<P>(
        List<GroupedDocumentSink.PreparedDocument<P>> documents,
        long preparedBytes,
        long readyNanos
    ) {}

    private record LanePreparedDocument<P>(
        LaneChunkState chunk,
        GroupedDocumentSink.PreparedDocument<P> document
    ) {}

    private record LanePreparedChunk<P>(
        List<LanePreparedDocument<P>> documents,
        long preparedBytes,
        long completedNanos
    ) {}

    private record LanePreparedBatch<P>(
        List<LanePreparedDocument<P>> documents,
        long preparedBytes
    ) {}

    private record LanePackedBatch<P>(
        List<LanePreparedDocument<P>> documents,
        long preparedBytes,
        long readyNanos
    ) {}

    private record PermittedLanePackedBatch<P>(
        LanePackedBatch<P> batch,
        RequestPermit permit
    ) {}

    private record ReorderEmission(
        CompletionReorderBuffer buffer,
        List<CompletedBatch> ready
    ) {}

    /**
     * A fixed asynchronous concurrency gate shared by every persistent lane. It queues only lane-local subscription
     * continuations, never document or request data, and does not impose ordering on the requests that acquire it.
     */
    private static final class RequestConcurrencyGate {
        private final ArrayDeque<MonoSink<RequestPermit>> waiters = new ArrayDeque<>();
        private int available;

        private RequestConcurrencyGate(int permits) {
            available = permits;
        }

        private Mono<RequestPermit> acquire() {
            return Mono.create(sink -> {
                RequestPermit permit = null;
                synchronized (this) {
                    if (available > 0) {
                        available--;
                        permit = new RequestPermit(this);
                    } else {
                        waiters.addLast(sink);
                    }
                }
                if (permit != null) {
                    sink.success(permit);
                } else {
                    sink.onCancel(() -> cancel(sink));
                }
            });
        }

        private synchronized void cancel(MonoSink<RequestPermit> waiter) {
            waiters.remove(waiter);
        }

        private void release() {
            MonoSink<RequestPermit> waiter;
            synchronized (this) {
                waiter = waiters.pollFirst();
                if (waiter == null) {
                    available++;
                    return;
                }
            }
            waiter.success(new RequestPermit(this));
        }
    }

    private static final class RequestPermit {
        private final RequestConcurrencyGate gate;
        private boolean released;

        private RequestPermit(RequestConcurrencyGate gate) {
            this.gate = gate;
        }

        private void release() {
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
            }
            gate.release();
        }
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

}
