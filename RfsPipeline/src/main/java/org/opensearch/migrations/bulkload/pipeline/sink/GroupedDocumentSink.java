package org.opensearch.migrations.bulkload.pipeline.sink;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import reactor.core.publisher.Mono;

/**
 * A sink that can preserve source/transform batch boundaries while packing their output together.
 */
public interface GroupedDocumentSink extends DocumentSink {

    /**
     * The number of source batches that the pipeline should offer to one sink write.
     */
    int preferredSourceBatchGroupSize();

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
}
