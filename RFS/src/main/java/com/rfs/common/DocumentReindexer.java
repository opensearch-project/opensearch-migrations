package com.rfs.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class DocumentReindexer {
    private static final ObjectMapper objectMapper = new ObjectMapper();
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
                    .doFirst(() -> log.info("{} documents in current bulk request.", bulkSections.size()))
                    .doOnSuccess(unused -> log.debug("Batch succeeded"))
                    .doOnError(error -> log.error("Batch failed", error))
                    // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                    .onErrorResume(e -> Mono.empty()),
            false,
                1, // control concurrency on parallel rails
                1 // control prefetch across all parallel runners
            )
            .doOnComplete(() -> log.debug("All batches processed"))
            .then();
    }

    @SneakyThrows
    private String convertDocumentToBulkSection(Document document) {
        String id = Uid.decodeId(document.getBinaryValue("_id").bytes);

        String action = "{\"index\": {\"_id\": \"" + id + "\"}}";

        // We must ensure the _source document is a "minified" JSON string, otherwise the bulk request will be corrupted.
        // Specifically, we cannot have any leading or trailing whitespace, and the JSON must be on a single line.
        String trimmedSource = document.getBinaryValue("_source").utf8ToString().trim();
        Object jsonObject = objectMapper.readValue(trimmedSource, Object.class);
        String minifiedSource = objectMapper.writeValueAsString(jsonObject);

        return action + "\n" + minifiedSource;
    }

    private String convertToBulkRequestBody(List<String> bulkSections) {
        StringBuilder builder = new StringBuilder();
        for (String section : bulkSections) {
            builder.append(section).append("\n");
        }
        log.atDebug().setMessage("Bulk request body: \n{}").addArgument(builder::toString).log();

        return builder.toString();
    }
}
