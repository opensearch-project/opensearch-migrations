package org.opensearch.migrations.bulkload.common;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

/**
 * Shared utility for parsing boolean settings from Elasticsearch/OpenSearch
 * cluster settings API responses ({@code _cluster/settings?include_defaults=true}).
 *
 * <p>Settings are checked across persistent, transient, and defaults scopes.
 */
@UtilityClass
public class ClusterSettingsParser {

    /**
     * Checks whether a two-level boolean setting is enabled, respecting ES/OS precedence:
     * transient > persistent > defaults. A scope that explicitly sets the value to {@code false}
     * takes precedence over a lower-priority scope that sets it to {@code true}.
     *
     * @param settingsRoot the root JSON node from the cluster settings response
     * @param primaryKey   first-level key (e.g. "compatibility", "http_compression")
     * @param secondaryKey second-level key (e.g. "override_main_response_version", "enabled")
     * @return true if the setting is enabled after applying precedence
     */
    public boolean isSettingEnabled(JsonNode settingsRoot, String primaryKey, String secondaryKey) {
        var body = Optional.of(settingsRoot);
        var transientValue = getNodeValue(body.map(n -> n.get("transient")), primaryKey, secondaryKey);
        if (transientValue.isPresent()) {
            return transientValue.get();
        }
        var persistentValue = getNodeValue(body.map(n -> n.get("persistent")), primaryKey, secondaryKey);
        if (persistentValue.isPresent()) {
            return persistentValue.get();
        }
        var defaultsValue = getNodeValue(body.map(n -> n.get("defaults")), primaryKey, secondaryKey);
        return defaultsValue.orElse(false);
    }

    /**
     * Reads a two-level boolean setting from a single scope node.
     * Returns empty if the setting is not present in this scope.
     */
    public Optional<Boolean> getNodeValue(Optional<JsonNode> node, String primaryKey, String secondaryKey) {
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
            });
    }
}
