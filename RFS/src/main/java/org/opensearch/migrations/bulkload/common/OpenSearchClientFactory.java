package org.opensearch.migrations.bulkload.common;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.RetryBudget;
import org.opensearch.migrations.bulkload.version_es_5_6.OpenSearchClient_ES_5_6;
import org.opensearch.migrations.bulkload.version_es_6_8.OpenSearchClient_ES_6_8;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@SuppressWarnings("java:S1450")
@Getter
@Slf4j
public class OpenSearchClientFactory {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_COMPRESSION_CHECK_RETRIES = 2;
    // Same shape as version detection: an immediate retry after a 429 almost always gets another 429.
    private static final Retry COMPRESSION_CHECK_RETRY = Retry.backoff(MAX_COMPRESSION_CHECK_RETRIES, Duration.ofSeconds(1))
        .maxBackoff(Duration.ofSeconds(5))
        .filter(HttpRetryClassifier::isRetryable)
        .doBeforeRetry(retrySignal -> log.atWarn()
            .setCause(retrySignal.failure())
            .setMessage("Checking whether the target supports compression failed ({}); retrying attempt {} of {}")
            .addArgument(HttpRetryClassifier.describe(retrySignal.failure()))
            .addArgument(retrySignal.totalRetries() + 2)
            .addArgument(MAX_COMPRESSION_CHECK_RETRIES + 1)
            .log());

    static final int COLLECTION_TYPE_PROBE_ATTEMPTS = 3;

    private final ConnectionContext connectionContext;
    private final int maxConnections;
    Duration collectionTypeProbeRetryDelay = Duration.ofSeconds(2);
    private Version version;
    private CompressionMode compressionMode;
    RestClient client;
    // One per process per target, shared by every client this factory makes: used only for bulk writes, so a
    // throttled target can't starve work coordination or metadata requests of retries.
    private final RetryBudget bulkRetryBudget = new RetryBudget();
    // Same scope; bounded by maxConnections. Null without a connection limit (metadata migration and tools).
    private final AdaptiveLimit bulkInFlightLimit;

    public OpenSearchClientFactory(ConnectionContext connectionContext) {
        this(connectionContext, 0);
    }

    public OpenSearchClientFactory(ConnectionContext connectionContext, int maxConnections) {
        if (connectionContext == null) {
            throw new IllegalArgumentException("Connection context was not provided in constructor.");
        }
        this.connectionContext = connectionContext;
        this.maxConnections = maxConnections;
        this.client = new RestClient(connectionContext, maxConnections);
        this.bulkInFlightLimit = maxConnections > 0 ? new AdaptiveLimit(maxConnections) : null;
    }

    public OpenSearchClient determineVersionAndCreate() {
        return determineVersionAndCreate(null, null);
    }

