package org.opensearch.migrations.bulkload.pipeline.sink;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;

import reactor.core.publisher.Mono;

/**
 * Port for writing documents to any target — OpenSearch, Elasticsearch, S3, or test sink.
 *
 * <p>The sink is decoupled from the source's partitioning strategy. It receives a target
 * collection name and a batch of documents — it does not know or care how the source
 * organized its data into partitions.
 */
public interface DocumentSink extends AutoCloseable {

    /**
     * Create a collection on the target with the given metadata.
     * Idempotent — calling with the same metadata twice should not fail.
     */
    Mono<Void> createCollection(CollectionMetadata metadata);

    /**
     * Write a batch of documents to the target collection.
     *
     * <p>Documents arrive paired with the source cursor that resumes after each of them. The sink
     * writes the documents and reports back the cursor it wrote through, which the pipeline turns
     * into a {@link org.opensearch.migrations.bulkload.pipeline.model.ProgressCursor}. The sink
     * never interprets the cursor.
     *
     * @param collectionName the target collection name
     * @param batch          the documents to write, must not be empty
     * @return batch-local stats (docs written, bytes written, cursor written through)
     */
    Mono<BatchResult> writeBatch(String collectionName, List<PositionedDocument> batch);

    @Override
    default void close() throws Exception {
        // Default no-op
    }
}
