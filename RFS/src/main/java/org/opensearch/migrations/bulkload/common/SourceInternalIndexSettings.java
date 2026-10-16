package org.opensearch.migrations.bulkload.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Index settings that record the source cluster's copy of an index (identity and version
 * bookkeeping) rather than anything the user configured. The target always assigns its own
 * values, so these are removed before index creation instead of relying on the target to
 * reject them: OpenSearch Serverless accepts index.version.created, and every write to the
 * resulting index then fails.
 */
public final class SourceInternalIndexSettings {
    private static final String INDEX_PREFIX = "index.";

    private static final Set<String> EXACT = Set.of(
        "creation_date",
        "uuid",
        "provided_name",
        "history.uuid",
        "verified_before_close"
    );

    private static final Set<String> PREFIXES = Set.of(
        "version.",       // version.created, version.upgraded, ...
        "resize.source."  // resize.source.name, resize.source.uuid
    );

    private SourceInternalIndexSettings() {}

    /** @param settingName a dotted setting name, with or without the "index." prefix */
    public static boolean isInternal(String settingName) {
        var name = settingName.startsWith(INDEX_PREFIX) ? settingName.substring(INDEX_PREFIX.length()) : settingName;
        return EXACT.contains(name) || PREFIXES.stream().anyMatch(name::startsWith);
    }

    /**
     * Removes internal settings in place. Handles flat keys ("index.version.created"), nested
     * objects ({"version": {"created": ...}}), and either form under an "index" object.
     * Objects left empty by a removal are removed too.
     *
     * @return the dotted names of the removed settings
     */
    public static List<String> strip(ObjectNode settings) {
        var removed = new ArrayList<String>();
        strip(settings, "", removed);
        return removed;
    }

    private static void strip(ObjectNode node, String pathPrefix, List<String> removed) {
        var keysToRemove = new ArrayList<String>();
        node.fieldNames().forEachRemaining(key -> {
            var path = pathPrefix + key;
            JsonNode value = node.get(key);
            if (value.isObject()) {
                var removedBefore = removed.size();
                strip((ObjectNode) value, path + ".", removed);
                if (value.isEmpty() && removed.size() > removedBefore) {
                    keysToRemove.add(key);
                }
            } else if (isInternal(path)) {
                keysToRemove.add(key);
                removed.add(path);
            }
        });
        node.remove(keysToRemove);
    }
}
