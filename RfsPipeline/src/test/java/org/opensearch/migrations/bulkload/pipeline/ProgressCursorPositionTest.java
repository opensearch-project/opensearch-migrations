package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Sparse positions and non-overlapping checkpoints under concurrent writes. */
class ProgressCursorPositionTest {

    private static final String IDX = "idx";

    @Test
    void completedRequestsBeyondAGapDoNotAdvanceCommittedMetrics() throws Exception {
        var started = new CountDownLatch(3);
        var firstEmitted = new CountDownLatch(1);
        var completions = List.of(Sinks.<BatchResult>one(), Sinks.<BatchResult>one(), Sinks.<BatchResult>one());
        var cursors = new CopyOnWriteArrayList<ProgressCursor>();
        var batchSizes = new CopyOnWriteArrayList<Integer>();
        var source = new SparseSource(List.of(0, 1, 2, 3, 4), true) {
            @Override
            public Flux<Document> readDocuments(Partition partition, long start) {
                return super.readDocuments(partition, start).map(doc -> new Document(doc.id(),
                    new byte[doc.position().orElseThrow() == 0 ? 10 : 1], doc.operation(),
                    doc.hints(), doc.sourceMetadata()));
            }
        };
        var sink = new CountingSink() {
            int next;

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                batchSizes.add(batch.size());
                var result = completions.get(next++);
                started.countDown();
                return result.asMono();
            }
        };
        var subscription = new DocumentMigrationPipeline(source, sink, 3, 10)
            .migratePartition(partition(), IDX, 0)
            .subscribe(cursor -> {
                cursors.add(cursor);
                firstEmitted.countDown();
            });
        try {
            Assertions.assertTrue(started.await(10, TimeUnit.SECONDS));
            Assertions.assertEquals(List.of(1, 3, 1), batchSizes);
            completions.get(2).tryEmitValue(new BatchResult(1, 1,
                new MigrationMetrics.Requests(1, 1, 100, 10)));
            Assertions.assertTrue(cursors.isEmpty());
            completions.get(0).tryEmitValue(new BatchResult(1, 10,
                new MigrationMetrics.Requests(1, 2, 200, 20)));
            Assertions.assertTrue(firstEmitted.await(10, TimeUnit.SECONDS));
            Assertions.assertEquals(1, cursors.size());
            Assertions.assertEquals(1, cursors.get(0).nextPosition());
            Assertions.assertEquals(new MigrationMetrics(
                new MigrationMetrics.Source(1, 0, 10), new MigrationMetrics.Requests(1, 2, 200, 20)),
                cursors.get(0).metrics());
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void orderedSourcesFinishEarlierBatchesBeforeSendingLaterBatches() throws Exception {
        var source = new SparseSource(List.of(0, 1), true) {
            @Override
            public boolean requiresOrderedWrites() {
                return true;
            }
        };
        var firstStarted = new CountDownLatch(1);
        var firstCompleted = Sinks.<BatchResult>one();
        var started = new CopyOnWriteArrayList<String>();
        var sink = new CountingSink() {
            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                started.add(batch.get(0).id());
                firstStarted.countDown();
                return started.size() == 1 ? firstCompleted.asMono() : Mono.just(new BatchResult(1, 1));
            }
        };
        var result = new DocumentMigrationPipeline(source, sink, 1, 100)
            .migratePartition(partition(), IDX, 0).collectList().toFuture();
        Assertions.assertTrue(firstStarted.await(10, TimeUnit.SECONDS));
        Assertions.assertEquals(List.of("doc-0"), started);
        firstCompleted.tryEmitValue(new BatchResult(1, 1));
        var cursors = result.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals(List.of("doc-0", "doc-1"), started);
        Assertions.assertEquals(2, cursors.get(1).metrics().source().upserts());
    }

