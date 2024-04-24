package com.rfs.common;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;


public class DocumentReindexer {
    private static final Logger logger = LogManager.getLogger(DocumentReindexer.class);
    private static final int MAX_BATCH_SIZE = 1000; // Arbitrarily chosen

    public static void reindex(String indexName, Flux<Document> documentStream, ConnectionDetails targetConnection) throws Exception {
        String targetUrl = "/" + indexName + "/_bulk";
        HttpClient client = HttpClient.create()
            .host(targetConnection.hostName)
            .port(targetConnection.port)
            .headers(h -> {
                h.set("Content-Type", "application/json");
                if (targetConnection.authType == ConnectionDetails.AuthType.BASIC) {
                    String credentials = targetConnection.username + ":" + targetConnection.password;
                    String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
                    h.set("Authorization", "Basic " + encodedCredentials);
                }
            });

        documentStream
            .map(DocumentReindexer::convertDocumentToBulkSection)  // Convert each Document to part of a bulk operation
            .buffer(MAX_BATCH_SIZE) // Collect until you hit the batch size
            .map(DocumentReindexer::convertToBulkRequestBody)  // Assemble the bulk request body from the parts
            .flatMap(bulkJson -> sendBulkRequest(client, targetUrl, bulkJson)) // Send the request
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(5)))
            .subscribe(
                response -> logger.info("Batch uploaded successfully"),
                error -> logger.error("Failed to upload batch", error)
            );
    }

    private static String convertDocumentToBulkSection(Document document) {
        String id = Uid.decodeId(document.getBinaryValue("_id").bytes);
        String source = document.getBinaryValue("_source").utf8ToString();
        String action = "{\"index\": {\"_id\": \"" + id + "\"}}";

        return action + "\n" + source;
    }

    private static String convertToBulkRequestBody(List<String> bulkSections) {
        logger.info(bulkSections.size() + " documents in current bulk request");
        StringBuilder builder = new StringBuilder();
        for (String section : bulkSections) {
            builder.append(section).append("\n");
        }
        return builder.toString();
    }

    private static Mono<Void> sendBulkRequest(HttpClient client, String url, String bulkJson) {
        return client.post()
                .uri(url)
                .send(Flux.just(Unpooled.wrappedBuffer(bulkJson.getBytes())))
                .responseSingle((res, content) -> 
                    content.asString()  // Convert the response content to a string
                    .map(body -> new BulkResponseDetails(res.status().code(), body))  // Map both status code and body into a response details object
                )
                .flatMap(responseDetails -> {
                    // Something bad happened with our request, log it
                    if (responseDetails.hasBadStatusCode()) {
                        logger.error(responseDetails.getFailureMessage());
                    }
                    // Some of the bulk operations failed, log it
                    else if (responseDetails.hasFailedOperations()) {
                        logger.error(responseDetails.getFailureMessage());
                    }
                    return Mono.just(responseDetails);
                })
                .doOnError(err -> {
                    // We weren't even able to complete the request, log it
                    logger.error("Bulk request failed", err);
                })
                .then();
    }

    public static void refreshAllDocuments(ConnectionDetails targetConnection) throws Exception {
        // Send the request
        RestClient client = new RestClient(targetConnection);
        client.get("_refresh", false);
    }

    static class BulkResponseDetails {
        public final int statusCode;
        public final String body;
    
        BulkResponseDetails(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public boolean hasBadStatusCode() {
            return !(statusCode == 200 || statusCode == 201);
        }

        public boolean hasFailedOperations() {
            return body.contains("\"errors\":true");
        }

        public String getFailureMessage() {
            String failureMessage;
            if (hasBadStatusCode()) {
                failureMessage = "Bulk request failed.  Status code: " + statusCode + ", Response body: " + body;
            } else {
                failureMessage = "Bulk request succeeded, but some operations failed.  Response body: " + body;
            }

            return failureMessage;
        }
    }
}
