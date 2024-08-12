package com.rfs.common;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.migrations.parsing.BulkResponseParser;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.DocumentReindexer.BulkDocSection;
import com.rfs.common.http.ConnectionContext;
import com.rfs.common.http.HttpResponse;
import com.rfs.tracing.IRfsContexts;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class OpenSearchClient {
    private static final Logger logger = LogManager.getLogger(OpenSearchClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final RestClient client;

    public OpenSearchClient(ConnectionContext connectionContext) {
        this(new RestClient(connectionContext));
    }

    OpenSearchClient(RestClient client) {
        this.client = client;
    }

    /*
     * Create a legacy template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createLegacyTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        String targetPath = "_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Create a component template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createComponentTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        String targetPath = "_component_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Create an index template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createIndexTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        String targetPath = "_index_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Create an index if it does not already exist.  Returns an Optional; if the index was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createIndex(
        String indexName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        String targetPath = indexName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    Retry checkIfItemExistsRetryStrategy = Retry.backoff(3, Duration.ofSeconds(1))
        .maxBackoff(Duration.ofSeconds(10));
    Retry createItemExistsRetryStrategy = Retry.backoff(3, Duration.ofSeconds(1))
        .maxBackoff(Duration.ofSeconds(10))
        .filter(throwable -> !(throwable instanceof InvalidResponse)); // Do not retry on this exception

    private Optional<ObjectNode> createObjectIdempotent(
        String objectPath,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        HttpResponse getResponse = client.getAsync(objectPath, context.createCheckRequestContext())
            .flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_NOT_FOUND || resp.statusCode == HttpURLConnection.HTTP_OK) {
                    return Mono.just(resp);
                } else {
                    String errorMessage = ("Could not create object: "
                        + objectPath
                        + ". Response Code: "
                        + resp.statusCode
                        + ", Response Message: "
                        + resp.statusText
                        + ", Response Body: "
                        + resp.body);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(checkIfItemExistsRetryStrategy)
            .block();

        assert getResponse != null : ("getResponse should not be null; it should either be a valid response or an exception"
            + " should have been thrown.");
        boolean objectDoesNotExist = getResponse.statusCode == HttpURLConnection.HTTP_NOT_FOUND;
        if (objectDoesNotExist) {
            client.putAsync(objectPath, settings.toString(), context.createCheckRequestContext()).flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                    return Mono.just(resp);
                } else if (resp.statusCode == HttpURLConnection.HTTP_BAD_REQUEST) {
                    return Mono.error(
                        new InvalidResponse("Create object failed for " + objectPath + "\r\n" + resp.body, resp)
                    );
                } else {
                    String errorMessage = ("Could not create object: "
                        + objectPath
                        + ". Response Code: "
                        + resp.statusCode
                        + ", Response Message: "
                        + resp.statusText
                        + ", Response Body: "
                        + resp.body);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
                .doOnError(e -> logger.error(e.getMessage()))
                .retryWhen(createItemExistsRetryStrategy)
                .block();

            return Optional.of(settings);
        }
        // The only response code that can end up here is HTTP_OK, which means the object already existed
        return Optional.empty();
    }

    Retry snapshotRetryStrategy = Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10));

    /*
     * Attempts to register a snapshot repository; no-op if the repo already exists.
     */
    public void registerSnapshotRepo(
        String repoName,
        ObjectNode settings,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        String targetPath = "_snapshot/" + repoName;
        client.putAsync(targetPath, settings.toString(), context.createRegisterRequest()).flatMap(resp -> {
            if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = ("Could not register snapshot repo: "
                    + targetPath
                    + ". Response Code: "
                    + resp.statusCode
                    + ", Response Message: "
                    + resp.statusText
                    + ", Response Body: "
                    + resp.body);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(snapshotRetryStrategy)
            .block();
    }

    /*
     * Attempts to create a snapshot; no-op if the snapshot already exists.
     */
    public void createSnapshot(
        String repoName,
        String snapshotName,
        ObjectNode settings,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        client.putAsync(targetPath, settings.toString(), context.createSnapshotContext()).flatMap(resp -> {
            if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = ("Could not create snapshot: "
                    + targetPath
                    + ". Response Code: "
                    + resp.statusCode
                    + ", Response Message: "
                    + resp.statusText
                    + ", Response Body: "
                    + resp.body);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(snapshotRetryStrategy)
            .block();
    }

    /*
     * Get the status of a snapshot.  Returns an Optional; if the snapshot was found, it will be the snapshot status
     * and empty otherwise.
     */
    public Optional<ObjectNode> getSnapshotStatus(
        String repoName,
        String snapshotName,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        var getResponse = client.getAsync(targetPath, context.createGetSnapshotContext()).flatMap(resp -> {
            if (resp.statusCode == HttpURLConnection.HTTP_OK || resp.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return Mono.just(resp);
            } else {
                String errorMessage = "Could get status of snapshot: "
                    + targetPath
                    + ". Response Code: "
                    + resp.statusCode
                    + ", Response Body: "
                    + resp.body;
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(snapshotRetryStrategy)
            .block();

        assert getResponse != null : ("getResponse should not be null; it should either be a valid response or an exception"
            + " should have been thrown.");
        if (getResponse.statusCode == HttpURLConnection.HTTP_OK) {
            try {
                return Optional.of(objectMapper.readValue(getResponse.body, ObjectNode.class));
            } catch (Exception e) {
                String errorMessage = "Could not parse response for: _snapshot/" + repoName + "/" + snapshotName;
                throw new OperationFailed(errorMessage, getResponse);
            }
        } else if (getResponse.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
            return Optional.empty();
        } else {
            String errorMessage = "Should not have gotten here while parsing response for: _snapshot/"
                + repoName
                + "/"
                + snapshotName;
            throw new OperationFailed(errorMessage, getResponse);
        }
    }

    Retry getBulkRetryStrategy() {
        return Retry.backoff(6, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(60));
    }

    public Mono<BulkResponse> sendBulkRequest(String indexName, List<BulkDocSection> docs, IRfsContexts.IRequestContext context) {
        String targetPath = indexName + "/_bulk";
        var maxAttempts = 6;

        Map<String, BulkDocSection> docsMap = docs.stream().collect(Collectors.toMap(d -> d.getDocId(), d -> d));
        for (int i = 0; i < maxAttempts; i++) {

            logger.warn("Creating bulk body with " + docsMap.keySet());
            if (docsMap.isEmpty()) {
                return Mono.empty();
            }
            var body = BulkDocSection.convertToBulkRequestBody(docsMap.values());
            try {
                var outerResponse = client.postAsync(targetPath, body, context)
                    .flatMap(response -> {
                        var resp = new BulkResponse(response.statusCode, response.statusText, response.headers, response.body);
                        if (!resp.hasBadStatusCode() && !resp.hasFailedOperations()) {
                            return Mono.just(resp);
                        }
                        // Remove all successful documents for the next bulk request attempt
                        resp.getSuccessfulDocs().forEach(docsMap::remove);

                        logger.error(resp.getFailureMessage());
                        return Mono.error(new OperationFailed(resp.getFailureMessage(), resp));
                    }).blockOptional();

                if (outerResponse.isPresent()) {
                    return Mono.just(outerResponse.get());
                }
            } catch (OperationFailed of) {
                if (i + 1 < maxAttempts) {
                    continue;
                }
                throw of;
            }
        }
        return Mono.empty();
    }

    public HttpResponse refresh(IRfsContexts.IRequestContext context) {
        String targetPath = "_refresh";
        return client.get(targetPath, context);
    }

    public static class BulkResponse extends HttpResponse {
        public BulkResponse(int statusCode, String statusText, Map<String, String> headers, String body) {
            super(statusCode, statusText, headers, body);
        }

        public boolean hasBadStatusCode() {
            return !(statusCode == HttpURLConnection.HTTP_OK || statusCode == HttpURLConnection.HTTP_CREATED);
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

        public List<String> getSuccessfulDocs() {
            try {
                return BulkResponseParser.findSuccessDocs(body);
            } catch (IOException ioe) {
                logger.warn("Unable to process bulk request for success", ioe);
                return List.of();
            }
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

    public static class OperationFailed extends RfsException {
        public final HttpResponse response;

        public OperationFailed(String message, HttpResponse response) {
            super(message);

            this.response = response;
        }
    }
}
