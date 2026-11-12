package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentMigrationPipelineMemoryTest {

    @Test
    void derivedCapacityCanFillEveryRequestWhileReadersRemainActive() {
        int maxDocumentBytes = 6;
        int maxDocsPerBatch = 3;
        int maxBytesPerBatch = 3;
        int maxConnections = 2;
        int readParallelism = Math.min(maxConnections, Runtime.getRuntime().availableProcessors());
        long capacity = org.opensearch.migrations.bulkload.common.RfsMemorySizing.sourcePermitBytes(
            maxDocumentBytes,
            maxDocsPerBatch,
            maxBytesPerBatch,
            maxConnections,
            Runtime.getRuntime().availableProcessors()
        );
        var limiter = new WeightedByteLimiter(capacity);
        var partition = new EsShardPartition("snapshot", "index", 0);
        var source = preAdmittedSource(partition, 12);
        var requestCompletions = new CopyOnWriteArrayList<Sinks.One<BatchResult>>();
        var fullRequestsStarted = new CountDownLatch(maxConnections);
        DocumentSink sink = new DocumentSink() {
            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                assertEquals(maxDocsPerBatch, batch.size());
                var completion = Sinks.<BatchResult>one();
                requestCompletions.add(completion);
                fullRequestsStarted.countDown();
                return completion.asMono();
            }
        };
        var pipeline = new DocumentMigrationPipeline(
            source,
            sink,
            maxDocsPerBatch,
            maxBytesPerBatch,
            1,
            maxConnections,
            PipelineMetrics.NONE,
            maxDocumentBytes,
            limiter
        );

        var result = pipeline.migratePartition(partition, "index", 0).collectList().toFuture();
        awaitRequests(fullRequestsStarted, 0);
        assertEquals(maxConnections, requestCompletions.size());
        assertTrue(limiter.maxUsedBytes() <= capacity);
        assertEquals(
            readParallelism * (long) maxDocumentBytes + maxConnections * (long) maxBytesPerBatch,
            capacity
        );

        while (!result.isDone()) {
            List.copyOf(requestCompletions).forEach(completion ->
                completion.tryEmitValue(new BatchResult(maxDocsPerBatch, maxBytesPerBatch)));
            Thread.yield();
        }
        assertEquals(4, result.orTimeout(2, TimeUnit.SECONDS).join().size());
        assertEquals(0, limiter.usedBytes());
    }

    @Test
    void sourcePermitIsHeldUntilItsRequestCompletes() {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var source = source(partition, List.of(
            document("one", 6),
            document("two", 6),
            document("three", 6)
        ));
        var requestCompletions = new CopyOnWriteArrayList<Sinks.One<BatchResult>>();
        var requestStarted = new CountDownLatch(3);
        DocumentSink sink = new DocumentSink() {
            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                var completion = Sinks.<BatchResult>one();
                requestCompletions.add(completion);
                requestStarted.countDown();
                return completion.asMono();
            }
        };
        var pipeline = new DocumentMigrationPipeline(
            source, sink, 1, 100, 1, 2, PipelineMetrics.NONE,
            6, new WeightedByteLimiter(12)
        );

        var result = pipeline.migratePartition(partition, "index", 0).collectList().toFuture();
        awaitRequests(requestStarted, 1);
        assertEquals(1, requestCompletions.size());

        requestCompletions.get(0).tryEmitValue(new BatchResult(1, 6));
        awaitRequests(requestStarted, 0);
        requestCompletions.get(1).tryEmitValue(new BatchResult(1, 6));
        requestCompletions.get(2).tryEmitValue(new BatchResult(1, 6));

        assertEquals(3, result.orTimeout(2, java.util.concurrent.TimeUnit.SECONDS).join().size());
    }

    @Test
    void rejectsASourceDocumentAboveItsDeclaration() {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var pipeline = new DocumentMigrationPipeline(
            source(partition, List.of(document("too-large", 7))),
            immediateSink(),
            1, 100, 1, 1, PipelineMetrics.NONE,
            6, new WeightedByteLimiter(10)
        );

        var error = assertThrows(RuntimeException.class,
            () -> pipeline.migratePartition(partition, "index", 0).blockLast(Duration.ofSeconds(2)));
        assertEquals(true, rootMessage(error).contains("over maxSourceDocumentSizeBytes"));
    }

    private static DocumentSource source(EsShardPartition partition, List<Document> documents) {
        return new DocumentSource() {
            @Override
            public List<String> listCollections() {
                return List.of("index");
            }

            @Override
            public List<org.opensearch.migrations.bulkload.pipeline.model.Partition> listPartitions(
                String collectionName
            ) {
                return List.of(partition);
            }

            @Override
            public CollectionMetadata readCollectionMetadata(String collectionName) {
                return new CollectionMetadata(collectionName, 1, Map.of());
            }

            @Override
            public Flux<Document> readDocuments(
                org.opensearch.migrations.bulkload.pipeline.model.Partition ignored,
                long startingDocOffset
            ) {
                return Flux.fromIterable(documents);
            }
        };
    }

    private static DocumentSource preAdmittedSource(EsShardPartition partition, int documentCount) {
        return new DocumentSource() {
            @Override
            public List<String> listCollections() {
                return List.of("index");
            }

            @Override
            public List<org.opensearch.migrations.bulkload.pipeline.model.Partition> listPartitions(
                String collectionName
            ) {
                return List.of(partition);
            }

            @Override
            public CollectionMetadata readCollectionMetadata(String collectionName) {
                return new CollectionMetadata(collectionName, 1, Map.of());
            }

            @Override
            public Flux<Document> readDocuments(
                org.opensearch.migrations.bulkload.pipeline.model.Partition ignored,
                long startingDocOffset
            ) {
                throw new AssertionError("memory-aware read path was not used");
            }

            @Override
            public Flux<Document> readDocuments(
                org.opensearch.migrations.bulkload.pipeline.model.Partition ignored,
                long startingDocOffset,
                SourceDocumentMemoryTracker memoryTracker
            ) {
                return Flux.range(Math.toIntExact(startingDocOffset), documentCount)
                    .flatMapSequential(
                        index -> memoryTracker.materialize(
                            () -> document(Integer.toString(index), 1),
                            Document::sourceLength,
                            Document::id
                        ),
                        memoryTracker.readParallelism(),
                        1
                    );
            }
        };
    }

    private static DocumentSink immediateSink() {
        return new DocumentSink() {
            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                return Mono.just(new BatchResult(batch.size(),
                    batch.stream().mapToLong(Document::sourceLength).sum()));
            }
        };
    }

    private static Document document(String id, int bytes) {
        return new Document(id, new byte[bytes], Document.Operation.UPSERT, Map.of(), Map.of());
    }

    private static void awaitRequests(CountDownLatch latch, long expectedRemaining) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (latch.getCount() > expectedRemaining && System.nanoTime() < deadline) {
            try {
                latch.await(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static String rootMessage(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }
}
