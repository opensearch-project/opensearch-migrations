package org.opensearch.migrations.bulkload.pipeline.sink;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;

import reactor.core.publisher.Mono;

/**
 * A collecting {@link DocumentSink} for testing the reading side without any real target cluster.
 *
 * <p>Source-side tests use this sink to capture all documents produced by a real snapshot reader,
 * then assert on the collected data. Thread-safe via {@link CopyOnWriteArrayList}.
 */
public class CollectingDocumentSink implements DocumentSink {

    private final List<IndexMetadataSnapshot> createdIndices = new CopyOnWriteArrayList<>();
    private final List<DocumentChange> collectedDocuments = new CopyOnWriteArrayList<>();
    private final List<ProgressCursor> cursors = new CopyOnWriteArrayList<>();

    @Override
    public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
        return Mono.fromRunnable(() -> createdIndices.add(metadata));
    }

    @Override
    public Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch) {
        return Mono.fromCallable(() -> {
            collectedDocuments.addAll(batch);
            long bytes = batch.stream()
                .mapToLong(d -> d.source() != null ? d.source().length : 0)
                .sum();
            var cursor = new ProgressCursor(shardId, batch.size(), batch.size(), bytes);
            cursors.add(cursor);
            return cursor;
        });
    }

    /** All indices created via {@link #createIndex}. */
    public List<IndexMetadataSnapshot> getCreatedIndices() {
        return Collections.unmodifiableList(createdIndices);
    }

    /** All documents collected across all batches. */
    public List<DocumentChange> getCollectedDocuments() {
        return Collections.unmodifiableList(collectedDocuments);
    }

    /** All progress cursors emitted, one per batch. */
    public List<ProgressCursor> getCursors() {
        return Collections.unmodifiableList(cursors);
    }

    /** Reset all collected state. Useful for reuse across test cases. */
    public void reset() {
        createdIndices.clear();
        collectedDocuments.clear();
        cursors.clear();
    }
}
