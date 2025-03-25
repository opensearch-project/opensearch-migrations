package org.opensearch.migrations.bulkload.common;


import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Optional;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
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

@Getter
@Slf4j
public class OpenSearchClientFactory {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private ConnectionContext connectionContext;
    private Version version;
    RestClient client;
 
    public OpenSearchClientFactory(ConnectionContext connectionContext) {
        if (connectionContext == null) {
            throw new IllegalArgumentException("Connection context was not provided in constructor.");
        }
        this.connectionContext = connectionContext;
        this.client = new RestClient(connectionContext);
    }

    public OpenSearchClient determineVersionAndCreate() {
        if (version == null) {
            version = getClusterVersion();
        }
        var clientClass = getOpenSearchClientClass(version);
        try {
            return clientClass.getConstructor(ConnectionContext.class, Version.class)
                    .newInstance(connectionContext, version);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ClientInstantiationException("Failed to instantiate OpenSearchClient", e);
        }
    }

    public OpenSearchClient determineVersionAndCreate(RestClient restClient, FailedRequestsLogger failedRequestsLogger) {
        if (version == null) {
            version = getClusterVersion();
        }
        var clientClass = getOpenSearchClientClass(version);
        try {
            return clientClass.getConstructor(RestClient.class, FailedRequestsLogger.class, Version.class)
                    .newInstance(restClient, failedRequestsLogger, version);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ClientInstantiationException("Failed to instantiate OpenSearchClient", e);
        }
    }

    private Class<? extends OpenSearchClient> getOpenSearchClientClass(Version version) {
        if (VersionMatchers.isOS_1_X.or(VersionMatchers.isOS_2_X).or(VersionMatchers.isES_7_X).test(version)) {
            return OpenSearchClient_OS_2_11.class;
        } else if (VersionMatchers.isES_6_X.test(version)) {
            return OpenSearchClient_ES_6_8.class;
        } else if (VersionMatchers.isES_5_X.test(version)) {
            return OpenSearchClient_ES_5_6.class;
        }
        throw new IllegalArgumentException("Unsupported version: " + version);
    }

    /** Amazon OpenSearch Serverless cluster don't have a version number, but
     * it is closely aligned with the latest open-source OpenSearch 2.X */
    private static final Version AMAZON_SERVERLESS_VERSION = Version.builder()
            .flavor(Flavor.AMAZON_SERVERLESS_OPENSEARCH)
            .major(2)
            .build();

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
                .doOnError(e -> log.error(e.getMessage()))
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
                    log.atInfo().setMessage("Checking CompatibilityMode, was enabled? {}").addArgument(hasCompatibilityModeEnabled).log();
                    if (Boolean.FALSE.equals(hasCompatibilityModeEnabled)) {
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
            return Mono.error(new OpenSearchClient.OperationFailed("Unable to parse version from response: " + e.getMessage(), resp));
        }
    }

    Mono<Boolean> checkCompatibilityModeFromResponse(HttpResponse resp) {
        if (resp.statusCode != 200) {
            return Mono.error(new OpenSearchClient.UnexpectedStatusCode(resp));
        }
        try {
            var body = Optional.of(objectMapper.readTree(resp.body));
            var persistentlyInCompatibilityMode = inCompatibilityMode(body.map(n -> n.get("persistent")));
            var transientlyInCompatibilityMode = inCompatibilityMode(body.map(n -> n.get("transient")));
            return Mono.just(persistentlyInCompatibilityMode || transientlyInCompatibilityMode);
        } catch (Exception e) {
            log.error("Unable to determine if the cluster is in compatibility mode", e);
            return Mono.error(new OpenSearchClient.OperationFailed("Unable to determine if the cluster is in compatibility mode from response: " + e.getMessage(), resp));
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
            return Mono.error(new OpenSearchClient.UnexpectedStatusCode(resp));
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
                return Mono.error(new OpenSearchClient.OperationFailed("Unable to find any version numbers", resp));
            } else if (foundVersions.size() == 1) {
                return Mono.just(foundVersions.stream().findFirst().get());
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