    public OpenSearchClient determineVersionAndCreate(RestClient restClient, FailedRequestsLogger failedRequestsLogger) {
        return determineVersionAndCreate(restClient, failedRequestsLogger,
            Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public OpenSearchClient determineVersionAndCreate(int maxDocsPerBulkRequest, long maxBytesPerBulkRequest) {
        return determineVersionAndCreate(null, null, maxDocsPerBulkRequest, maxBytesPerBulkRequest);
    }

    private OpenSearchClient determineVersionAndCreate(
        RestClient restClient, FailedRequestsLogger failedRequestsLogger, int maxDocsPerBulkRequest, long maxBytesPerBulkRequest
    ) {
        if (version == null) {
            version = getClusterVersion();
        }

        // Serverless doesn't support _cluster/settings API, default to compression enabled
        if (version.getFlavor() == Flavor.AMAZON_SERVERLESS_OPENSEARCH) {
            log.info("Serverless target detected, defaulting to compression enabled");
            compressionMode = connectionContext.isDisableCompression()
                ? CompressionMode.UNCOMPRESSED
                : CompressionMode.GZIP_BODY_COMPRESSION;
        } else if (!connectionContext.isDisableCompression() && Boolean.TRUE.equals(getCompressionEnabled())) {
            compressionMode = CompressionMode.GZIP_BODY_COMPRESSION;
        } else {
            compressionMode = CompressionMode.UNCOMPRESSED;
        }
        var clientClass = getOpenSearchClientClass(version);
        try {
            var created = clientClass.getConstructor(RestClient.class, FailedRequestsLogger.class, Version.class,
                    CompressionMode.class, int.class, long.class)
                .newInstance(restClient == null ? client : restClient,
                    failedRequestsLogger == null ? new FailedRequestsLogger() : failedRequestsLogger,
                    version, compressionMode, maxDocsPerBulkRequest, maxBytesPerBulkRequest);
            created.setBulkRetryBudget(bulkRetryBudget);
            created.setBulkInFlightLimit(bulkInFlightLimit);
            return created;
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ClientInstantiationException("Failed to instantiate OpenSearchClient", e);
        }
    }

    private Class<? extends OpenSearchClient> getOpenSearchClientClass(Version version) {
        if (UnboundVersionMatchers.anyOS.or(UnboundVersionMatchers.isGreaterOrEqualES_7_X).test(version)) {
            return OpenSearchClient_OS_2_11.class;
        } else if (VersionMatchers.isES_6_X.test(version)) {
            return OpenSearchClient_ES_6_8.class;
        } else if (UnboundVersionMatchers.isBelowES_6_X.test(version)) {
            return OpenSearchClient_ES_5_6.class;
        }
        throw new IllegalArgumentException("Unsupported version: " + version);
    }

    private Boolean getCompressionEnabled() {
        log.atInfo().setMessage("Checking compression on cluster").log();
        return client.getAsync("_cluster/settings?include_defaults=true", null)
            .flatMap(this::checkCompressionFromResponse)
            .retryWhen(COMPRESSION_CHECK_RETRY)
            .onErrorResume(e -> {
                var cause = Exceptions.isRetryExhausted(e) && e.getCause() != null ? e.getCause() : e;
                log.atWarn()
                    .setCause(cause)
                    .setMessage("Couldn't tell whether the target supports compression ({}); sending requests "
                        + "uncompressed")
                    .addArgument(HttpRetryClassifier.describe(cause))
                    .log();
                return Mono.just(false);
            })
            .doOnNext(hasCompressionEnabled -> log.atInfo()
                .setMessage("After querying target, compression={}")
                .addArgument(hasCompressionEnabled).log())
            .block();
    }

    public Version getClusterVersion() {
        // A per-account serverless endpoint can't answer GET / without a collection header
        if (connectionContext.isCollectionRouted()) {
            return ClusterVersionDetector.AMAZON_SERVERLESS_VERSION;
        }
        return ClusterVersionDetector.detect(client);
    }

    Mono<Boolean> checkCompressionFromResponse(HttpResponse resp) {
        return checkBooleanSettingFromResponse(
                resp,
                "http_compression",
                "enabled",
                "Unable to determine if compression is supported")
            .or(checkBooleanSettingFromResponse(
                    resp,
                    "http",
                    "compression",
                    "Unable to determine if compression is supported")
            );
    }

    private Mono<Boolean> checkBooleanSettingFromResponse(
            HttpResponse resp,
            String primaryKey,
            String secondaryKey,
            String errorLogMessage) {

        if (resp.statusCode != 200) {
            return Mono.error(new OpenSearchClient.UnexpectedStatusCode(resp));
        }
        try {
            var body = objectMapper.readTree(resp.body);
            return Mono.just(ClusterSettingsParser.isSettingEnabled(body, primaryKey, secondaryKey));
        } catch (Exception e) {
            log.error(errorLogMessage, e);
            return Mono.error(new OpenSearchClient.OperationFailed(errorLogMessage + " from response: " + e.getMessage(), resp));
        }
    }

    /**
     * Detects the serverless collection type by probing with a KNN index creation request.
     * Different collection types return different error messages when KNN features are used incorrectly.
     * 
     * @return The detected ServerlessCollectionType, or NONE if not serverless
     */
    public ServerlessCollectionType detectServerlessCollectionType() {
        return detectServerlessCollectionType(null);
    }

    /**
     * Detects the type of one collection behind a per-account serverless endpoint, or of the
     * endpoint's own collection when serverlessCollection is null. Throws if a routed collection's
     * type is still unknown after retries.
     */
    public ServerlessCollectionType detectServerlessCollectionType(String serverlessCollection) {
        if (version == null) {
            version = getClusterVersion();
        }

        if (version.getFlavor() != Flavor.AMAZON_SERVERLESS_OPENSEARCH) {
            return ServerlessCollectionType.NONE;
        }

        if (serverlessCollection == null) {
            return probeServerlessCollectionType(null);
        }
        for (int attempt = 1; ; attempt++) {
            var detected = probeServerlessCollectionType(serverlessCollection);
            if (detected != ServerlessCollectionType.NONE) {
                return detected;
            }
            if (attempt >= COLLECTION_TYPE_PROBE_ATTEMPTS) {
                throw new UndeterminedCollectionTypeException(serverlessCollection, attempt);
            }
            log.atWarn().setMessage("Could not determine the type of serverless collection {} (attempt {} of {}), retrying")
                .addArgument(serverlessCollection).addArgument(attempt).addArgument(COLLECTION_TYPE_PROBE_ATTEMPTS).log();
            sleepBeforeProbeRetry();
        }
    }

    private void sleepBeforeProbeRetry() {
        try {
            Thread.sleep(collectionTypeProbeRetryDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry the collection type probe", e);
        }
    }

    private ServerlessCollectionType probeServerlessCollectionType(String serverlessCollection) {
        // Probe with invalid KNN mapping to detect collection type from error message
        String probeIndex = "migrations_type_probe_" + System.currentTimeMillis();
        String probeBody = "{\"settings\":{\"index.knn\":true},\"mappings\":{\"properties\":{\"v\":{\"type\":\"knn_vector\",\"dimension\":-1}}}}";
        
        try {
            var response = (serverlessCollection == null
                ? client.putAsync(probeIndex, probeBody, null)
                : client.putAsync(probeIndex, probeBody, ServerlessCollectionRouting.headersFor(serverlessCollection), null)
            ).block();
            if (response != null) {
                String responseBody = response.body != null ? response.body : "";
                log.info("Probe response status={}, body={}", response.statusCode, responseBody);
                
                // Check response body for collection type indicators
                ServerlessCollectionType detected = parseCollectionTypeFromError(responseBody);
                if (detected != ServerlessCollectionType.NONE) {
                    return detected;
                }
                
                // If 4xx error, the body should contain the error
                if (response.statusCode >= 400) {
                    return parseCollectionTypeFromError(responseBody);
                }
            }
            // Unexpected success - assume VECTOR (most permissive for KNN)
            log.warn("Probe index creation unexpectedly succeeded, assuming VECTOR collection type");
            return ServerlessCollectionType.VECTOR;
        } catch (Exception e) {
            log.info("Probe exception: {}", e.getMessage());
            return parseCollectionTypeFromError(e.getMessage());
        }
    }
    
    private ServerlessCollectionType parseCollectionTypeFromError(String errorMessage) {
        if (errorMessage == null) {
            errorMessage = "";
        }
        
        if (errorMessage.contains("KNN features not supported on TIMESERIES collection type")) {
            log.info("Detected serverless collection type: TIMESERIES");
            return ServerlessCollectionType.TIMESERIES;
        } else if (errorMessage.contains("KNN features not supported on SEARCH collection type")) {
            log.info("Detected serverless collection type: SEARCH");
            return ServerlessCollectionType.SEARCH;
        } else if (errorMessage.contains("Dimension value must be greater than 0")) {
            log.info("Detected serverless collection type: VECTOR");
            return ServerlessCollectionType.VECTOR;
        } else {
            log.debug("Could not determine serverless collection type from: {}", errorMessage);
            return ServerlessCollectionType.NONE;
        }
    }

    public static class UndeterminedCollectionTypeException extends RuntimeException {
        public UndeterminedCollectionTypeException(String serverlessCollection, int attempts) {
            super("Could not determine the type of serverless collection '" + serverlessCollection + "' after "
                + attempts + " attempts, so it isn't known whether it needs server-generated document IDs. "
                + "Set --server-generated-ids to ALWAYS or NEVER to skip detection.");
        }
    }

    public static class ClientInstantiationException extends RuntimeException {
        public ClientInstantiationException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
