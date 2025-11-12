package org.opensearch.migrations.bulkload.common;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Optional;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@SuppressWarnings("java:S1450")
@Getter
@Slf4j
public class OpenSearchClientFactory {
    private static final ObjectMapper objectMapper = new ObjectMapper();

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

        if (!connectionContext.isDisableCompression() && Boolean.TRUE.equals(getCompressionEnabled())) {
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

    /** Amazon OpenSearch Serverless clusters don't have a version number, but
     * they are closely aligned with the latest open-source OpenSearch 2.X */
    private static final Version AMAZON_SERVERLESS_VERSION = Version.builder()
            .flavor(Flavor.AMAZON_SERVERLESS_OPENSEARCH)
            .major(2)
            .build();

    private Boolean getCompressionEnabled() {
        log.atInfo().setMessage("Checking compression on cluster").log();
        return client.getAsync("_cluster/settings?include_defaults=true", null)
            .flatMap(this::checkCompressionFromResponse)
            .doOnError(e -> log.atWarn()
                .setMessage("Check cluster compression failed")
                .setCause(e)
                .log())
            .retryWhen(OpenSearchClient.CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            .onErrorReturn(false)
            .doOnNext(hasCompressionEnabled -> log.atInfo()
                .setMessage("After querying target, compression={}")
                .addArgument(hasCompressionEnabled).log())
            .block();
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
                return Mono.error(new OpenSearchClient.UnexpectedStatusCode(resp));
            })
            .doOnError(e -> log.atWarn()
                .setMessage("Check cluster version failed")
                .setCause(e)
                .log())
            .retryWhen(OpenSearchClient.CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            .block();

        // Compatibility mode is only enabled on OpenSearch clusters responding with the version of 7.10.2
        if (!VersionMatchers.isES_7_10.test(versionFromRootApi)) {
            return versionFromRootApi;
        }
        return client.getAsync("_cluster/settings?include_defaults=true", null)
                .flatMap(this::checkCompatibilityModeFromResponse)
                .doOnError(e -> log.error(e.getMessage()))
                .retryWhen(OpenSearchClient.CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
                .flatMap(hasCompatibilityModeEnabled -> {
                    log.atInfo().setMessage("After querying target, compatibilityMode={}").addArgument(hasCompatibilityModeEnabled).log();
                    if (Boolean.FALSE.equals(hasCompatibilityModeEnabled)) {
                        assert versionFromRootApi != null : "Expected version from root api to be set";
                        return Mono.just(versionFromRootApi);
                    }
                    return client.getAsync("_nodes/_all/nodes,version?format=json", null)
                            .flatMap(this::getVersionFromNodes)
                            .doOnError(e -> log.error(e.getMessage()))
                            .retryWhen(OpenSearchClient.CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY);
                })
                .onErrorResume(e -> {
                    log.atWarn()
                            .setCause(e)
                            .setMessage("Unable to determine CompatibilityMode or version from plugin, falling back to version {}")
                            .addArgument(versionFromRootApi).log();
                    assert versionFromRootApi != null : "Expected version from root api to be set";
                    return Mono.just(versionFromRootApi);
                })
                .block();
    }

    private Mono<Version> versionFromResponse(HttpResponse resp) {
        try {
            var body = objectMapper.readTree(resp.body);
            var versionNode = body.get("version");

            var versionNumberString = versionNode.get("number").asText();
            var parts = versionNumberString.split("[.\\-]");
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
            return Mono.error(new OpenSearchClient.OperationFailed("Unable to parse version from response: " + e.getMessage(), resp));
        }
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
            var body = Optional.of(objectMapper.readTree(resp.body));
            var persistentEnabled = isSettingEnabled(body.map(n -> n.get("persistent")), primaryKey, secondaryKey);
            var transientEnabled = isSettingEnabled(body.map(n -> n.get("transient")), primaryKey, secondaryKey);
            var defaultsEnabled = isSettingEnabled(body.map(n -> n.get("defaults")), primaryKey, secondaryKey);
            return Mono.just(persistentEnabled || transientEnabled || defaultsEnabled);
        } catch (Exception e) {
            log.error(errorLogMessage, e);
            return Mono.error(new OpenSearchClient.OperationFailed(errorLogMessage + " from response: " + e.getMessage(), resp));
        }
    }

    private boolean isSettingEnabled(Optional<JsonNode> node, String primaryKey, String secondaryKey) {
        return node.filter(n -> !n.isNull())
            .map(n -> n.get(primaryKey))
            .filter(n -> !n.isNull())
            .map(n -> n.get(secondaryKey))
            .filter(n -> !n.isNull())
            .map(n -> {
                if (n.isBoolean()) {
                    return n.asBoolean();
                } else if (n.isTextual()) {
                    return Boolean.parseBoolean(n.asText());
                } else {
                    return false;
                }
            })
            .orElse(false);
    }

    private Mono<Version> getVersionFromNodes(HttpResponse resp) {
        if (resp.statusCode != 200) {
            return Mono.error(new OpenSearchClient.UnexpectedStatusCode(resp));
        }
        var foundVersions = new HashSet<Version>();
        try {

            var nodes = objectMapper.readTree(resp.body)
                    .get("nodes");
            nodes.properties().forEach(node -> {
                var versionNumber = node.getValue().get("version").asText();
                var nodeVersion = Version.fromString(getLikelyOpenSearchFlavor() + " " + versionNumber);
                foundVersions.add(nodeVersion);
            });

            if (foundVersions.isEmpty()) {
                return Mono.error(new OpenSearchClient.OperationFailed("Unable to find any version numbers", resp));
            } else if (foundVersions.size() == 1) {
                return Mono.just(foundVersions.iterator().next());
            }

            return Mono.error(new OpenSearchClient.OperationFailed("Multiple version numbers discovered on nodes, " + foundVersions, resp));

        } catch (Exception e) {
            log.error("Unable to check node versions", e);
            return Mono.error(new OpenSearchClient.OperationFailed("Unable to check node versions: " + e.getMessage(), resp));
        }
    }

    private Flavor getLikelyOpenSearchFlavor() {
        return client.getConnectionContext().isAwsSpecificAuthentication() ? Flavor.AMAZON_MANAGED_OPENSEARCH : Flavor.OPENSEARCH;
    }

    public static class ClientInstantiationException extends RuntimeException {
        public ClientInstantiationException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