    @Test
    void fallbackOffsetsAdvanceInSourceOrderWhenWritesCompleteInReverseOrder() throws Exception {
        var writesStarted = new CountDownLatch(3);
        var completions = List.of(Sinks.<BatchResult>one(), Sinks.<BatchResult>one(), Sinks.<BatchResult>one());
        var sink = new CountingSink() {
            int next;

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                var completion = completions.get(next++);
                writesStarted.countDown();
                return completion.asMono();
            }
        };
        var future = new DocumentMigrationPipeline(
            new SparseSource(List.of(13, 17, 19), false), sink, 1, 1000)
            .migratePartition(partition(), IDX, 10).collectList().toFuture();
        Assertions.assertTrue(writesStarted.await(10, TimeUnit.SECONDS));
        for (int i = 2; i >= 0; i--) {
            completions.get(i).tryEmitValue(new BatchResult(1, 1));
        }
        var cursors = future.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals(List.of(11L, 12L, 13L),
            cursors.stream().map(ProgressCursor::nextPosition).toList());
    }

    @Test
    void resubscribingStartsWithTheOriginalOffset() {
        var migration = new DocumentMigrationPipeline(
            new SparseSource(List.of(13, 17, 19), false), new CountingSink(), 2, 1000)
            .migratePartition(partition(), IDX, 10).collectList();
        Assertions.assertEquals(migration.block(Duration.ofSeconds(10)), migration.block(Duration.ofSeconds(10)));
    }

    @Test
    void handoffAfterAMultiDocumentBatchStartsAtTheNextDocument() {
        var source = new SparseSource(List.of(3, 7, 9, 15, 20), true);
        var first = run(source, new CountingSink(), 3, 0).get(0);
        var secondSink = new CountingSink();
        run(source, secondSink, 2, first.nextPosition());
        Assertions.assertEquals(List.of("doc-15", "doc-20"), secondSink.written);
    }

    private static Partition partition() {
        return new EsShardPartition("snap", IDX, 0);
    }

    /**
     * Source whose emittable positions are sparse — the gaps stand in for nested children,
     * which occupy Lucene doc ids but never reach the sink.
     */
    private static class SparseSource implements DocumentSource {
        private final List<Integer> emittablePositions;
        private final boolean exposePosition;

        SparseSource(List<Integer> emittablePositions, boolean exposePosition) {
            this.emittablePositions = emittablePositions;
            this.exposePosition = exposePosition;
        }

        @Override
        public List<String> listCollections() {
            return List.of(IDX);
        }

        @Override
        public List<Partition> listPartitions(String collectionName) {
            return List.of(partition());
        }

        @Override
        public CollectionMetadata readCollectionMetadata(String collectionName) {
            return new CollectionMetadata(IDX, 1, Map.of());
        }

        @Override
        public Flux<Document> readDocuments(Partition p, long startingPosition) {
            // Interpret the checkpoint as a Lucene doc id, exactly as LuceneReader does.
            return Flux.fromIterable(emittablePositions)
                .filter(pos -> pos >= startingPosition)
                .map(pos -> new Document(
                    "doc-" + pos,
                    new byte[] { 1 },
                    Document.Operation.UPSERT,
                    Map.of(),
                    exposePosition
                        ? Map.of(Document.SOURCE_META_LUCENE_DOC_NUMBER, pos)
                        : Map.of()));
        }
    }

    private static class CountingSink implements DocumentSink {
        final List<String> written = new ArrayList<>();

        @Override
        public Mono<Void> createCollection(CollectionMetadata metadata) {
            return Mono.empty();
        }

        @Override
        public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
            batch.forEach(d -> written.add(d.id()));
            return Mono.just(new BatchResult(batch.size(), batch.size()));
        }
    }

    private static List<ProgressCursor> run(DocumentSource src, DocumentSink sink,
                                            int batchSize, long from) {
        var pipeline = new DocumentMigrationPipeline(src, sink, batchSize, 1_000_000L);
        return pipeline.migratePartition(partition(), IDX, from).collectList().block();
    }

    @Test
    void cursorCarriesSourcePositionNotEmittedCount() {
        // Lucene doc ids 3, 7, 9 are roots; 0-2, 4-6, 8 are nested children.
        var cursors = run(new SparseSource(List.of(3, 7, 9), true), new CountingSink(), 1, 0);

        Assertions.assertNotNull(cursors);
        Assertions.assertEquals(List.of(4L, 8L, 10L),
            cursors.stream().map(ProgressCursor::nextPosition).toList(),
            "resume after the last source document, including sparse Lucene positions");
    }

    @Test
    void cursorFollowsTheLastPositionOfTheBatch() {
        // batch size 2 -> batches are [3,7] and [9]
        var cursors = run(new SparseSource(List.of(3, 7, 9), true), new CountingSink(), 2, 0);

        Assertions.assertNotNull(cursors);
        Assertions.assertEquals(List.of(8L, 10L),
            cursors.stream().map(ProgressCursor::nextPosition).toList(),
            "each cursor is immediately after the last document in its batch");
    }

    @Test
    void resumingAtTheCursorDoesNotReplayCompletedDocuments() {
        var sink = new CountingSink();
        var cursors = run(new SparseSource(List.of(3, 7, 9), true), sink, 1, 0);
        Assertions.assertNotNull(cursors);
        Assertions.assertEquals(List.of("doc-3", "doc-7", "doc-9"), sink.written);

        long resume = cursors.get(cursors.size() - 1).nextPosition();
        Assertions.assertEquals(10L, resume);

        var sink2 = new CountingSink();
        run(new SparseSource(List.of(3, 7, 9), true), sink2, 1, resume);
        Assertions.assertTrue(sink2.written.isEmpty());
    }

    @Test
    void midShardResumeSkipsAlreadyMigratedDocs() {
        var sink = new CountingSink();
        // Resume from position 7: docs 3 must NOT be re-read.
        run(new SparseSource(List.of(3, 7, 9), true), sink, 1, 7);
        Assertions.assertEquals(List.of("doc-7", "doc-9"), sink.written);
    }

    @Test
    void sourcesWithoutAPositionFallBackToEmittedCount() {
        // Sources that expose no position (synthetic/streaming) keep the historical
        // count-based cursor so they still resume.
        var cursors = run(new SparseSource(List.of(3, 7, 9), false), new CountingSink(), 1, 0);

        Assertions.assertNotNull(cursors);
        Assertions.assertEquals(List.of(1L, 2L, 3L),
            cursors.stream().map(ProgressCursor::nextPosition).toList(),
            "no position metadata -> fall back to counting emitted docs");
    }

    @Test
    void denseSourceIsUnaffectedByTheFix() {
        var cursors = run(new SparseSource(List.of(0, 1, 2, 3, 4), true), new CountingSink(), 1, 0);

        Assertions.assertNotNull(cursors);
        Assertions.assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
            cursors.stream().map(ProgressCursor::nextPosition).toList());
    }
}
