package com.rfs.common;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import lombok.NonNull;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class OpenSearchClient {
    private static final Logger logger = LogManager.getLogger(OpenSearchClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public final ConnectionDetails connectionDetails;
    private final RestClient client;

    public OpenSearchClient(@NonNull String url, UsernamePassword p) {
        this(url, p == null ? null : p.getUsername(), p == null ? null : p.getPassword(), false);
    }

    public OpenSearchClient(@NonNull String url, String username, String password, boolean insecure) {
        this(new ConnectionDetails(url, username, password, insecure));
    }

    public OpenSearchClient(ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;
        this.client = new RestClient(connectionDetails);
    }

    /*
     * Create a legacy template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createLegacyTemplate(String templateName, ObjectNode settings) {
        String targetPath = "_template/" + templateName;
        return createObjectIdempotent(targetPath, settings);
    }

    /*
     * Create a component template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createComponentTemplate(String templateName, ObjectNode settings) {
        String targetPath = "_component_template/" + templateName;
        return createObjectIdempotent(targetPath, settings);
    }

    /*
     * Create an index template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createIndexTemplate(String templateName, ObjectNode settings) {
        String targetPath = "_index_template/" + templateName;
        return createObjectIdempotent(targetPath, settings);
    }

    /*
     * Create an index if it does not already exist.  Returns an Optional; if the index was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createIndex(String indexName, ObjectNode settings) {
        String targetPath = indexName;
        return createObjectIdempotent(targetPath, settings);
    }

    private Optional<ObjectNode> createObjectIdempotent(String objectPath, ObjectNode settings) {
        RestClient.Response response = client.getAsync(objectPath).flatMap(resp -> {
            if (resp.code == HttpURLConnection.HTTP_NOT_FOUND || resp.code == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = ("Could not create object: "
                    + objectPath
                    + ". Response Code: "
                    + resp.code
                    + ", Response Message: "
                    + resp.message
                    + ", Response Body: "
                    + resp.body);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();

        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            client.put(objectPath, settings.toString());
            return Optional.of(settings);
        }
        // The only response code that can end up here is HTTP_OK, which means the object already existed
        return Optional.empty();
    }

    /*
     * Attempts to register a snapshot repository; no-op if the repo already exists.  
     */
    public void registerSnapshotRepo(String repoName, ObjectNode settings) {
        String targetPath = "_snapshot/" + repoName;
        client.putAsync(targetPath, settings.toString()).flatMap(resp -> {
            if (resp.code == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = ("Could not register snapshot repo: "
                    + targetPath
                    + ". Response Code: "
                    + resp.code
                    + ", Response Message: "
                    + resp.message
                    + ", Response Body: "
                    + resp.body);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();
    }

    /*
     * Attempts to create a snapshot; no-op if the snapshot already exists.
     */
    public void createSnapshot(String repoName, String snapshotName, ObjectNode settings) {
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        client.putAsync(targetPath, settings.toString()).flatMap(resp -> {
            if (resp.code == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = ("Could not create snapshot: "
                    + targetPath
                    + ". Response Code: "
                    + resp.code
                    + ", Response Message: "
                    + resp.message
                    + ", Response Body: "
                    + resp.body);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();
    }

    /*
     * Get the status of a snapshot.  Returns an Optional; if the snapshot was found, it will be the snapshot status
     * and empty otherwise.
     */
    public Optional<ObjectNode> getSnapshotStatus(String repoName, String snapshotName) {
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        RestClient.Response response = client.getAsync(targetPath).flatMap(resp -> {
            if (resp.code == HttpURLConnection.HTTP_OK || resp.code == HttpURLConnection.HTTP_NOT_FOUND) {
                return Mono.just(resp);
            } else {
                String errorMessage = "Could get status of snapshot: "
                    + targetPath
                    + ". Response Code: "
                    + resp.code
                    + ", Response Body: "
                    + resp.body;
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();

        if (response.code == HttpURLConnection.HTTP_OK) {
            try {
                return Optional.of(objectMapper.readValue(response.body, ObjectNode.class));
            } catch (Exception e) {
                String errorMessage = "Could not parse response for: _snapshot/" + repoName + "/" + snapshotName;
                throw new OperationFailed(errorMessage, response);
            }
        } else if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return Optional.empty();
        } else {
            String errorMessage = "Should not have gotten here while parsing response for: _snapshot/"
                + repoName
                + "/"
                + snapshotName;
            throw new OperationFailed(errorMessage, response);
        }
    }

    public Mono<BulkResponse> sendBulkRequest(String indexName, String body) {
        String targetPath = indexName + "/_bulk";

        return client.postAsync(targetPath, body)
            .map(response -> new BulkResponse(response.code, response.body, response.message))
            .flatMap(resp -> {
                if (resp.hasBadStatusCode() || resp.hasFailedOperations()) {
                    logger.error(resp.getFailureMessage());
                    return Mono.error(new OperationFailed(resp.getFailureMessage(), resp));
                }
                return Mono.just(resp);
            })
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)));
    }

    public RestClient.Response refresh() {
        String targetPath = "_refresh";

        return client.get(targetPath);
    }

    public static class BulkResponse extends RestClient.Response {
        public BulkResponse(int responseCode, String responseBody, String responseMessage) {
            super(responseCode, responseBody, responseMessage);
        }

        public boolean hasBadStatusCode() {
            return !(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED);
        }

        public boolean hasFailedOperations() {
            // The OpenSearch Bulk API response body is JSON and contains a top-level "errors" field that indicates
            // whether any of the individual operations in the bulk request failed. Rather than marshalling the entire
            // response as JSON, just check for the string value.

            String regexPattern = "\"errors\"\\s*:\\s*true";
            Pattern pattern = Pattern.compile(regexPattern);
            Matcher matcher = pattern.matcher(body);
            return matcher.find();
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

    public static class OperationFailed extends RfsException {
        public final RestClient.Response response;

        public OperationFailed(String message, RestClient.Response response) {
            super(message);

            this.response = response;
        }
    }
}
