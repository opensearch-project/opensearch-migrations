package com.rfs.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class DocumentReindexer {
    private static final Logger logger = LogManager.getLogger(DocumentReindexer.class);
    protected final OpenSearchClient client;
    private final int maxDocsPerBulkRequest;
    private final long maxBytesPerBulkRequest;
    private final int maxConcurrentWorkItems;

    public Mono<Void> reindex(
        String indexName,
        Flux<Document> documentStream,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        return documentStream
            .map(this::convertDocumentToBulkSection)
            .bufferUntil(new Predicate<>() {
                private int currentItemCount = 0;
                private long currentSize = 0;

                @Override
                public boolean test(String next) {
                    // TODO: Move to Bytebufs to convert from string to bytes only once
                    // Add one for newline between bulk sections
                    var nextSize = next.getBytes(StandardCharsets.UTF_8).length + 1L;
                    currentSize += nextSize;
                    currentItemCount++;

                    if (currentItemCount > maxDocsPerBulkRequest || currentSize > maxBytesPerBulkRequest) {
                        // Reset and return true to signal to stop buffering.
                        // Current item is included in the current buffer
                        currentItemCount = 1;
                        currentSize = nextSize;
                        return true;
                    }
                    return false;
                }
            }, true)
             .parallel(
                maxConcurrentWorkItems, // Number of parallel workers, tested in reindex_shouldRespectMaxConcurrentRequests
                maxConcurrentWorkItems // Limit prefetch for memory pressure
            )
            .flatMap(
                bulkSections -> client
                    .sendBulkRequest(indexName,
                        this.convertToBulkRequestBody(bulkSections),
                        context.createBulkRequest()) // Send the request
                    .doFirst(() -> logger.info("{} documents in current bulk request.", bulkSections.size()))
                    .doOnSuccess(unused -> logger.debug("Batch succeeded"))
                    .doOnError(error -> logger.error("Batch failed", error))
                    // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                    .onErrorResume(e -> Mono.empty()),
            false,
                1, // control concurrency on parallel rails
                1 // control prefetch across all parallel runners
            )
            .doOnComplete(() -> logger.debug("All batches processed"))
            .then();
    }

    private String convertDocumentToBulkSection(Document document) {
        String id = Uid.decodeId(document.getBinaryValue("_id").bytes);
        String source = document.getBinaryValue("_source").utf8ToString();
        String action = "{\"index\": {\"_id\": \"" + id + "\"}}";

        return action + "\n" + source;
    }

    private String convertToBulkRequestBody(List<String> bulkSections) {
        StringBuilder builder = new StringBuilder();
        for (String section : bulkSections) {
            builder.append(section).append("\n");
        }
        return builder.toString();
    }
}
