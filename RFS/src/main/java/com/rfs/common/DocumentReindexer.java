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
        // Build up to 2x the allowed concurrent requests
        final int requestBuffer = maxConcurrentRequests * 2;

        return documentStream
            .map(this::convertDocumentToBulkSection)
            .bufferWhile(statefulSizeSegmentingPredicate(numDocsPerBulkRequest, numBytesPerBulkRequest))
            .subscribeOn(Schedulers.parallel()) // Initiate request scheduling in parallel
            .limitRate(requestBuffer) // Reduce memory and cpu pressure by limiting request building
            .flatMap(
                bulkSections -> client
                    .sendBulkRequest(indexName,
                        this.convertToBulkRequestBody(bulkSections),
                        context.createBulkRequest()) // Send the request
                    .doOnRequest(unused -> logger.info("{} documents in current bulk request.", bulkSections.size()))
                    .doOnSuccess(unused -> logger.debug("Batch succeeded"))
                    .doOnError(error -> logger.error("Batch failed", error))
                    // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                    .onErrorResume(e -> Mono.empty()),
                maxConcurrentRequests)
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
                currentItemCount++;
                // TODO: Move to Bytebufs to convert from string to bytes only once
                // Add one for newline between bulk sections
                currentSize += next.getBytes(StandardCharsets.UTF_8).length + 1;

                // Return true to keep buffering while conditions are met
                if (currentSize == 0 ||
                    (currentItemCount <= maxItems && currentSize <= maxSizeInBytes)) {
                    return true;
                }

                // Reset and return false to signal to stop buffering.
                // Next item is excluded from current buffer
                currentItemCount = 0;
                currentSize = 0;
                return false;
            }
        };
    }
}
