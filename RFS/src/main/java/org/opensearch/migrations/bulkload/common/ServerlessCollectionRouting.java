package org.opensearch.migrations.bulkload.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Maps source index names to OpenSearch Serverless collections for targets that use the
 * per-account endpoint, which selects the collection from a request header.
 *
 * The table is a JSON object with two optional lists of {"sourceIndex": ..., "collection": ...}
 * entries. staticCollectionRouting maps exact index names to collection names.
 * regexCollectionRouting is an ordered list of patterns that must match the whole index name,
 * whose collection may reference capture groups ($1, ${name}). An exact static match wins,
 * otherwise the first matching regex entry is used.
 */
public class ServerlessCollectionRouting {
    public static final String COLLECTION_NAME_HEADER = "x-amz-aoss-collection-name";
    public static final String STATIC_FIELD = "staticCollectionRouting";
    public static final String REGEX_FIELD = "regexCollectionRouting";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // In order: an escaped character, ${name}, $n, then the incomplete forms ${, $ and a trailing backslash
    private static final Pattern REPLACEMENT_TOKEN =
        Pattern.compile("\\\\.|\\$\\{([^}]*)}|\\$(\\d)|\\$\\{|\\$|\\\\", Pattern.DOTALL);

    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    private record RegexRule(Pattern sourceIndex, String collection) {}

    private final Map<String, String> staticRules;
    private final List<RegexRule> regexRules;

    private ServerlessCollectionRouting(Map<String, String> staticRules, List<RegexRule> regexRules) {
        this.staticRules = staticRules;
        this.regexRules = regexRules;
    }

