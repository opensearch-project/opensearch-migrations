package org.opensearch.migrations.parsing;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ObjectNodeUtils {
    private ObjectNodeUtils() {}

    /**
     * Removes a setting addressed by a dotted path, tolerating both shapes seen in
     * index-settings JSON: a literal flat key (settings["index.knn.algo_param.m"])
     * or a nested-object path (settings.index.knn.algo_param.m). The flat key is
     * matched first. This flat-key handling is what stops ES 7.x index-level knn
     * params from surviving IndexCreator's strip-and-retry into an infinite loop.
     */
    public static void removeFieldsByPath(ObjectNode node, String path) {
        if (node == null || path == null || path.isEmpty()) {
            return;
        }

        if (node.has(path)) {
            node.remove(path);
            return;
        }

        var pathParts = path.split("\\.");

        if (pathParts.length == 1) {
            node.remove(pathParts[0]);
            return;
        }

        var currentNode = node;
        for (int i = 0; i < pathParts.length - 1; i++) {
            var nextNode = currentNode.get(pathParts[i]);
            if (nextNode != null && nextNode.isObject()) {
                currentNode = (ObjectNode) nextNode;
            } else {
                return;
            }
        }
        currentNode.remove(pathParts[pathParts.length - 1]);
    }

    /**
     * Removes the given filter names from any analyzer "filter" array under the analysis
     * settings of an index/template body. Handles both shapes:
     *   settings.analysis.analyzer.&lt;name&gt;.filter
     *   settings.index.analysis.analyzer.&lt;name&gt;.filter
     * No-ops if the structure is missing.
     */
    public static void removeAnalyzerFilters(ObjectNode body, Set<String> filtersToRemove) {
        if (body == null || filtersToRemove == null || filtersToRemove.isEmpty()) {
            return;
        }
        var settings = body.get("settings");
        if (settings instanceof ObjectNode) {
            removeAnalyzerFiltersFromSettings((ObjectNode) settings, filtersToRemove);
        }
        // Templates can place settings/mappings under "template"
        var template = body.get("template");
        if (template instanceof ObjectNode) {
            var templateSettings = template.get("settings");
            if (templateSettings instanceof ObjectNode) {
                removeAnalyzerFiltersFromSettings((ObjectNode) templateSettings, filtersToRemove);
            }
        }
    }

    private static void removeAnalyzerFiltersFromSettings(ObjectNode settings, Set<String> filtersToRemove) {
        var analysis = settings.get("analysis");
        if (analysis == null) {
            var indexNode = settings.get("index");
            if (indexNode instanceof ObjectNode) {
                analysis = indexNode.get("analysis");
            }
        }
        if (!(analysis instanceof ObjectNode)) {
            return;
        }
        var analyzers = analysis.get("analyzer");
        if (!(analyzers instanceof ObjectNode)) {
            return;
        }
        ((ObjectNode) analyzers).fields().forEachRemaining(entry -> {
            var analyzerDef = entry.getValue();
            if (analyzerDef instanceof ObjectNode) {
                var filterNode = analyzerDef.get("filter");
                if (filterNode instanceof ArrayNode) {
                    removeMatchingArrayElements((ArrayNode) filterNode, filtersToRemove);
                }
            }
        });
    }

    private static void removeMatchingArrayElements(ArrayNode array, Set<String> values) {
        for (int i = array.size() - 1; i >= 0; i--) {
            JsonNode element = array.get(i);
            if (element != null && element.isTextual() && values.contains(element.asText())) {
                array.remove(i);
            }
        }
    }
}
