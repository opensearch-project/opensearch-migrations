package org.opensearch.migrations.bulkload.pipeline.sink;

import java.util.List;
import java.util.Objects;

import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import reactor.core.publisher.Mono;

/**
 * A sink that can preserve source/transform batch boundaries while packing their output together.
 */
public interface GroupedDocumentSink<P> extends DocumentSink {

    /**
     * A source document together with the exact size and optional sink-specific preparation computed for it.
     */
    record PreparedDocument<P>(Document document, long sizeBytes, P preparation) {
        public PreparedDocument {
            Objects.requireNonNull(document, "document must not be null");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must be nonnegative, got " + sizeBytes);
            }
        }
    }

    /**
     * The number of source batches that the pipeline should offer to one sink write.
     */
    int preferredSourceBatchGroupSize();

    /**
     * Size one source document for the pipeline's first batching pass.
     *
     * <p>The default preserves source-oriented batching. A sink may return its exact encoded size when that is
     * knowable before transformation, allowing the pipeline to make one tightly packed target request per source
     * batch without a second split or a partially filled request at an intermediate group boundary.
     */
    default long sourceBatchBytes(String collectionName, Document document) {
        return document.sourceLength();
    }

    /**
     * Prepare and size one source document for batching.
     *
     * <p>The pipeline carries this value with the document so the sink can reuse preparation performed while
     * sizing instead of retaining it in shared mutable state.
     */
    default PreparedDocument<P> prepareSourceDocument(String collectionName, Document document) {
        return new PreparedDocument<>(document, sourceBatchBytes(collectionName, document), null);
    }

    /**
     * Write a bounded group of source batches.
     *
     * <p>Each inner list remains a distinct source/transform batch. The sink may flatten the output after
     * independently processing each inner batch.
     *
     * @param collectionName      the target collection name
     * @param sourceBatches       non-empty source batches in source order
     * @param requestConcurrency  maximum request concurrency available within this group
     */
    Mono<BatchResult> writeBatchGroup(
        String collectionName,
        List<List<Document>> sourceBatches,
        int requestConcurrency
    );

    /**
     * Write source batches with their carried sizing annotations.
     *
     * <p>The default keeps existing grouped sinks source-compatible; sinks that prepare data while sizing can
     * override this method to consume that preparation.
     */
    default Mono<BatchResult> writePreparedBatchGroup(
        String collectionName,
        List<List<PreparedDocument<P>>> sourceBatches,
        int requestConcurrency
    ) {
        var documents = sourceBatches.stream()
            .map(batch -> batch.stream().map(PreparedDocument::document).toList())
            .toList();
        return writeBatchGroup(collectionName, documents, requestConcurrency);
    }
}
