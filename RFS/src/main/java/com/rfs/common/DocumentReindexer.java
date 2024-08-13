package com.rfs.common;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RequiredArgsConstructor
public class DocumentReindexer {
    private static final Logger logger = LogManager.getLogger(DocumentReindexer.class);
    protected final OpenSearchClient client;
    private final int numDocsPerBulkRequest;
    private final long numBytesPerBulkRequest;
    private final int maxConcurrentRequests;

    public Mono<Void> reindex(
        String indexName,
        Flux<Document> documentStream,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        return documentStream
            .map(this::convertDocumentToBulkSection)
            .subscribeOn(Schedulers.parallel()) // Initiate request scheduling in parallel
            .bufferUntil(statefulSizeSegmentingPredicate(numDocsPerBulkRequest, numBytesPerBulkRequest), true)
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
                maxConcurrentRequests,
                2) // Prefetch up to 2 requests per connection to limit memory pressure
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

    private static java.util.function.Predicate<String> statefulSizeSegmentingPredicate(int maxItems, long maxSizeInBytes) {
        return new java.util.function.Predicate<>() {
            private int currentItemCount = 0;
            private long currentSize = 0;

            @Override
            public boolean test(String next) {
                // TODO: Move to Bytebufs to convert from string to bytes only once
                // Add one for newline between bulk sections
                var nextSize = next.getBytes(StandardCharsets.UTF_8).length + 1L;
                currentSize += nextSize;
                currentItemCount++;

                if (currentItemCount > maxItems || currentSize > maxSizeInBytes) {
                    // Reset and return true to signal to stop buffering.
                    // Current item is included in the current buffer
                    currentItemCount = 1;
                    currentSize = nextSize;
                    return true;
                }
                return false;
            }
        };
    }
}
