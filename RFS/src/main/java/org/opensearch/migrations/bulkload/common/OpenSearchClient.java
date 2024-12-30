package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.parsing.BulkResponseParser;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
public class OpenSearchClient {

    protected static final ObjectMapper objectMapper = new ObjectMapper();

    /** Amazon OpenSearch Serverless cluster don't have a version number, but
     * its closely aligned with the latest open-source OpenSearch 2.X */
    private static final Version AMAZON_SERVERLESS_VERSION = Version.builder()
        .flavor(Flavor.AMAZON_SERVERLESS_OPENSEARCH)
        .major(2)
        .build();

    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);
    private static final Retry SNAPSHOT_RETRY_STRATEGY = Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF)
        .maxBackoff(DEFAULT_MAX_BACKOFF);
    protected static final Retry CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY =
        Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF)
            .maxBackoff(DEFAULT_MAX_BACKOFF);
    private static final Retry CREATE_ITEM_EXISTS_RETRY_STRATEGY =
        Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF)
            .maxBackoff(DEFAULT_MAX_BACKOFF)
            .filter(throwable -> !(throwable instanceof InvalidResponse)); // Do not retry on this exception

    private static final int BULK_MAX_RETRY_ATTEMPTS = 15;
    private static final Duration BULK_BACKOFF = Duration.ofSeconds(2);
    private static final Duration BULK_MAX_BACKOFF = Duration.ofSeconds(60);
    /** Retries for up 10 minutes */
    private static final Retry BULK_RETRY_STRATEGY = Retry.backoff(BULK_MAX_RETRY_ATTEMPTS, BULK_BACKOFF)
        .maxBackoff(BULK_MAX_BACKOFF);
    public static final String SNAPSHOT_PREFIX_STR = "_snapshot/";

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
        var versionFromRootApi = client.getAsync("", null)
            .flatMap(resp -> {
                if (resp.statusCode == 200) {
                    return versionFromResponse(resp);
                }
                // If the root API doesn't exist, the cluster is OpenSearch Serverless
                if (resp.statusCode == 404) {
                    return Mono.just(AMAZON_SERVERLESS_VERSION);
                }
                return Mono.error(new UnexpectedStatusCode(resp));
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            .block();

        // Compatibility mode is only enabled on OpenSearch clusters responding with the version of 7.10.2 
        if (!VersionMatchers.isES_7_10.test(versionFromRootApi)) {
            return versionFromRootApi;
        }
        return client.getAsync("_cluster/settings", null)
            .flatMap(this::checkCompatibilityModeFromResponse)
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            .flatMap(hasCompatibilityModeEnabled -> {
                log.atInfo().setMessage("Checking CompatibilityMode, was enabled? {}").addArgument(hasCompatibilityModeEnabled).log();
                if (Boolean.FALSE.equals(hasCompatibilityModeEnabled)) {
                    return Mono.just(versionFromRootApi);
                }
                return client.getAsync("_nodes/_all/nodes,version?format=json", null)
                    .flatMap(this::getVersionFromNodes)
                    .doOnError(e -> log.error(e.getMessage()))
                    .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY);
            })
            .onErrorResume(e -> {
                log.atWarn()
                    .setCause(e)
                    .setMessage("Unable to CompatibilityMode or determine the version from a plugin, falling back to version {}")
                    .addArgument(versionFromRootApi).log();
                return Mono.just(versionFromRootApi);
            })
            .block();        
    }

    private Mono<Version> versionFromResponse(HttpResponse resp) {
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
                versionBuilder.flavor(getLikelyOpenSearchFlavor());
            } else {
                versionBuilder.flavor(Flavor.ELASTICSEARCH);
            }
            return Mono.just(versionBuilder.build());
        } catch (Exception e) {
            log.error("Unable to parse version from response", e);
            return Mono.error(new OperationFailed("Unable to parse version from response: " + e.getMessage(), resp));
        }
    }

    Mono<Boolean> checkCompatibilityModeFromResponse(HttpResponse resp) {
        if (resp.statusCode != 200) {
            return Mono.error(new UnexpectedStatusCode(resp));
        }
        try {
            var body = Optional.of(objectMapper.readTree(resp.body));
            var persistentlyInCompatibilityMode = inCompatibilityMode(body.map(n -> n.get("persistent")));
            var transientlyInCompatibilityMode = inCompatibilityMode(body.map(n -> n.get("transient")));
            return Mono.just(persistentlyInCompatibilityMode || transientlyInCompatibilityMode);
        } catch (Exception e) {
            log.error("Unable to determine if the cluster is in compatibility mode", e);
            return Mono.error(new OperationFailed("Unable to determine if the cluster is in compatibility mode from response: " + e.getMessage(), resp));
        }
    }
    
    private boolean inCompatibilityMode(Optional<JsonNode> node) {
        return node.filter(n -> !n.isNull())
            .map(n -> n.get("compatibility"))
            .filter(n -> !n.isNull())
            .map(n -> n.get("override_main_response_version"))
            .filter(n -> !n.isNull())
            .map(n -> n.asBoolean())
            .orElse(false);
    }

    private Mono<Version> getVersionFromNodes(HttpResponse resp) {
        if (resp.statusCode != 200) {
            return Mono.error(new UnexpectedStatusCode(resp));
        }
        var foundVersions = new HashSet<Version>();
        try {

            var nodes = objectMapper.readTree(resp.body)
                .get("nodes");
            nodes.fields().forEachRemaining(node -> {
                var versionNumber = node.getValue().get("version").asText();
                var nodeVersion = Version.fromString(getLikelyOpenSearchFlavor() + " " + versionNumber);
                foundVersions.add(nodeVersion);
            });

            if (foundVersions.isEmpty()) {
                return Mono.error(new OperationFailed("Unable to find any version numbers", resp));
            }

            if (foundVersions.size() == 1) {
                return Mono.just(foundVersions.stream().findFirst().get());
            }

            return Mono.error(new OperationFailed("Multiple version numbers discovered on nodes, " + foundVersions, resp));

        } catch (Exception e) {
            log.error("Unable to check node versions", e);
            return Mono.error(new OperationFailed("Unable to check node versions: " + e.getMessage(), resp));
        }
    }

    private Flavor getLikelyOpenSearchFlavor() {
        return client.getConnectionContext().isAwsSpecificAuthentication() ? Flavor.AMAZON_MANAGED_OPENSEARCH : Flavor.OPENSEARCH;
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
            var putRequestContext = context == null ? null : context.createCheckRequestContext();
            client.putAsync(objectPath, settings.toString(), putRequestContext).flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                    return Mono.just(resp);
                } else if (resp.statusCode == HttpURLConnection.HTTP_BAD_REQUEST) {
                    return Mono.error(
                        new InvalidResponse("Create object failed for " + objectPath + "\r\n" + resp.body, resp)
                    );
                } else {
                    String errorMessage = "Could not create object: " + objectPath + ". " + getString(resp);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
                .doOnError(e -> log.error(e.getMessage()))
                .retryWhen(CREATE_ITEM_EXISTS_RETRY_STRATEGY)
                .block();

            return Optional.of(settings);
        } else {
            log.debug("Object at path {} already exists, not attempting to create.", objectPath);
        }
        // The only response code that can end up here is HTTP_OK, which means the object already existed
        return Optional.empty();
    }

    private static String getString(HttpResponse resp) {
        return "Response Code: "
            + resp.statusCode
            + ", Response Message: "
            + resp.statusText
            + ", Response Body: "
            + resp.body;
    }

    private boolean hasObjectCheck(
        String objectPath,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        var requestContext = Optional.ofNullable(context)
            .map(IRfsContexts.ICheckedIdempotentPutRequestContext::createCheckRequestContext)
            .orElse(null);
        var getResponse = client.getAsync(objectPath, requestContext)
            .flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_NOT_FOUND ||
                    resp.statusCode == HttpURLConnection.HTTP_OK)
                {
                    return Mono.just(resp);
                } else {
                    String errorMessage = "Could not create object: " + objectPath + ". " + getString(resp);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            .block();

        assert getResponse != null : ("getResponse should not be null; it should either be a valid response or " +
            "an exception should have been thrown.");
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
        String targetPath = SNAPSHOT_PREFIX_STR + repoName;
        client.putAsync(targetPath, settings.toString(), context.createRegisterRequest()).flatMap(resp -> {
            if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = "Could not register snapshot repo: " + targetPath + ". " + getString(resp);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(SNAPSHOT_RETRY_STRATEGY)
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
        String targetPath = SNAPSHOT_PREFIX_STR + repoName + "/" + snapshotName;
        client.putAsync(targetPath, settings.toString(), context.createSnapshotContext()).flatMap(resp -> {
            if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = "Could not create snapshot: " + targetPath + "." + getString(resp);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(SNAPSHOT_RETRY_STRATEGY)
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
        String targetPath = SNAPSHOT_PREFIX_STR + repoName + "/" + snapshotName;
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
            .retryWhen(SNAPSHOT_RETRY_STRATEGY)
            .block();

        assert getResponse != null : ("getResponse should not be null; it should either be a valid response or an "
            + "exception should have been thrown.");
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
        return BULK_RETRY_STRATEGY;
    }

    public Mono<BulkResponse> sendBulkRequest(String indexName, List<BulkDocSection> docs,
                                              IRfsContexts.IRequestContext context)
    {
        final var docsMap = docs.stream().collect(Collectors.toMap(d -> d.getDocId(), d -> d));
        return Mono.defer(() -> {
            final String targetPath = indexName + "/_bulk";
            log.atTrace().setMessage("Creating bulk body with document ids {}").addArgument(docsMap::keySet).log();
            var body = BulkDocSection.convertToBulkRequestBody(docsMap.values());
            var additionalHeaders = new HashMap<String, List<String>>();
            // Reduce network bandwidth by attempting request and response compression
            if (client.supportsGzipCompression()) {
                RestClient.addGzipRequestHeaders(additionalHeaders);
                RestClient.addGzipResponseHeaders(additionalHeaders);
            }
            return client.postAsync(targetPath, body, additionalHeaders, context)
                .flatMap(response -> {
                    var resp =
                        new BulkResponse(response.statusCode, response.statusText, response.headers, response.body);
                    if (!resp.hasBadStatusCode() && !resp.hasFailedOperations()) {
                        return Mono.just(resp);
                    }
                    log.atDebug().setMessage("Response has some errors...: {}").addArgument(response.body).log();
                    log.atDebug().setMessage("... for request: {}").addArgument(body).log();
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
                    .setCause(error)
                    .setMessage("Unexpected empty document map for bulk request on index {}")
                    .addArgument(indexName)
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

    public static class OperationFailed extends RuntimeException {
        public final transient HttpResponse response;

        public OperationFailed(String message, HttpResponse response) {
            super(message +"\nBody:\n" + response);

            this.response = response;
        }
    }

    public static class UnexpectedStatusCode extends OperationFailed {
        public UnexpectedStatusCode(HttpResponse response) {
            super("Unexpected status code " + response.statusCode, response);
        }
    }
}
