package org.opensearch.migrations.bulkload.common;

import java.util.HashSet;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Detects the version of a remote OpenSearch/Elasticsearch cluster via REST API.
 */
@Slf4j
public class ClusterVersionDetector {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Retry RETRY_STRATEGY = Retry.max(1)
        .filter(throwable -> !(throwable instanceof UnexpectedStatusCode &&
            ((UnexpectedStatusCode) throwable).response.statusCode >= 400 &&
            ((UnexpectedStatusCode) throwable).response.statusCode < 500));

    /** Amazon OpenSearch Serverless clusters don't have a version number */
    private static final Version AMAZON_SERVERLESS_VERSION = Version.builder()
        .flavor(Flavor.AMAZON_SERVERLESS_OPENSEARCH)
        .major(2)
        .build();

    private ClusterVersionDetector() {}

    public static Version detect(ConnectionContext connectionContext) {
        var client = new RestClient(connectionContext);
        return detect(client);
    }

    public static Version detect(RestClient client) {
        var versionFromRootApi = client.getAsync("", null)
            .flatMap(resp -> {
                if (resp.statusCode == 200) {
                    return versionFromResponse(resp, client);
                }
                if (resp.statusCode == 404) {
                    return Mono.just(AMAZON_SERVERLESS_VERSION);
                }
                return Mono.error(new UnexpectedStatusCode(resp));
            })
            .doOnError(e -> log.atWarn()
                .setMessage("Check cluster version failed")
                .setCause(e)
                .log())
            .retryWhen(RETRY_STRATEGY)
            .block();

        // Compatibility mode is only enabled on OpenSearch clusters responding with the version of 7.10.2
        if (!VersionMatchers.isES_7_10.test(versionFromRootApi)) {
            return versionFromRootApi;
        }
        return client.getAsync("_cluster/settings?include_defaults=true", null)
            .flatMap(resp -> checkCompatibilityModeFromResponse(resp))
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(RETRY_STRATEGY)
            .flatMap(hasCompatibilityModeEnabled -> {
                log.atInfo().setMessage("After querying target, compatibilityMode={}").addArgument(hasCompatibilityModeEnabled).log();
                if (Boolean.FALSE.equals(hasCompatibilityModeEnabled)) {
                    assert versionFromRootApi != null;
                    return Mono.just(versionFromRootApi);
                }
                return client.getAsync("_nodes/_all/nodes,version?format=json", null)
                    .flatMap(resp -> getVersionFromNodes(resp, client))
                    .doOnError(e -> log.error(e.getMessage()))
                    .retryWhen(RETRY_STRATEGY);
            })
            .onErrorResume(e -> {
                log.atWarn()
                    .setCause(e)
                    .setMessage("Unable to determine CompatibilityMode or version from plugin, falling back to version {}")
                    .addArgument(versionFromRootApi).log();
                assert versionFromRootApi != null;
                return Mono.just(versionFromRootApi);
            })
            .block();
    }

    private static Mono<Version> versionFromResponse(HttpResponse resp, RestClient client) {
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
                versionBuilder.flavor(client.getConnectionContext().isAwsSpecificAuthentication()
                    ? Flavor.AMAZON_MANAGED_OPENSEARCH : Flavor.OPENSEARCH);
            } else {
                versionBuilder.flavor(Flavor.ELASTICSEARCH);
            }
            return Mono.just(versionBuilder.build());
        } catch (Exception e) {
            log.error("Unable to parse version from response", e);
            return Mono.error(new UnexpectedStatusCode(resp));
        }
    }

    private static Mono<Boolean> checkCompatibilityModeFromResponse(HttpResponse resp) {
        return checkBooleanSettingFromResponse(resp, "compatibility", "override_main_response_version");
    }

    private static Mono<Boolean> checkBooleanSettingFromResponse(HttpResponse resp, String primaryKey, String secondaryKey) {
        if (resp.statusCode != 200) {
            return Mono.error(new UnexpectedStatusCode(resp));
        }
        try {
            var body = objectMapper.readTree(resp.body);
            return Mono.just(ClusterSettingsParser.isSettingEnabled(body, primaryKey, secondaryKey));
        } catch (Exception e) {
            log.error("Unable to check setting", e);
            return Mono.error(new UnexpectedStatusCode(resp));
        }
    }

    private static Mono<Version> getVersionFromNodes(HttpResponse resp, RestClient client) {
        if (resp.statusCode != 200) {
            return Mono.error(new UnexpectedStatusCode(resp));
        }
        var foundVersions = new HashSet<Version>();
        try {
            var nodes = objectMapper.readTree(resp.body).get("nodes");
            Flavor likelyFlavor = client.getConnectionContext().isAwsSpecificAuthentication()
                ? Flavor.AMAZON_MANAGED_OPENSEARCH : Flavor.OPENSEARCH;
            nodes.properties().forEach(node -> {
                var versionNumber = node.getValue().get("version").asText();
                var nodeVersion = Version.fromString(likelyFlavor + " " + versionNumber);
                foundVersions.add(nodeVersion);
            });

            if (foundVersions.isEmpty()) {
                return Mono.error(new UnexpectedStatusCode(resp));
            } else if (foundVersions.size() == 1) {
                return Mono.just(foundVersions.iterator().next());
            }
            return Mono.error(new RuntimeException("Multiple version numbers discovered on nodes: " + foundVersions));
        } catch (Exception e) {
            log.error("Unable to check node versions", e);
            return Mono.error(new UnexpectedStatusCode(resp));
        }
    }

    public static class UnexpectedStatusCode extends RuntimeException {
        public final HttpResponse response;

        public UnexpectedStatusCode(HttpResponse response) {
            super("Unexpected status code: " + response.statusCode);
            this.response = response;
        }
    }
}
