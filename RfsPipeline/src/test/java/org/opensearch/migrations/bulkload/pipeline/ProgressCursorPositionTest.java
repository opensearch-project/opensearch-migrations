package org.opensearch.migrations.bulkload.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

/**
 * Pins the resume-cursor contract: {@link ProgressCursor#lastDocProcessed()} carries the
 * SOURCE POSITION of the last processed document (for Lucene sources, its doc number),
 * not a running count of emitted documents.
 *
 * <p>Why this matters. Every reader in the codebase interprets the resume checkpoint as a
 * Lucene doc id — {@code LuceneReader.readDocsFromSegment} computes
 * {@code position - segmentDocBase} and {@code getSegmentsFromStartingSegment} binary
 * searches it against segment doc bases. A running emitted-count silently disagrees with
 * that interpretation whenever the reader consumes a source position without emitting a
 * document, which is exactly what happens for nested child documents (they carry no
 * stored {@code _id}, so {@code LuceneReader.getDocument} returns null).
 *
 * <p>Before the fix, a shard of 3 roots interleaved with 4 nested children finished with a
 * cursor of 3 while the true Lucene position was 9 — so a resumed worker re-read and
 * re-migrated the entire shard. These tests fail against that behaviour.
 */
class ProgressCursorPositionTest {

    private static final String IDX = "idx";

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
        return run(src, sink, batchSize, from, 0L);
    }

    private static List<ProgressCursor> run(DocumentSource src, DocumentSink sink,
                                            int batchSize, long from, long carriedIn) {
        var pipeline = new DocumentMigrationPipeline(src, sink, batchSize, 1_000_000L);
        return pipeline.migratePartition(partition(), IDX, from, carriedIn).collectList().block();
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
    void bothQuantitiesAreTrackedIndependently() {
        var cursors = run(new SparseSource(List.of(3, 7, 9), true), new CountingSink(), 1, 0);

        Assertions.assertNotNull(cursors);
        // Seek position follows the sparse Lucene ids...
        Assertions.assertEquals(List.of(3L, 7L, 9L),
            cursors.stream().map(ProgressCursor::lastDocProcessed).toList(),
            "lastDocProcessed = seekable source position");
        // ...while the emitted count advances densely, one per document.
        Assertions.assertEquals(List.of(1L, 2L, 3L),
            cursors.stream().map(ProgressCursor::cumulativeDocsEmitted).toList(),
            "cumulativeDocsEmitted = live non-nested docs actually sent to the target");
    }

    @Test
    void emittedCountCarriesAcrossLeaseGenerations() {
        // Generation 1 processes doc 3 only, then the lease expires.
        var gen1 = run(new SparseSource(List.of(3, 7, 9), true), new CountingSink(), 1, 0);
        Assertions.assertNotNull(gen1);
        var checkpoint = gen1.get(0);
        Assertions.assertEquals(3L, checkpoint.lastDocProcessed());
        Assertions.assertEquals(1L, checkpoint.cumulativeDocsEmitted());

        // Generation 2 resumes at the checkpoint position, carrying the running total.
        // The checkpoint doc (3) is replayed by design, so the carried-in total must be the
        // count BEFORE that doc to avoid double-counting it.
        long carriedIn = checkpoint.cumulativeDocsEmitted() - checkpoint.docsInBatch();
        Assertions.assertEquals(0L, carriedIn);

        var sink2 = new CountingSink();
        var gen2 = run(new SparseSource(List.of(3, 7, 9), true), sink2, 1,
            checkpoint.lastDocProcessed(), carriedIn);

        Assertions.assertNotNull(gen2);
        Assertions.assertEquals(List.of("doc-3", "doc-7", "doc-9"), sink2.written,
            "generation 2 replays the checkpoint doc, then continues");
        Assertions.assertEquals(3L, gen2.get(gen2.size() - 1).cumulativeDocsEmitted(),
            "final total is 3 -- the replayed checkpoint doc is not double-counted");
    }

    @Test
    void cursorIsLastPositionOfTheBatchWhenBatching() {
        // batch size 2 -> batches are [3,7] and [9]
        var cursors = run(new SparseSource(List.of(3, 7, 9), true), new CountingSink(), 2, 0);

        Assertions.assertNotNull(cursors);
        Assertions.assertEquals(List.of(7L, 9L),
            cursors.stream().map(ProgressCursor::lastDocProcessed).toList(),
            "each cursor is the position of the LAST document in its batch");
        Assertions.assertEquals(List.of(2L, 3L),
            cursors.stream().map(ProgressCursor::cumulativeDocsEmitted).toList(),
            "emitted total accumulates per batch");
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
