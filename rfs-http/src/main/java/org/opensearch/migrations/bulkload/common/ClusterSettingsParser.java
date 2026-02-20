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
     * Checks whether a two-level boolean setting is enabled in any scope
     * (persistent, transient, or defaults) of a parsed cluster settings response.
     *
     * @param settingsRoot the root JSON node from the cluster settings response
     * @param primaryKey   first-level key (e.g. "compatibility", "http_compression")
     * @param secondaryKey second-level key (e.g. "override_main_response_version", "enabled")
     * @return true if the setting is enabled in any scope
     */
    public boolean isSettingEnabled(JsonNode settingsRoot, String primaryKey, String secondaryKey) {
        var body = Optional.of(settingsRoot);
        var persistentEnabled = isNodeEnabled(body.map(n -> n.get("persistent")), primaryKey, secondaryKey);
        var transientEnabled = isNodeEnabled(body.map(n -> n.get("transient")), primaryKey, secondaryKey);
        var defaultsEnabled = isNodeEnabled(body.map(n -> n.get("defaults")), primaryKey, secondaryKey);
        return persistentEnabled || transientEnabled || defaultsEnabled;
    }

    /**
     * Checks whether a two-level boolean setting is enabled within a single scope node.
     */
    public boolean isNodeEnabled(Optional<JsonNode> node, String primaryKey, String secondaryKey) {
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
}
