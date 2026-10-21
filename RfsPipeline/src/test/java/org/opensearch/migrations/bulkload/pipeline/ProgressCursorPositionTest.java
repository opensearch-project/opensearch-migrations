package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Sparse positions, inclusive resume, and ordered checkpoints under concurrent writes. */
class ProgressCursorPositionTest {

    private static final String IDX = "idx";

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
            cursors.stream().map(ProgressCursor::lastDocProcessed).toList());
    }

    @Test
    void resubscribingStartsWithTheOriginalOffset() {
        var migration = new DocumentMigrationPipeline(
            new SparseSource(List.of(13, 17, 19), false), new CountingSink(), 2, 1000)
            .migratePartition(partition(), IDX, 10).collectList();
        Assertions.assertEquals(migration.block(Duration.ofSeconds(10)), migration.block(Duration.ofSeconds(10)));
    }

    @Test
    void handoffAfterAMultiDocumentBatchReplaysOnlyItsLastDocument() {
        var source = new SparseSource(List.of(3, 7, 9, 15, 20), true);
        var first = run(source, new CountingSink(), 3, 0).get(0);
        var secondSink = new CountingSink();
        run(source, secondSink, 2, first.lastDocProcessed());
        Assertions.assertEquals(List.of("doc-9", "doc-15", "doc-20"), secondSink.written);
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
        Assertions.assertEquals(List.of(3L, 7L, 9L),
            cursors.stream().map(ProgressCursor::lastDocProcessed).toList(),
            "seek position must be the Lucene doc number of the last doc in each batch");
    }

    @Test
    void cursorIsLastPositionOfTheBatchWhenBatching() {
        // batch size 2 -> batches are [3,7] and [9]
        var cursors = run(new SparseSource(List.of(3, 7, 9), true), new CountingSink(), 2, 0);

        Assertions.assertNotNull(cursors);
        Assertions.assertEquals(List.of(7L, 9L),
            cursors.stream().map(ProgressCursor::lastDocProcessed).toList(),
            "each cursor is the position of the LAST document in its batch");
    }

    @Test
    void resumingAtTheCursorReplaysOnlyTheCheckpointDoc() {
        var sink = new CountingSink();
        var cursors = run(new SparseSource(List.of(3, 7, 9), true), sink, 1, 0);
        Assertions.assertNotNull(cursors);
        Assertions.assertEquals(List.of("doc-3", "doc-7", "doc-9"), sink.written);

        long resume = cursors.get(cursors.size() - 1).lastDocProcessed();
        Assertions.assertEquals(9L, resume, "checkpoint is the true Lucene position");

        // Successors deliberately restart AT the last checkpoint (see
        // RfsMigrateDocuments.getSuccessorWorkItemIds) to handle 1:many doc splits, so
        // exactly one document is replayed -- bounded, not the whole shard.
        var sink2 = new CountingSink();
        run(new SparseSource(List.of(3, 7, 9), true), sink2, 1, resume);
        Assertions.assertEquals(List.of("doc-9"), sink2.written,
            "only the checkpoint doc is replayed; pre-fix a cursor of 3 replayed all three");
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
            cursors.stream().map(ProgressCursor::lastDocProcessed).toList(),
            "no position metadata -> fall back to counting emitted docs");
    }

    @Test
    void denseSourceIsUnaffectedByTheFix() {
        // No nested children: positions 0..4 all emit. Cursor tracks position, which for a
        // dense source is also the doc ordinal -- this is why the bug went unnoticed.
        var cursors = run(new SparseSource(List.of(0, 1, 2, 3, 4), true), new CountingSink(), 1, 0);

        Assertions.assertNotNull(cursors);
        Assertions.assertEquals(List.of(0L, 1L, 2L, 3L, 4L),
            cursors.stream().map(ProgressCursor::lastDocProcessed).toList());
    }
}
