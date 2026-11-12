package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.bulkload.common.RfsMemorySizing;
import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Child-process harness for {@link RfsSourceMemoryStressTest}.
 */
public final class RfsSourceMemoryStressHarness {
    private static final int MIB = 1024 * 1024;
    private static final int MAX_DOCUMENT_BYTES = 4 * MIB;
    private static final int DOCUMENT_BYTES = 3 * MIB;
    private static final int MAX_DOCS_PER_BATCH = 2;
    private static final long MAX_BYTES_PER_BATCH = 6L * MIB;
    private static final int MAX_CONNECTIONS = 2;

    private RfsSourceMemoryStressHarness() {}

    public static void main(String[] args) {
        int durationSeconds = Integer.parseInt(args[0]);
        int processors = Runtime.getRuntime().availableProcessors();
        long capacity = RfsMemorySizing.sourcePermitBytes(
            MAX_DOCUMENT_BYTES,
            MAX_DOCS_PER_BATCH,
            MAX_BYTES_PER_BATCH,
            MAX_CONNECTIONS,
            processors
        );
        var limiter = new WeightedByteLimiter(capacity);
        var partition = new EsShardPartition("stress", "index", 0);
        var materialized = new AtomicLong();
        var requests = new AtomicLong();
        var activeRequests = new AtomicInteger();
        var maxActiveRequests = new AtomicInteger();
        var peakHeapBytes = new AtomicLong();

        DocumentSource source = new DocumentSource() {
            @Override
            public List<String> listCollections() {
                return List.of("index");
            }

            @Override
            public List<Partition> listPartitions(String collectionName) {
                return List.of(partition);
            }

            @Override
            public CollectionMetadata readCollectionMetadata(String collectionName) {
                return new CollectionMetadata(collectionName, 1, Map.of());
            }

            @Override
            public Flux<Document> readDocuments(Partition ignored, long startingDocOffset) {
                throw new AssertionError("memory-aware source path was not used");
            }

            @Override
            public Flux<Document> readDocuments(
                Partition ignored,
                long startingDocOffset,
                SourceDocumentMemoryTracker memoryTracker
            ) {
                return Flux.range(Math.toIntExact(startingDocOffset), Integer.MAX_VALUE)
                    .flatMapSequential(
                        index -> memoryTracker.materialize(
                            () -> {
                                materialized.incrementAndGet();
                                return new Document(
                                    Integer.toString(index),
                                    new byte[DOCUMENT_BYTES],
                                    Document.Operation.UPSERT,
                                    Map.of(),
                                    Map.of()
                                );
                            },
                            Document::sourceLength,
                            Document::id
                        ),
                        memoryTracker.readParallelism(),
                        1
                    )
                    .take(Duration.ofSeconds(durationSeconds));
            }
        };

        DocumentSink sink = new DocumentSink() {
            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                if (batch.size() > MAX_DOCS_PER_BATCH) {
                    return Mono.error(new AssertionError("batch exceeded document limit: " + batch.size()));
                }
                long bytes = batch.stream().mapToLong(Document::sourceLength).sum();
                if (bytes > MAX_BYTES_PER_BATCH) {
                    return Mono.error(new AssertionError("batch exceeded byte limit: " + bytes));
                }
                requests.incrementAndGet();
                int active = activeRequests.incrementAndGet();
                maxActiveRequests.accumulateAndGet(active, Math::max);
                sampleHeap(peakHeapBytes);
                var completed = new AtomicBoolean();
                Runnable finishRequest = () -> {
                    if (completed.compareAndSet(false, true)) {
                        activeRequests.decrementAndGet();
                    }
                };
                return Mono.delay(Duration.ofMillis(25))
                    .map(ignored -> new BatchResult(batch.size(), bytes))
                    .doOnSuccess(ignored -> finishRequest.run())
                    .doOnError(ignored -> finishRequest.run())
                    .doOnCancel(finishRequest);
            }
        };

        var pipeline = new DocumentMigrationPipeline(
            source,
            sink,
            MAX_DOCS_PER_BATCH,
            MAX_BYTES_PER_BATCH,
            1,
            MAX_CONNECTIONS,
            PipelineMetrics.NONE,
            MAX_DOCUMENT_BYTES,
            limiter
        );
        var cursors = pipeline.migratePartition(partition, "index", 0)
            .collectList()
            .block(Duration.ofSeconds(durationSeconds + 30L));
        sampleHeap(peakHeapBytes);

        long migrated = cursors.stream().mapToLong(cursor -> cursor.docsInBatch()).sum();
        if (migrated < 100) {
            throw new AssertionError("stress run made too little progress: " + migrated + " documents");
        }
        long canceledReads = materialized.get() - migrated;
        if (canceledReads < 0 || canceledReads > processors) {
            throw new AssertionError(
                "materialized " + materialized.get() + " documents but migrated " + migrated
                    + "; expected at most " + processors + " canceled active reads"
            );
        }
        if (maxActiveRequests.get() != MAX_CONNECTIONS) {
            throw new AssertionError(
                "did not fill the request window: max active requests=" + maxActiveRequests.get()
            );
        }
        if (limiter.maxUsedBytes() > capacity) {
            throw new AssertionError(
                "source permits exceeded capacity: " + limiter.maxUsedBytes() + " > " + capacity
            );
        }
        if (limiter.usedBytes() != 0) {
            throw new AssertionError("source permits leaked " + limiter.usedBytes() + " bytes");
        }

        System.out.println(
            "RFS_SOURCE_MEMORY_STRESS_PASS"
                + " durationSeconds=" + durationSeconds
                + " documents=" + migrated
                + " canceledReads=" + canceledReads
                + " requests=" + requests.get()
                + " capacityBytes=" + capacity
                + " maxPermittedBytes=" + limiter.maxUsedBytes()
                + " peakHeapBytes=" + peakHeapBytes.get()
                + " maxHeapBytes=" + Runtime.getRuntime().maxMemory()
        );
    }

    private static void sampleHeap(AtomicLong peakHeapBytes) {
        var runtime = Runtime.getRuntime();
        peakHeapBytes.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), Math::max);
    }
}
