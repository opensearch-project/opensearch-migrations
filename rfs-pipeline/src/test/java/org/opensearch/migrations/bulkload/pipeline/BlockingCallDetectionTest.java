package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.pipeline.ir.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.ir.Document;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.Partition;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.source.SyntheticDocumentSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Verifies that the pipeline's reactive chain does not make blocking calls
 * on non-blocking Reactor threads.
 */
class BlockingCallDetectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Partition TEST_PARTITION = new SyntheticDocumentSource.SyntheticPartition("idx", 0);

    @BeforeAll
    static void installBlockHound() {
        BlockHound.install();
    }

    @Test
    void migratePartition_noBlockingOnReactorThreads() {
        var pipeline = new DocumentMigrationPipeline(nonBlockingSource(50), nonBlockingSink(), 10, 100_000);

        StepVerifier.create(
                pipeline.migratePartition(TEST_PARTITION, "idx", 0)
                    .subscribeOn(Schedulers.parallel())
            )
            .thenConsumeWhile(cursor -> cursor.docsInBatch() > 0)
            .verifyComplete();
    }

    @Test
    void migrateCollection_noBlockingOnReactorThreads() {
        var pipeline = new DocumentMigrationPipeline(nonBlockingSource(20), nonBlockingSink(), 10, 100_000);

        StepVerifier.create(
                pipeline.migrateCollection("idx")
                    .subscribeOn(Schedulers.parallel())
            )
            .thenConsumeWhile(cursor -> cursor.docsInBatch() > 0)
            .verifyComplete();
    }

    @Test
    void migrateAll_noBlockingOnReactorThreads() {
        var pipeline = new DocumentMigrationPipeline(nonBlockingSource(10), nonBlockingSink(), 5, 100_000);

        StepVerifier.create(
                pipeline.migrateAll()
                    .subscribeOn(Schedulers.parallel())
            )
            .thenConsumeWhile(cursor -> cursor.docsInBatch() > 0)
            .verifyComplete();
    }

    @Test
    void sinkWithSlowNonBlockingWrite_noBlockingDetected() {
        DocumentSink delaySink = new DocumentSink() {
            @Override
            public Mono<Void> createCollection(IndexMetadataSnapshot metadata) {
                return Mono.delay(Duration.ofMillis(10)).then();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                return Mono.delay(Duration.ofMillis(10))
                    .map(ignored -> new BatchResult(batch.size(), 0));
            }
        };
        var pipeline = new DocumentMigrationPipeline(nonBlockingSource(5), delaySink, 5, 100_000);

        StepVerifier.create(
                pipeline.migratePartition(TEST_PARTITION, "idx", 0)
                    .subscribeOn(Schedulers.parallel())
            )
            .thenConsumeWhile(cursor -> cursor.docsInBatch() > 0)
            .verifyComplete();
    }

    @Test
    void blockingSink_detectedByBlockHound() {
        DocumentSink blockingSink = new DocumentSink() {
            @Override
            public Mono<Void> createCollection(IndexMetadataSnapshot metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                return Mono.just(batch)
                    .subscribeOn(Schedulers.parallel())
                    .flatMap(b -> {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Mono.just(new BatchResult(b.size(), 0));
                    });
            }
        };
        var pipeline = new DocumentMigrationPipeline(nonBlockingSource(3), blockingSink, 3, 100_000);

        StepVerifier.create(
                pipeline.migratePartition(TEST_PARTITION, "idx", 0)
                    .subscribeOn(Schedulers.parallel())
            )
            .verifyErrorSatisfies(e -> {
                Throwable cause = e;
                while (cause != null) {
                    if (cause instanceof BlockingOperationError) {
                        return;
                    }
                    cause = cause.getCause();
                }
                throw new AssertionError("Expected BlockingOperationError in cause chain, got: " + e, e);
            });
    }

    private static DocumentSource nonBlockingSource(int docCount) {
        return new DocumentSource() {
            @Override
            public List<String> listCollections() {
                return List.of("idx");
            }

            @Override
            public List<Partition> listPartitions(String collectionName) {
                return List.of(TEST_PARTITION);
            }

            @Override
            public IndexMetadataSnapshot readCollectionMetadata(String collectionName) {
                ObjectNode empty = MAPPER.createObjectNode();
                return new IndexMetadataSnapshot(collectionName, 1, 0, empty, empty, empty);
            }

            @Override
            public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
                return Flux.range(0, docCount)
                    .map(i -> new Document(
                        "doc-" + i,
                        ("{\"field\":\"value-" + i + "\"}").getBytes(),
                        Document.Operation.UPSERT,
                        Map.of(),
                        Map.of()
                    ));
            }
        };
    }

    private static DocumentSink nonBlockingSink() {
        return new DocumentSink() {
            @Override
            public Mono<Void> createCollection(IndexMetadataSnapshot metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                long bytes = batch.stream()
                    .mapToLong(Document::sourceLength)
                    .sum();
                return Mono.just(new BatchResult(batch.size(), bytes));
            }
        };
    }
}
