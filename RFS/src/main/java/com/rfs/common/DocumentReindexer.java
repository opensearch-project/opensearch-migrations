package com.rfs.common;

import java.time.Duration;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;


public class DocumentReindexer {
    private static final Logger logger = LogManager.getLogger(DocumentReindexer.class);
    private static final LongCounter documentsProcessedCounter;
    private static final LongCounter batchesProcessedCounter;
    static {
        final var meter = GlobalOpenTelemetry.getMeter("DocumentReindexer");
        documentsProcessedCounter = meter.counterBuilder("documentsProcessed")
            .setDescription("Counts the number of documents processed")
            .setUnit("1")
            .build();
        batchesProcessedCounter = meter.counterBuilder("batchesProcessed")
            .setDescription("Counts the number of batches processed")
            .setUnit("1")
            .build();
    }


    private static final int MAX_BATCH_SIZE = 1000; // Arbitrarily chosen

    @WithSpan
    public static Mono<Void> reindex(@SpanAttribute("indexName") String indexName, Flux<Document> documentStream, OpenSearchClient client) throws Exception {   

        return documentStream
            .map(DocumentReindexer::convertDocumentToBulkSection)  // Convert each Document to part of a bulk operation
            .map(d -> { documentsProcessedCounter.add(1); return d; })
            .buffer(MAX_BATCH_SIZE) // Collect until you hit the batch size
            .doOnNext(bulk -> { logger.info(bulk.size() + " documents in current bulk request"); batchesProcessedCounter.add(1); })
            .map(DocumentReindexer::convertToBulkRequestBody)  // Assemble the bulk request body from the parts
            .flatMap(bulkJson -> client.sendBulkRequest(indexName, bulkJson) // Send the request
                .doOnSuccess(unused -> logger.debug("Batch succeeded"))
                .doOnError(error -> logger.error("Batch failed", error))
                .onErrorResume(e -> Mono.empty()) // Prevent the error from stopping the entire stream
            )
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(5)))
            .doOnComplete(() -> logger.debug("All batches processed"))
            .then();
    }

    private static String convertDocumentToBulkSection(Document document) {
        String id = Uid.decodeId(document.getBinaryValue("_id").bytes);
        String source = document.getBinaryValue("_source").utf8ToString();
        String action = "{\"index\": {\"_id\": \"" + id + "\"}}";

        return action + "\n" + source;
    }

    private static String convertToBulkRequestBody(List<String> bulkSections) {
        StringBuilder builder = new StringBuilder();
        for (String section : bulkSections) {
            builder.append(section).append("\n");
        }
        return builder.toString();
    }

    public static void refreshAllDocuments(ConnectionDetails targetConnection) throws Exception {
        // Send the request
        OpenSearchClient client = new OpenSearchClient(targetConnection);
        client.refresh();
    }
}
