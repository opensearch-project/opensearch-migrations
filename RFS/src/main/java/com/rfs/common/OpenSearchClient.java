package com.rfs.common;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.parsing.BulkResponseParser;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.rfs.common.DocumentReindexer.BulkDocSection;
import com.rfs.common.http.ConnectionContext;
import com.rfs.common.http.HttpResponse;
import com.rfs.tracing.IRfsContexts;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
public class OpenSearchClient {

    protected static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int defaultMaxRetryAttempts = 3;
    private static final Duration defaultBackoff = Duration.ofSeconds(1);
    private static final Duration defaultMaxBackoff = Duration.ofSeconds(10);
    private static final Retry snapshotRetryStrategy = Retry.backoff(defaultMaxRetryAttempts, defaultBackoff)
        .maxBackoff(defaultMaxBackoff);
    protected static final Retry checkIfItemExistsRetryStrategy = Retry.backoff(defaultMaxRetryAttempts, defaultBackoff)
        .maxBackoff(defaultMaxBackoff);
    private static final Retry createItemExistsRetryStrategy = Retry.backoff(defaultMaxRetryAttempts, defaultBackoff)
        .maxBackoff(defaultMaxBackoff)
        .filter(throwable -> !(throwable instanceof InvalidResponse)); // Do not retry on this exception

    private static final int bulkMaxRetryAttempts = 15;
    private static final Duration bulkBackoff = Duration.ofSeconds(2);
    private static final Duration bulkMaxBackoff = Duration.ofSeconds(60);
    /** Retries for up 10 minutes */
    private static final Retry bulkRetryStrategy = Retry.backoff(bulkMaxRetryAttempts, bulkBackoff)
        .maxBackoff(bulkMaxBackoff);

