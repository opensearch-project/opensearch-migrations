package org.opensearch.migrations.bulkload.pipeline;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.sink.CollectingDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.source.SyntheticDocumentSource;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link MigrationPipeline}.
 */
class MigrationPipelineTest {

    @Nested
    class FullMigration {

        @Test
        void migratesAllDocumentsFromSourceToSink() {
            var source = new SyntheticDocumentSource("test-index", 2, 10);
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 5, 1024 * 1024);

            StepVerifier.create(pipeline.migrateIndex("test-index"))
                .thenConsumeWhile(cursor -> cursor instanceof ProgressCursor)
                .verifyComplete();

            assertEquals(20, sink.getCollectedDocuments().size(), "2 shards × 10 docs = 20");
            assertEquals(1, sink.getCreatedIndices().size());
            assertEquals("test-index", sink.getCreatedIndices().get(0).indexName());
        }

        @Test
        void migrateAllProcessesAllIndices() {
            var source = new SyntheticDocumentSource("test-index", 1, 5);
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            StepVerifier.create(pipeline.migrateAll())
                .thenConsumeWhile(cursor -> true)
                .verifyComplete();

            assertEquals(5, sink.getCollectedDocuments().size());
        }
    }

    @Nested
    class Batching {

        @Test
        void batchesByDocumentCount() {
            var source = new SyntheticDocumentSource("test-index", 1, 12);
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 5, Long.MAX_VALUE);

            StepVerifier.create(pipeline.migrateShard(
                    source.listShards("test-index").get(0), "test-index", 0))
                .thenConsumeWhile(cursor -> true)
                .verifyComplete();

            assertEquals(12, sink.getCollectedDocuments().size());
            assertEquals(3, sink.getCursors().size(), "12 docs / 5 per batch = 3 batches");
        }

        @Test
        void batchesByByteSize() {
            var source = new SyntheticDocumentSource("test-index", 1, 10);
            var sink = new CollectingDocumentSink();
            // Each synthetic doc body is ~40 bytes. Set limit to 100 bytes → ~2-3 docs per batch.
            var pipeline = new MigrationPipeline(source, sink, Integer.MAX_VALUE, 100);

            StepVerifier.create(pipeline.migrateShard(
                    source.listShards("test-index").get(0), "test-index", 0))
                .thenConsumeWhile(cursor -> true)
                .verifyComplete();

            assertEquals(10, sink.getCollectedDocuments().size());
            assertTrue(sink.getCursors().size() > 1, "Should have multiple batches due to byte limit");
        }

        @Test
        void singleDocBatch() {
            var source = new SyntheticDocumentSource("test-index", 1, 3);
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 1, Long.MAX_VALUE);

            StepVerifier.create(pipeline.migrateShard(
                    source.listShards("test-index").get(0), "test-index", 0))
                .thenConsumeWhile(cursor -> true)
                .verifyComplete();

            assertEquals(3, sink.getCollectedDocuments().size());
            assertEquals(3, sink.getCursors().size(), "1 doc per batch = 3 batches");
        }
    }

    @Nested
    class Resume {

        @Test
        void resumesFromOffset() {
            var source = new SyntheticDocumentSource("test-index", 1, 10);
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            var shardId = source.listShards("test-index").get(0);
            StepVerifier.create(pipeline.migrateShard(shardId, "test-index", 5))
                .thenConsumeWhile(cursor -> true)
                .verifyComplete();

            assertEquals(5, sink.getCollectedDocuments().size(), "Should only get docs 5-9");
        }

        @Test
        void resumeFromEndProducesNoDocuments() {
            var source = new SyntheticDocumentSource("test-index", 1, 10);
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            var shardId = source.listShards("test-index").get(0);
            StepVerifier.create(pipeline.migrateShard(shardId, "test-index", 10))
                .verifyComplete();

            assertEquals(0, sink.getCollectedDocuments().size());
        }
    }

    @Nested
    class Concurrency {

        @Test
        void parallelShardProcessing() {
            var source = new SyntheticDocumentSource("test-index", 4, 10);
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 5, Long.MAX_VALUE, 4);

            StepVerifier.create(pipeline.migrateIndex("test-index"))
                .thenConsumeWhile(cursor -> true)
                .verifyComplete();

            assertEquals(40, sink.getCollectedDocuments().size(), "4 shards × 10 docs = 40");
        }

        @Test
        void concurrencyOfOneIsSequential() {
            var callOrder = new AtomicInteger(0);
            var source = new SyntheticDocumentSource("test-index", 2, 5);
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE, 1);

            StepVerifier.create(pipeline.migrateIndex("test-index"))
                .thenConsumeWhile(cursor -> {
                    callOrder.incrementAndGet();
                    return true;
                })
                .verifyComplete();

            assertEquals(10, sink.getCollectedDocuments().size());
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void emptyShardProducesNoCursors() {
            var source = new SyntheticDocumentSource("test-index", 1, 0);
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            var shardId = source.listShards("test-index").get(0);
            StepVerifier.create(pipeline.migrateShard(shardId, "test-index", 0))
                .verifyComplete();

            assertEquals(0, sink.getCollectedDocuments().size());
            assertEquals(0, sink.getCursors().size());
        }

        @Test
        void documentsWithNullSourceHandledCorrectly() {
            var shardId = new ShardId("snap", "idx", 0);
            var source = new DocumentSource() {
                @Override public List<String> listIndices() { return List.of("idx"); }
                @Override public List<ShardId> listShards(String indexName) { return List.of(shardId); }
                @Override public IndexMetadataSnapshot readIndexMetadata(String indexName) {
                    return new IndexMetadataSnapshot(indexName, 1, 0, null, null, null);
                }
                @Override public Flux<DocumentChange> readDocuments(ShardId sid, int offset) {
                    return Flux.just(
                        new DocumentChange("d1", null, null, null, DocumentChange.ChangeType.DELETE),
                        new DocumentChange("d2", null, "{}".getBytes(), null, DocumentChange.ChangeType.INDEX)
                    );
                }
            };
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            StepVerifier.create(pipeline.migrateShard(shardId, "idx", 0))
                .thenConsumeWhile(cursor -> true)
                .verifyComplete();

            assertEquals(2, sink.getCollectedDocuments().size());
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void sourceErrorWrappedInPipelineException() {
            var shardId = new ShardId("snap", "idx", 0);
            var source = new DocumentSource() {
                @Override public List<String> listIndices() { return List.of("idx"); }
                @Override public List<ShardId> listShards(String indexName) { return List.of(shardId); }
                @Override public IndexMetadataSnapshot readIndexMetadata(String indexName) {
                    return new IndexMetadataSnapshot(indexName, 1, 0, null, null, null);
                }
                @Override public Flux<DocumentChange> readDocuments(ShardId sid, int offset) {
                    return Flux.error(new RuntimeException("source read failed"));
                }
            };
            var sink = new CollectingDocumentSink();
            var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            StepVerifier.create(pipeline.migrateShard(shardId, "idx", 0))
                .expectErrorMatches(e ->
                    e instanceof PipelineException && e.getMessage().contains("snap/idx/0"))
                .verify();
        }

        @Test
        void sinkErrorWrappedInPipelineException() {
            var source = new SyntheticDocumentSource("test-index", 1, 5);
            var shardId = source.listShards("test-index").get(0);
            var sink = new DocumentSink() {
                @Override public Mono<Void> createIndex(IndexMetadataSnapshot m) { return Mono.empty(); }
                @Override public Mono<ProgressCursor> writeBatch(ShardId s, String idx, List<DocumentChange> batch) {
                    return Mono.error(new RuntimeException("write failed"));
                }
            };
            var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            StepVerifier.create(pipeline.migrateShard(shardId, "test-index", 0))
                .expectErrorMatches(e ->
                    e instanceof PipelineException && e.getMessage().contains("synthetic/test-index/0"))
                .verify();
        }
    }

}
