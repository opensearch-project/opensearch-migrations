package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import org.opensearch.migrations.bulkload.pipeline.sink.GroupedDocumentSink;
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
                new MigrationMetrics.Operations(10, 0, 10),
                new MigrationMetrics.Requests(1, 1, 0, 100, 10),
                new MigrationMetrics.Failures(5, 5)));
            Assertions.assertTrue(cursors.isEmpty());
            completions.get(0).tryEmitValue(new BatchResult(1, 10,
                new MigrationMetrics.Operations(2, 0, 20),
                new MigrationMetrics.Requests(1, 2, 1, 200, 20),
                new MigrationMetrics.Failures(1, 0)));
            Assertions.assertTrue(firstEmitted.await(10, TimeUnit.SECONDS));
            Assertions.assertEquals(1, cursors.size());
            Assertions.assertEquals(1, cursors.get(0).nextPosition());
            // The failures of the batch past the gap aren't committed either.
            Assertions.assertEquals(new MigrationMetrics(
                new MigrationMetrics.Operations(1, 0, 10), new MigrationMetrics.Operations(2, 0, 20),
                new MigrationMetrics.Requests(1, 2, 1, 200, 20), new MigrationMetrics.Failures(1, 0)),
                cursors.get(0).metrics());
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void orderedSourcesRunEachOperationPhaseConcurrentlyButAwaitItsCompletion() throws Exception {
        var source = new SparseSource(List.of(0, 1), true) {
            @Override
            public boolean requiresOrderedWrites() {
                return true;
            }

            @Override
            public Flux<Document> readDocuments(Partition partition, long start) {
                return Flux.just(
                    new Document("delete-0", new byte[] { 1 }, Document.Operation.DELETE, Map.of(), Map.of()),
                    new Document("delete-1", new byte[] { 1 }, Document.Operation.DELETE, Map.of(), Map.of()),
                    new Document("upsert-0", new byte[] { 1 }, Document.Operation.UPSERT, Map.of(), Map.of()),
                    new Document("upsert-1", new byte[] { 1 }, Document.Operation.UPSERT, Map.of(), Map.of())
                );
            }
        };
        var deletesStarted = new CountDownLatch(2);
        var upsertsStarted = new CountDownLatch(2);
        var deleteCompletions = List.of(Sinks.<BatchResult>one(), Sinks.<BatchResult>one());
        var started = new CopyOnWriteArrayList<String>();
        var sink = new CountingSink() {
            int nextDelete;

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                var document = batch.get(0);
                started.add(document.id());
                if (document.operation() == Document.Operation.DELETE) {
                    deletesStarted.countDown();
                    return deleteCompletions.get(nextDelete++).asMono();
                }
                upsertsStarted.countDown();
                return Mono.just(new BatchResult(1, 1));
            }
        };
        var result = new DocumentMigrationPipeline(source, sink, 1, 100, 1, 2)
            .migratePartition(partition(), IDX, 0).collectList().toFuture();
        boolean bothDeletesRanConcurrently = deletesStarted.await(1, TimeUnit.SECONDS);
        deleteCompletions.get(0).tryEmitValue(new BatchResult(1, 1));
        Assertions.assertFalse(upsertsStarted.await(200, TimeUnit.MILLISECONDS),
            "No upsert may start while any delete request remains incomplete");
        deleteCompletions.get(1).tryEmitValue(new BatchResult(1, 1));

        var cursors = result.get(10, TimeUnit.SECONDS);
        Assertions.assertTrue(bothDeletesRanConcurrently,
            "Ordered sources should retain concurrency within a write phase");
        Assertions.assertEquals(List.of("delete-0", "delete-1"), started.subList(0, 2));
        Assertions.assertEquals(2, cursors.get(3).metrics().source().upserts());
        Assertions.assertEquals(2, cursors.get(3).metrics().source().deletes());
    }

    @Test
    void persistentLanesAdvanceCheckpointsOnlyAcrossTheCompletedSourcePrefix() throws Exception {
        var documents = List.of(
            positioned("doc-0", 0, Document.Operation.UPSERT),
            positioned("doc-1", 1, Document.Operation.UPSERT),
            positioned("doc-2", 2, Document.Operation.UPSERT),
            positioned("doc-3", 3, Document.Operation.UPSERT)
        );
        var source = source(documents, false);
        var started = new CountDownLatch(documents.size());
        var completions = new ConcurrentHashMap<String, Sinks.One<BatchResult>>();
        documents.forEach(document -> completions.put(document.id(), Sinks.one()));
        var cursors = new CopyOnWriteArrayList<ProgressCursor>();

        var sink = new GroupedCountingSink() {
            @Override
            public Mono<BatchResult> writePreparedBatchGroup(
                String collectionName,
                List<List<PreparedDocument<String>>> sourceBatches,
                int requestConcurrency
            ) {
                var document = sourceBatches.get(0).get(0).document();
                started.countDown();
                return completions.get(document.id()).asMono();
            }
        };

        var subscription = new DocumentMigrationPipeline(source, sink, 1, 100, 1, 4)
            .migratePartition(partition(), IDX, 0)
            .subscribe(cursors::add);
        try {
            Assertions.assertTrue(started.await(10, TimeUnit.SECONDS));
            completions.get("doc-3").tryEmitValue(new BatchResult(1, 1));
            Assertions.assertTrue(cursors.isEmpty());
            completions.get("doc-0").tryEmitValue(new BatchResult(1, 1));
            awaitCursorCount(cursors, 1);
            Assertions.assertEquals(1, cursors.get(0).nextPosition());
            completions.get("doc-2").tryEmitValue(new BatchResult(1, 1));
            Assertions.assertEquals(1, cursors.size());
            completions.get("doc-1").tryEmitValue(new BatchResult(1, 1));
            awaitCursorCount(cursors, 4);
            Assertions.assertEquals(List.of(1L, 2L, 3L, 4L),
                cursors.stream().map(ProgressCursor::nextPosition).toList());
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void persistentLanesDrainEveryDeleteRequestBeforeSubmittingUpserts() throws Exception {
        var source = source(List.of(
            positioned("delete-0", 0, Document.Operation.DELETE),
            positioned("delete-1", 1, Document.Operation.DELETE),
            positioned("upsert-0", 2, Document.Operation.UPSERT),
            positioned("upsert-1", 3, Document.Operation.UPSERT)
        ), true);
        var deletesStarted = new CountDownLatch(2);
        var upsertsStarted = new CountDownLatch(2);
        var deleteCompletions = new ConcurrentHashMap<String, Sinks.One<BatchResult>>();
        deleteCompletions.put("delete-0", Sinks.one());
        deleteCompletions.put("delete-1", Sinks.one());

        var sink = new GroupedCountingSink() {
            @Override
            public Mono<BatchResult> writePreparedBatchGroup(
                String collectionName,
                List<List<PreparedDocument<String>>> sourceBatches,
                int requestConcurrency
            ) {
                var document = sourceBatches.get(0).get(0).document();
                if (document.operation() == Document.Operation.DELETE) {
                    deletesStarted.countDown();
                    return deleteCompletions.get(document.id()).asMono();
                }
                upsertsStarted.countDown();
                return Mono.just(new BatchResult(1, 1));
            }
        };

        var result = new DocumentMigrationPipeline(source, sink, 1, 100, 1, 4)
            .migratePartition(partition(), IDX, 0)
            .collectList()
            .toFuture();
        Assertions.assertTrue(deletesStarted.await(10, TimeUnit.SECONDS));
        Assertions.assertFalse(upsertsStarted.await(200, TimeUnit.MILLISECONDS));
        deleteCompletions.values().forEach(completion -> completion.tryEmitValue(new BatchResult(1, 1)));
        Assertions.assertTrue(upsertsStarted.await(10, TimeUnit.SECONDS));
        Assertions.assertEquals(4, result.get(10, TimeUnit.SECONDS).size());
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

    @Test
    void aSuccessorCommitsOnlyTheFailuresAfterItsPredecessorsCheckpoint() {
        // Every odd position fails. The first worker commits two batches and loses its lease; its successor resumes
        // at the checkpoint and finishes the shard.
        var source = new SparseSource(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), true);
        var sink = new CountingSink() {
            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                long failed = batch.stream().filter(d -> Integer.parseInt(d.id().substring("doc-".length())) % 2 == 1)
                    .count();
                return Mono.just(new BatchResult(batch.size(), batch.size(), MigrationMetrics.Operations.ZERO,
                    MigrationMetrics.Requests.ZERO, new MigrationMetrics.Failures(failed, 0)));
            }
        };
        var first = new DocumentMigrationPipeline(source, sink, 2, 1_000_000L)
            .migratePartition(partition(), IDX, 0).take(2).blockLast();
        var second = run(source, sink, 2, first.nextPosition());

        Assertions.assertEquals(4, first.nextPosition());
        Assertions.assertEquals(new MigrationMetrics.Failures(2, 0), first.metrics().failures());
        Assertions.assertEquals(new MigrationMetrics.Failures(3, 0), second.get(second.size() - 1).metrics().failures());
    }

    private static Partition partition() {
        return new EsShardPartition("snap", IDX, 0);
    }

    private static Document positioned(String id, long position, Document.Operation operation) {
        return new Document(
            id,
            new byte[] { 1 },
            operation,
            Map.of(),
            Map.of(Document.SOURCE_META_LUCENE_DOC_NUMBER, position)
        );
    }

    private static DocumentSource source(List<Document> documents, boolean orderedWrites) {
        return new SparseSource(List.of(), true) {
            @Override
            public boolean requiresOrderedWrites() {
                return orderedWrites;
            }

            @Override
            public Flux<Document> readDocuments(Partition partition, long start) {
                return Flux.fromIterable(documents)
                    .filter(document -> document.position().orElseThrow() >= start);
            }
        };
    }

    private static void awaitCursorCount(List<ProgressCursor> cursors, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (cursors.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Assertions.assertEquals(count, cursors.size());
    }

    private abstract static class GroupedCountingSink implements GroupedDocumentSink<String> {
        @Override
        public Mono<Void> createCollection(CollectionMetadata metadata) {
            return Mono.empty();
        }

        @Override
        public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
            throw new AssertionError("The grouped path must be used");
        }

        @Override
        public PreparedDocument<String> prepareSourceDocument(String collectionName, Document document) {
            return new PreparedDocument<>(document, 1, "prepared-" + document.id());
        }

        @Override
        public Mono<BatchResult> writeBatchGroup(
            String collectionName,
            List<List<Document>> sourceBatches,
            int requestConcurrency
        ) {
            throw new AssertionError("The prepared grouped path must be used");
        }
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
