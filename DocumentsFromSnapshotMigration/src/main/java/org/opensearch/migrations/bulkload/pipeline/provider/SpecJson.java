package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Small reading helpers shared by the provider spec parsers.
 *
 * <p>They exist so a missing or null field reads as absent rather than as a Jackson
 * {@code NullNode} that stringifies to {@code "null"}.
 */
final class SpecJson {

    private SpecJson() {}

    static String requiredString(JsonNode config, String field) {
        var value = optionalString(config, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Document source config is missing required field '" + field + "'");
        }
        return value;
    }

    static String optionalString(JsonNode config, String field) {
        var node = config.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    static boolean booleanOr(JsonNode config, String field, boolean fallback) {
        var node = config.get(field);
        return node == null || node.isNull() ? fallback : node.asBoolean(fallback);
    }

    static long longOr(JsonNode config, String field, long fallback) {
        var node = config.get(field);
        return node == null || node.isNull() ? fallback : node.asLong(fallback);
    }

    static List<String> stringList(JsonNode config, String field) {
        var node = config.get(field);
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>(node.size());
        node.forEach(element -> values.add(element.asText()));
        return List.copyOf(values);
    }

    /** Writes a string field only when there is something to write, keeping absent distinct from empty. */
    static void putIfPresent(ObjectNode target, String field, String value) {
        if (value != null) {
            target.put(field, value);
        }
    }
}