    /** Returns empty when no routing table is configured. */
    public static Optional<ServerlessCollectionRouting> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Collection routing is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Collection routing must be a JSON object with "
                + STATIC_FIELD + " and/or " + REGEX_FIELD);
        }
        root.fieldNames().forEachRemaining(field -> {
            if (!field.equals(STATIC_FIELD) && !field.equals(REGEX_FIELD)) {
                throw new IllegalArgumentException("Unknown collection routing field '" + field + "'");
            }
        });

        var staticRules = new LinkedHashMap<String, String>();
        for (var entry : entries(root, STATIC_FIELD)) {
            var sourceIndex = requiredText(entry, "sourceIndex");
            if (staticRules.put(sourceIndex, requiredText(entry, "collection")) != null) {
                throw new IllegalArgumentException(STATIC_FIELD + " lists source index '" + sourceIndex + "' more than once");
            }
        }
        var regexRules = new ArrayList<RegexRule>();
        for (var entry : entries(root, REGEX_FIELD)) {
            regexRules.add(compileRegexRule(requiredText(entry, "sourceIndex"), requiredText(entry, "collection")));
        }
        if (staticRules.isEmpty() && regexRules.isEmpty()) {
            throw new IllegalArgumentException("Collection routing needs at least one entry in "
                + STATIC_FIELD + " or " + REGEX_FIELD);
        }
        return Optional.of(new ServerlessCollectionRouting(staticRules, regexRules));
    }

    /**
     * Parses the routing table for a target, requiring a table exactly when the target is a
     * collection-routed (per-account) endpoint.
     */
    public static Optional<ServerlessCollectionRouting> forTarget(String json, boolean targetCollectionRouted) {
        var routing = fromJson(json);
        if (targetCollectionRouted && routing.isEmpty()) {
            throw new IllegalArgumentException("A collection-routed target requires --collection-routing");
        }
        if (!targetCollectionRouted && routing.isPresent()) {
            throw new IllegalArgumentException("--collection-routing requires --target-collection-routed");
        }
        return routing;
    }

    private static List<JsonNode> entries(JsonNode root, String field) {
        var list = root.get(field);
        if (list == null || list.isNull()) {
            return List.of();
        }
        if (!list.isArray()) {
            throw new IllegalArgumentException(field + " must be a JSON array");
        }
        var result = new ArrayList<JsonNode>();
        list.forEach(result::add);
        return result;
    }

    private static String requiredText(JsonNode entry, String field) {
        var value = entry.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                "Each collection routing entry needs a non-empty string '" + field + "', got: " + entry);
        }
        return value.asText();
    }

    private static RegexRule compileRegexRule(String sourceIndex, String collection) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(sourceIndex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                "Invalid regex '" + sourceIndex + "' in " + REGEX_FIELD + ": " + e.getDescription(), e);
        }
        validateReplacement(pattern, collection);
        return new RegexRule(pattern, collection);
    }

    /**
     * Rejects replacements that Matcher.appendReplacement would fail on for every index, such as
     * a reference to a group the pattern does not define, so mistakes surface before any work starts.
     */
    private static void validateReplacement(Pattern pattern, String collection) {
        var tokens = REPLACEMENT_TOKEN.matcher(collection);
        while (tokens.find()) {
            var problem = replacementTokenProblem(pattern, tokens.group(), tokens.group(1), tokens.group(2));
            if (problem != null) {
                throw invalidReplacement(pattern, collection, problem);
            }
        }
    }

    /** Returns why a replacement token is invalid for the pattern, or null when it is valid. */
    private static String replacementTokenProblem(Pattern pattern, String token, String groupName, String groupNumber) {
        if (groupName != null) {
            return pattern.namedGroups().containsKey(groupName) ? null : "references undefined group '" + groupName + "'";
        }
        if (groupNumber != null) {
            var groupCount = pattern.matcher("").groupCount();
            return Integer.parseInt(groupNumber) <= groupCount ? null : "references undefined group " + groupNumber;
        }
        return switch (token) {
            case "${" -> "has an unclosed '${'";
            case "$" -> "has '$' that is not a group reference";
            case "\\" -> "ends with an unescaped backslash";
            default -> null; // an escaped character
        };
    }

    private static IllegalArgumentException invalidReplacement(Pattern pattern, String collection, String problem) {
        return new IllegalArgumentException("Collection '" + collection + "' for regex '" + pattern.pattern()
            + "' in " + REGEX_FIELD + " " + problem);
    }

    /** Returns the collection for a source index, or empty when no entry matches. */
    public Optional<String> resolve(String sourceIndex) {
        var staticCollection = staticRules.get(sourceIndex);
        if (staticCollection != null) {
            return Optional.of(staticCollection);
        }
        for (var rule : regexRules) {
            var matcher = rule.sourceIndex().matcher(sourceIndex);
            if (matcher.matches()) {
                var collection = new StringBuilder();
                matcher.appendReplacement(collection, rule.collection());
                if (collection.isEmpty()) {
                    throw new IllegalArgumentException("Collection routing resolved index '" + sourceIndex
                        + "' to an empty collection name");
                }
                return Optional.of(collection.toString());
            }
        }
        return Optional.empty();
    }

    /** Resolves the collection for a source index, failing if no entry matches. */
    public String resolveRequired(String sourceIndex) {
        return resolve(sourceIndex).orElseThrow(() -> new IllegalArgumentException(
            "No collection routing entry matches source index '" + sourceIndex + "'"));
    }

    /**
     * Resolves every source index, failing with all unmatched names at once.
     * Returns the distinct collections in first-seen order.
     */
    public Set<String> resolveAll(Collection<String> sourceIndices) {
        var collections = new LinkedHashSet<String>();
        var unmatched = new ArrayList<String>();
        for (var index : sourceIndices) {
            resolve(index).ifPresentOrElse(collections::add, () -> unmatched.add(index));
        }
        if (!unmatched.isEmpty()) {
            throw new IllegalArgumentException("No collection routing entry matches source indices " + unmatched);
        }
        return collections;
    }

    public static Map<String, List<String>> headersFor(String collection) {
        return collection == null ? null : Map.of(COLLECTION_NAME_HEADER, List.of(collection));
    }
}
