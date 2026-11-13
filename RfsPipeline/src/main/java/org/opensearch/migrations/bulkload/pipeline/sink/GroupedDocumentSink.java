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
     * Prepare and size one source document for batching.
     *
     * <p>The pipeline carries this value with the document so the sink can reuse preparation performed while
     * sizing instead of retaining it in shared mutable state.
     */
    default PreparedDocument<P> prepareSourceDocument(String collectionName, Document document) {
        return new PreparedDocument<>(document, document.sourceLength(), null);
    }

    /**
     * Write source batches with their carried sizing annotations.
     *
     * <p>Each inner list remains a distinct source/transform batch. The sink may flatten the output after
     * independently processing each inner batch.
     *
     * @param collectionName      the target collection name
     * @param sourceBatches       non-empty source batches in source order
     * @param requestConcurrency  maximum request concurrency available within this group
     */
    Mono<BatchResult> writePreparedBatchGroup(
        String collectionName,
        List<List<PreparedDocument<P>>> sourceBatches,
        int requestConcurrency
    );
}
