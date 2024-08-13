package com.rfs.common;

import java.util.List;

import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final int numDocsPerBulkRequest;
    private final int maxConcurrentRequests;

    public Mono<Void> reindex(
        String indexName,
        Flux<Document> documentStream,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {

        return documentStream.map(this::convertDocumentToBulkSection)  // Convert each Document to part of a bulk
                                                                       // operation
            .buffer(numDocsPerBulkRequest) // Collect until you hit the batch size
            .doOnNext(bulk -> log.atInfo().log("{} documents in current bulk request", bulk.size()))
            .map(this::convertToBulkRequestBody)  // Assemble the bulk request body from the parts
            .flatMap(
                bulkJson -> client.sendBulkRequest(indexName, bulkJson, context.createBulkRequest()) // Send the request
                    .doOnSuccess(unused -> log.atDebug().log("Batch succeeded"))
                    .doOnError(error -> log.atError().log("Batch failed", error))
                    // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                    .onErrorResume(e -> Mono.empty()),
                maxConcurrentRequests)
            .doOnComplete(() -> log.atDebug().log("All batches processed"))
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
        log.atInfo().log("Bulk request body: \n{}", builder.toString());

        return builder.toString();
    }
}
