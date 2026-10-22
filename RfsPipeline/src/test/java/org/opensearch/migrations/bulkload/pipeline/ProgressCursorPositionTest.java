package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;
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
        var source = new SparseSource(List.of(0, 1, 2, 3, 4)) {
            @Override
            public Flux<PositionedDocument> readDocuments(Partition partition, String start) {
                return super.readDocuments(partition, start).map(p -> new PositionedDocument(new Document(
                    p.document().id(), new byte[p.document().id().equals("doc-0") ? 10 : 1],
                    p.document().operation(), p.document().hints(), p.document().sourceMetadata()),
                    p.cursorAfter()));
            }
        };
        var sink = new CountingSink() {
            int next;

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<PositionedDocument> batch) {
                batchSizes.add(batch.size());
                var result = completions.get(next++);
                started.countDown();
                return result.asMono();
            }
        };
        var subscription = new DocumentMigrationPipeline(source, sink, 3, 10)
            .migratePartition(partition(), IDX, null)
            .subscribe(cursor -> {
                cursors.add(cursor);
                firstEmitted.countDown();
            });
        try {
            Assertions.assertTrue(started.await(10, TimeUnit.SECONDS));
            Assertions.assertEquals(List.of(1, 3, 1), batchSizes);
            completions.get(2).tryEmitValue(new BatchResult(1, 1, "5",
                new MigrationMetrics.Operations(10, 0, 10),
                new MigrationMetrics.Requests(1, 1, 0, 100, 10)));
            Assertions.assertTrue(cursors.isEmpty());
            completions.get(0).tryEmitValue(new BatchResult(1, 10, "1",
                new MigrationMetrics.Operations(2, 0, 20),
                new MigrationMetrics.Requests(1, 2, 1, 200, 20)));
            Assertions.assertTrue(firstEmitted.await(10, TimeUnit.SECONDS));
            Assertions.assertEquals(1, cursors.size());
            Assertions.assertEquals("1", cursors.get(0).cursorAfter());
            Assertions.assertEquals(new MigrationMetrics(
                new MigrationMetrics.Operations(1, 0, 10), new MigrationMetrics.Operations(2, 0, 20),
                new MigrationMetrics.Requests(1, 2, 1, 200, 20)),
                cursors.get(0).metrics());
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void orderedSourcesFinishEarlierBatchesBeforeSendingLaterBatches() throws Exception {
        var source = new SparseSource(List.of(0, 1)) {
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
            public Mono<BatchResult> writeBatch(String collectionName, List<PositionedDocument> batch) {
                started.add(batch.get(0).document().id());
                firstStarted.countDown();
                return started.size() == 1 ? firstCompleted.asMono() : super.writeBatch(collectionName, batch);
            }
        };
        var result = new DocumentMigrationPipeline(source, sink, 1, 100)
            .migratePartition(partition(), IDX, null).collectList().toFuture();
        Assertions.assertTrue(firstStarted.await(10, TimeUnit.SECONDS));
        Assertions.assertEquals(List.of("doc-0"), started);
        firstCompleted.tryEmitValue(new BatchResult(1, 1, "1"));
        var cursors = result.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals(List.of("doc-0", "doc-1"), started);
        Assertions.assertEquals(2, cursors.get(1).metrics().source().upserts());
    }

    @Test
    void cursorsAdvanceInSourceOrderWhenWritesCompleteInReverseOrder() throws Exception {
        var writesStarted = new CountDownLatch(3);
        var completions = List.of(Sinks.<BatchResult>one(), Sinks.<BatchResult>one(), Sinks.<BatchResult>one());
        var sink = new CountingSink() {
            int next;

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<PositionedDocument> batch) {
                var completion = completions.get(next++);
                writesStarted.countDown();
                return completion.asMono();
            }
        };
        var future = new DocumentMigrationPipeline(
            new SparseSource(List.of(13, 17, 19)), sink, 1, 1000)
            .migratePartition(partition(), IDX, "10").collectList().toFuture();
        Assertions.assertTrue(writesStarted.await(10, TimeUnit.SECONDS));
        var cursorsAfter = List.of("14", "18", "20");
        for (int i = 2; i >= 0; i--) {
            completions.get(i).tryEmitValue(new BatchResult(1, 1, cursorsAfter.get(i)));
        }
        var cursors = future.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals(cursorsAfter, cursors.stream().map(ProgressCursor::cursorAfter).toList());
    }

    @Test
    void resubscribingStartsWithTheOriginalCursor() {
        var migration = new DocumentMigrationPipeline(
            new SparseSource(List.of(13, 17, 19)), new CountingSink(), 2, 1000)
            .migratePartition(partition(), IDX, "10").collectList();
        Assertions.assertEquals(migration.block(Duration.ofSeconds(10)), migration.block(Duration.ofSeconds(10)));
    }

    @Test
    void handoffAfterAMultiDocumentBatchStartsAtTheNextDocument() {
        var source = new SparseSource(List.of(3, 7, 9, 15, 20));
        var first = run(source, new CountingSink(), 3, null).get(0);
        var secondSink = new CountingSink();
        run(source, secondSink, 2, first.cursorAfter());
        Assertions.assertEquals(List.of("doc-15", "doc-20"), secondSink.written);
    }

    @Test
    void cursorFollowsTheLastDocumentOfTheBatch() {
        // batch size 2 -> batches are [3,7] and [9]
        var cursors = run(new SparseSource(List.of(3, 7, 9)), new CountingSink(), 2, null);

        Assertions.assertEquals(List.of("8", "10"),
            cursors.stream().map(ProgressCursor::cursorAfter).toList());
    }

    @Test
    void resumingAtTheCursorDoesNotReplayCompletedDocuments() {
        var sink = new CountingSink();
        var cursors = run(new SparseSource(List.of(3, 7, 9)), sink, 1, null);
        Assertions.assertEquals(List.of("doc-3", "doc-7", "doc-9"), sink.written);

        var sink2 = new CountingSink();
        run(new SparseSource(List.of(3, 7, 9)), sink2, 1, cursors.get(cursors.size() - 1).cursorAfter());
        Assertions.assertTrue(sink2.written.isEmpty());
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

        SparseSource(List<Integer> emittablePositions) {
            this.emittablePositions = emittablePositions;
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
        public Optional<Partition> findPartition(String collectionName, String partitionName) {
            return Optional.of(partition()).filter(p -> p.name().equals(partitionName));
        }

        @Override
        public CollectionMetadata readCollectionMetadata(String collectionName) {
            return new CollectionMetadata(IDX, 1, Map.of());
        }

        @Override
        public Flux<PositionedDocument> readDocuments(Partition p, String startingCursor) {
            // The cursor is the next doc id to read, as in LuceneSnapshotSource.
            long start = startingCursor == null ? 0 : Long.parseLong(startingCursor);
            return Flux.fromIterable(emittablePositions)
                .filter(pos -> pos >= start)
                .map(pos -> new PositionedDocument(new Document(
                    "doc-" + pos,
                    new byte[] { 1 },
                    Document.Operation.UPSERT,
                    Map.of(),
                    Map.of()), Long.toString(pos + 1L)));
        }
    }

    private static class CountingSink implements DocumentSink {
        final List<String> written = new ArrayList<>();

        @Override
        public Mono<Void> createCollection(CollectionMetadata metadata) {
            return Mono.empty();
        }

        @Override
        public Mono<BatchResult> writeBatch(String collectionName, List<PositionedDocument> batch) {
            batch.forEach(p -> written.add(p.document().id()));
            return Mono.just(new BatchResult(batch.size(), batch.size(), batch.get(batch.size() - 1).cursorAfter()));
        }
    }

    private static List<ProgressCursor> run(DocumentSource src, DocumentSink sink,
                                            int batchSize, String from) {
        var pipeline = new DocumentMigrationPipeline(src, sink, batchSize, 1_000_000L);
        return pipeline.migratePartition(partition(), IDX, from).collectList().block();
    }
}
