package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

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
 * on non-blocking Reactor threads. Uses BlockHound to detect violations and
 * StepVerifier to drive the reactive streams.
 */
class BlockingCallDetectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ShardId SHARD = new ShardId("snap", "idx", 0);

    @BeforeAll
    static void installBlockHound() {
        BlockHound.install();
    }

    @Test
    void migrateShard_noBlockingOnReactorThreads() {
        var pipeline = new MigrationPipeline(nonBlockingSource(50), nonBlockingSink(), 10, 100_000);

        StepVerifier.create(
                pipeline.migrateShard(SHARD, "idx", 0)
                    .subscribeOn(Schedulers.parallel())
            )
            .thenConsumeWhile(cursor -> cursor.docsInBatch() > 0)
            .verifyComplete();
    }

    @Test
    void migrateIndex_noBlockingOnReactorThreads() {
        var pipeline = new MigrationPipeline(nonBlockingSource(20), nonBlockingSink(), 10, 100_000);

        StepVerifier.create(
                pipeline.migrateIndex("idx")
                    .subscribeOn(Schedulers.parallel())
            )
            .thenConsumeWhile(cursor -> cursor.docsInBatch() > 0)
            .verifyComplete();
    }

    @Test
    void migrateAll_noBlockingOnReactorThreads() {
        var pipeline = new MigrationPipeline(nonBlockingSource(10), nonBlockingSink(), 5, 100_000);

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
            public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
                return Mono.delay(Duration.ofMillis(10)).then();
            }

            @Override
            public Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch) {
                return Mono.delay(Duration.ofMillis(10))
                    .map(ignored -> new ProgressCursor(shardId, batch.size(), batch.size(), 0));
            }
        };
        var pipeline = new MigrationPipeline(nonBlockingSource(5), delaySink, 5, 100_000);

        StepVerifier.create(
                pipeline.migrateShard(SHARD, "idx", 0)
                    .subscribeOn(Schedulers.parallel())
            )
            .thenConsumeWhile(cursor -> cursor.docsInBatch() > 0)
            .verifyComplete();
    }

    @Test
    void blockingSink_detectedByBlockHound() {
        DocumentSink blockingSink = new DocumentSink() {
            @Override
            public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch) {
                // Wrap in flatMap to prevent fusion — ensures execution on Reactor thread
                return Mono.just(batch).flatMap(b -> {
                    try {
                        Thread.sleep(10); // Blocking call on Reactor thread
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Mono.just(new ProgressCursor(shardId, b.size(), b.size(), 0));
                });
            }
        };
        var pipeline = new MigrationPipeline(nonBlockingSource(3), blockingSink, 3, 100_000);

        StepVerifier.create(
                pipeline.migrateShard(SHARD, "idx", 0)
                    .subscribeOn(Schedulers.parallel())
            )
            .verifyErrorSatisfies(e -> {
                // BlockHound wraps the error in PipelineException via onErrorMap
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
            public List<String> listIndices() {
                return List.of("idx");
            }

            @Override
            public List<ShardId> listShards(String indexName) {
                return List.of(SHARD);
            }

            @Override
            public IndexMetadataSnapshot readIndexMetadata(String indexName) {
                ObjectNode empty = MAPPER.createObjectNode();
                return new IndexMetadataSnapshot(indexName, 1, 0, empty, empty, empty);
            }

            @Override
            public Flux<DocumentChange> readDocuments(ShardId shardId, long startingDocOffset) {
                return Flux.range(0, docCount)
                    .map(i -> new DocumentChange(
                        "doc-" + i, null,
                        ("{\"field\":\"value-" + i + "\"}").getBytes(),
                        null, DocumentChange.ChangeType.INDEX
                    ));
            }
        };
    }

    private static DocumentSink nonBlockingSink() {
        return new DocumentSink() {
            @Override
            public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch) {
                long bytes = batch.stream()
                    .mapToLong(d -> d.source() != null ? d.source().length : 0)
                    .sum();
                return Mono.just(new ProgressCursor(shardId, batch.size(), batch.size(), bytes));
            }
        };
    }
}
