package com.rfs.common;

import java.net.HttpURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;

public class OpenSearchClient {
    private static final Logger logger = LogManager.getLogger(OpenSearchClient.class);

    public static class BulkResponse extends RestClient.Response {
        public BulkResponse(int responseCode, String responseBody, String responseMessage) {
            super(responseCode, responseBody, responseMessage);
        }

        public boolean hasBadStatusCode() {
            return !(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED);
        }

        public boolean hasFailedOperations() {
            // The OpenSearch Bulk API response body is JSON and contains a top-level "errors" field that indicates
            // whether any of the individual operations in the bulk request failed.  Rather than marshalling the entire
            // response as JSON, just check for the string value.
            return body.contains("\"errors\":true");
        }

        public String getFailureMessage() {
            String failureMessage;
            if (hasBadStatusCode()) {
                failureMessage = "Bulk request failed.  Status code: " + code + ", Response body: " + body;
            } else {
                failureMessage = "Bulk request succeeded, but some operations failed.  Response body: " + body;
            }

            return failureMessage;
        }
    }

    public final ConnectionDetails connectionDetails;
    private final RestClient client;

    public OpenSearchClient(ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;
        this.client = new RestClient(connectionDetails);
    }

    /*
     * Create a legacy template if it does not already exist; return true if created, false otherwise.
     */
    public boolean createLegacyTemplateIdempotent(String templateName, ObjectNode settings){
        String targetPath = "_template/" + templateName;
        return createObjectIdempotent(targetPath, settings);
    }

    /*
     * Create a component template if it does not already exist; return true if created, false otherwise.
     */
    public boolean createComponentTemplateIdempotent(String templateName, ObjectNode settings){
        String targetPath = "_component_template/" + templateName;
        return createObjectIdempotent(targetPath, settings);
    }

    /*
     * Create an index template if it does not already exist; return true if created, false otherwise.
     */
    public boolean createIndexTemplateIdempotent(String templateName, ObjectNode settings){
        String targetPath = "_index_template/" + templateName;
        return createObjectIdempotent(targetPath, settings);
    }

    /*
     * Create an index if it does not already exist; return true if created, false otherwise.
     */
    public boolean createIndexIdempotent(String indexName, ObjectNode settings){
        String targetPath = indexName;
        return createObjectIdempotent(targetPath, settings);
    }

    private boolean createObjectIdempotent(String objectPath, ObjectNode settings){
        RestClient.Response response = client.get(objectPath, true);
        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            client.put(objectPath, settings.toString(), false);
            return true;
        } else if (response.code == HttpURLConnection.HTTP_OK) {
            logger.info(objectPath + " already exists. Skipping creation.");
        } else {
            logger.warn("Could not confirm that " + objectPath + " does not already exist. Skipping creation.");
        }
        return false;
    }

    public RestClient.Response registerSnapshotRepo(String repoName, ObjectNode settings){
        String targetPath = "_snapshot/" + repoName;
        return client.put(targetPath, settings.toString(), false);
    }

    public RestClient.Response createSnapshot(String repoName, String snapshotName, ObjectNode settings){
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        return client.put(targetPath, settings.toString(), false);
    }

    public RestClient.Response getSnapshotStatus(String repoName, String snapshotName){
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        return client.get(targetPath, false);
    }

    public Mono<BulkResponse> sendBulkRequest(String indexName, String body) {
        String targetPath = indexName + "/_bulk";

        return client.postAsync(targetPath, body)
            .map(response -> new BulkResponse(response.code, response.body, response.message))
            .flatMap(responseDetails -> {
                if (responseDetails.hasBadStatusCode() || responseDetails.hasFailedOperations()) {
                    logger.error(responseDetails.getFailureMessage());
                    return Mono.error(new RuntimeException(responseDetails.getFailureMessage()));
                }
                return Mono.just(responseDetails);
            });
    }

    public RestClient.Response refresh() {
        String targetPath = "_refresh";

        return client.get(targetPath, false);
    }
}