    static {
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    protected final RestClient client;
    protected final FailedRequestsLogger failedRequestsLogger;

    public OpenSearchClient(ConnectionContext connectionContext) {
        this(new RestClient(connectionContext), new FailedRequestsLogger());
    }

    protected OpenSearchClient(RestClient client, FailedRequestsLogger failedRequestsLogger) {
        this.client = client;
        this.failedRequestsLogger = failedRequestsLogger;
    }

    public Version getClusterVersion() {
        return client.getAsync("/", null)
            .flatMap(resp -> {
                try {
                    return Mono.just(versionFromResponse(resp));
                } catch (Exception e) {
                    return Mono.error(new OperationFailed(e.getMessage(), resp));
                }
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(checkIfItemExistsRetryStrategy)
            .block();
    }

    private Version versionFromResponse(HttpResponse resp) {
        if (resp.statusCode != 200) {
            throw new OperationFailed("Unexpected status code " + resp.statusCode, resp);
        }
        try {
            var body = objectMapper.readTree(resp.body);
            var versionNode = body.get("version");

            var versionNumberString = versionNode.get("number").asText();
            var parts = versionNumberString.split("\\.");
            var versionBuilder = Version.builder()
                .major(Integer.parseInt(parts[0]))
                .minor(Integer.parseInt(parts[1]))
                .patch(parts.length > 2 ? Integer.parseInt(parts[2]) : 0);
            
            var distroNode = versionNode.get("distribution");
            if (distroNode != null && distroNode.asText().equalsIgnoreCase("opensearch")) {
                versionBuilder.flavor(Flavor.OpenSearch);
            } else { 
                versionBuilder.flavor(Flavor.Elasticsearch);
            }
            return versionBuilder.build();
        } catch (Exception e) {
            log.error("Unable to parse version from response", e);
            throw new OperationFailed("Unable to parse version from response: " + e.getMessage(), resp);
        }
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

    /** Returns true if this template already exists */
    public boolean hasLegacyTemplate(String templateName) {
        var targetPath = "_template/" + templateName;
        return hasObjectCheck(targetPath, null);
    }

    /** Returns true if this template already exists */
    public boolean hasComponentTemplate(String templateName) {
        var targetPath = "_component_template/" + templateName;
        return hasObjectCheck(targetPath, null);
    }

    /** Returns true if this template already exists */
    public boolean hasIndexTemplate(String templateName) {
        var targetPath = "_index_template/" + templateName;
        return hasObjectCheck(targetPath, null);
    }

    /** Returns true if this index already exists */
    public boolean hasIndex(String indexName) {
        return hasObjectCheck(indexName, null);
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

    private Optional<ObjectNode> createObjectIdempotent(
        String objectPath,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        var objectDoesNotExist = !hasObjectCheck(objectPath, context);
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
                .doOnError(e -> log.error(e.getMessage()))
                .retryWhen(createItemExistsRetryStrategy)
                .block();

            return Optional.of(settings);
        } else {
            log.debug("Object at path {} already exists, not attempting to create.", objectPath);
        }
        // The only response code that can end up here is HTTP_OK, which means the object already existed
        return Optional.empty();
    }

    private boolean hasObjectCheck(
        String objectPath,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        var requestContext = Optional.ofNullable(context)
            .map(c -> c.createCheckRequestContext())
            .orElse(null);
        var getResponse = client.getAsync(objectPath, requestContext)
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
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(checkIfItemExistsRetryStrategy)
            .block();

        assert getResponse != null : ("getResponse should not be null; it should either be a valid response or an exception"
            + " should have been thrown.");
        return getResponse.statusCode == HttpURLConnection.HTTP_OK;
    }

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
            .doOnError(e -> log.error(e.getMessage()))
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
            .doOnError(e -> log.error(e.getMessage()))
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
            .doOnError(e -> log.error(e.getMessage()))
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
        return bulkRetryStrategy;
    }

    public Mono<BulkResponse> sendBulkRequest(String indexName, List<BulkDocSection> docs, IRfsContexts.IRequestContext context) {
        final var docsMap = docs.stream().collect(Collectors.toMap(d -> d.getDocId(), d -> d));
        return Mono.defer(() -> {
            final String targetPath = indexName + "/_bulk";
            log.atTrace()
                .setMessage("Creating bulk body with document ids {}")
                .addArgument(() -> docsMap.keySet())
                .log();
            var body = BulkDocSection.convertToBulkRequestBody(docsMap.values());
            var additionalHeaders = new HashMap<String, List<String>>();
            // Reduce network bandwidth by attempting request and response compression
            if (client.supportsGzipCompression()) {
                RestClient.addGzipRequestHeaders(additionalHeaders);
                RestClient.addGzipResponseHeaders(additionalHeaders);
            }
            return client.postAsync(targetPath, body, additionalHeaders, context)
                .flatMap(response -> {
                    var resp = new BulkResponse(response.statusCode, response.statusText, response.headers, response.body);
                    if (!resp.hasBadStatusCode() && !resp.hasFailedOperations()) {
                        return Mono.just(resp);
                    }
                    // Remove all successful documents for the next bulk request attempt
                    var successfulDocs = resp.getSuccessfulDocs();
                    successfulDocs.forEach(docsMap::remove);
                    log.atWarn()
                        .setMessage("After bulk request on index '{}', {} more documents have succeed, {} remain")
                        .addArgument(indexName)
                        .addArgument(successfulDocs::size)
                        .addArgument(docsMap::size)
                        .log();
                    return Mono.error(new OperationFailed(resp.getFailureMessage(), resp));
                });
        })
        .retryWhen(getBulkRetryStrategy())
        .doOnError(error -> {
            if (!docsMap.isEmpty()) {
                failedRequestsLogger.logBulkFailure(
                    indexName,
                    docsMap::size,
                    () -> BulkDocSection.convertToBulkRequestBody(docsMap.values()),
                    error
                );
            } else {
                log.atError()
                    .setMessage("Unexpected empty document map for bulk request on index {}")
                    .addArgument(indexName)
                    .setCause(error)
                    .log();
            }
        });
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
                log.warn("Unable to process bulk request for success", ioe);
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
            super(message +"\nBody:\n" + response);

            this.response = response;
        }
    }
}
