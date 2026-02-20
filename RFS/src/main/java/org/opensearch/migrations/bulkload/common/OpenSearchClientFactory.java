package org.opensearch.migrations.bulkload.common;

import java.lang.reflect.InvocationTargetException;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.version_es_5_6.OpenSearchClient_ES_5_6;
import org.opensearch.migrations.bulkload.version_es_6_8.OpenSearchClient_ES_6_8;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@SuppressWarnings("java:S1450")
@Getter
@Slf4j
public class OpenSearchClientFactory {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Retry RETRY_WHEN_NOT_4XX_STRATEGY = Retry.max(1)
        .filter(throwable -> !(throwable instanceof OpenSearchClient.UnexpectedStatusCode &&
            ((OpenSearchClient.UnexpectedStatusCode) throwable).response.statusCode >= 400 &&
            ((OpenSearchClient.UnexpectedStatusCode) throwable).response.statusCode < 500));

    private final ConnectionContext connectionContext;
    private Version version;
    private CompressionMode compressionMode;
    RestClient client;

    public OpenSearchClientFactory(ConnectionContext connectionContext) {
        if (connectionContext == null) {
            throw new IllegalArgumentException("Connection context was not provided in constructor.");
        }
        this.connectionContext = connectionContext;
        this.client = new RestClient(connectionContext);
    }

    public OpenSearchClient determineVersionAndCreate() {
        return determineVersionAndCreate(null, null);
    }

    public OpenSearchClient determineVersionAndCreate(RestClient restClient, FailedRequestsLogger failedRequestsLogger) {
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
            if (restClient == null && failedRequestsLogger == null) {
                return clientClass.getConstructor(ConnectionContext.class, Version.class, CompressionMode.class)
                        .newInstance(connectionContext, version, compressionMode);
            }
            return clientClass.getConstructor(RestClient.class, FailedRequestsLogger.class, Version.class, CompressionMode.class)
                    .newInstance(restClient, failedRequestsLogger, version, compressionMode);
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
            .doOnError(e -> log.atWarn()
                .setMessage("Check cluster compression failed")
                .setCause(e)
                .log())
            .retryWhen(RETRY_WHEN_NOT_4XX_STRATEGY)
            .onErrorReturn(false)
            .doOnNext(hasCompressionEnabled -> log.atInfo()
                .setMessage("After querying target, compression={}")
                .addArgument(hasCompressionEnabled).log())
            .block();
    }

    public Version getClusterVersion() {
        return ClusterVersionDetector.detect(client);
    }

    Mono<Boolean> checkCompatibilityModeFromResponse(HttpResponse resp) {
        return checkBooleanSettingFromResponse(
                resp,
                "compatibility",
                "override_main_response_version",
                "Unable to determine if the cluster is in compatibility mode");
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
        if (version == null) {
            version = getClusterVersion();
        }
        
        if (version.getFlavor() != Flavor.AMAZON_SERVERLESS_OPENSEARCH) {
            return ServerlessCollectionType.NONE;
        }
        
        // Probe with invalid KNN mapping to detect collection type from error message
        String probeIndex = "migrations_type_probe_" + System.currentTimeMillis();
        String probeBody = "{\"settings\":{\"index.knn\":true},\"mappings\":{\"properties\":{\"v\":{\"type\":\"knn_vector\",\"dimension\":-1}}}}";
        
        try {
            var response = client.putAsync(probeIndex, probeBody, null).block();
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

    public static class ClientInstantiationException extends RuntimeException {
        public ClientInstantiationException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
