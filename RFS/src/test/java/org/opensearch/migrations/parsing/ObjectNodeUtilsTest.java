package org.opensearch.migrations.parsing;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

class ObjectNodeUtilsTest {

    // Recursively collects and returns all field names
    private Set<String> collectAllFields(JsonNode node, String prefix) {
        Set<String> fields = new HashSet<>();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> iter = node.fields();
            while (iter.hasNext()) {
                Map.Entry<String, JsonNode> entry = iter.next();
                // Create a fully qualified field name
                String qualifiedName = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                fields.add(qualifiedName);
                fields.addAll(collectAllFields(entry.getValue(), qualifiedName));
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                fields.addAll(collectAllFields(element, prefix));
            }
        }
        return fields;
    }

    @Test
    void testRemoveFieldsByPath_topLevelField() {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        String fieldToRemove = "name";
        String fieldToRemain = "age";
        node.put(fieldToRemove, "testUser");
        node.put(fieldToRemain, 25);

        // Define expected fields
        Set<String> expectedFields = new HashSet<>(List.of(fieldToRemain));

        ObjectNodeUtils.removeFieldsByPath(node, fieldToRemove);

        // Collect actual field names from the node
        Set<String> actualFields = collectAllFields(node, "");

        assertThat(expectedFields, equalTo(actualFields));
    }

    @Test
    void testRemoveFieldsByPath_topLevelFieldThatDNE() {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        String nonExistentFieldToRemove = "unknown";
        String fieldToRemain = "age";
        node.put(fieldToRemain, 25);

        // Define expected fields
        Set<String> expectedFields = new HashSet<>(List.of(fieldToRemain));

        ObjectNodeUtils.removeFieldsByPath(node, nonExistentFieldToRemove);

        // Collect actual field names from the node
        Set<String> actualFields = collectAllFields(node, "");

        assertThat(expectedFields, equalTo(actualFields));
    }

    @Test
    void testRemoveFieldsByPath_nestedFieldThatDNE() {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        String fieldToRemain = "age";
        node.put(fieldToRemain, 25);

        // Define expected fields
        Set<String> expectedFields = new HashSet<>(List.of(fieldToRemain));

        ObjectNodeUtils.removeFieldsByPath(node, "setting.unknown");

        // Collect actual field names from the node
        Set<String> actualFields = collectAllFields(node, "");

        assertThat(expectedFields, equalTo(actualFields));
    }

    @Test
    void testRemoveFieldsByPath_nestedField() {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("age", 25);

        ObjectNode address = mapper.createObjectNode();
        address.put("city", "New York");
        address.put("zip", "10001");
        node.set("address", address);

        // Define expected fields
        Set<String> expectedFields = new HashSet<>(List.of("age", "address", "address.city"));

        ObjectNodeUtils.removeFieldsByPath(node, "address.zip");

        // Collect actual field names from the node
        Set<String> actualFields = collectAllFields(node, "");

        assertThat(expectedFields, equalTo(actualFields));
    }

    // --- removeAnalyzerFilters tests ---

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Helper: builds settings.index.analysis.analyzer structure with given analyzers.
     * Each entry in analyzerFilters maps analyzer-name to its filter list.
     */
    private ObjectNode buildIndexBody(Map<String, List<String>> analyzerFilters) {
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode settings = body.putObject("settings");
        ObjectNode index = settings.putObject("index");
        ObjectNode analysis = index.putObject("analysis");
        ObjectNode analyzers = analysis.putObject("analyzer");
        analyzerFilters.forEach((name, filters) -> {
            ObjectNode analyzerDef = analyzers.putObject(name);
            analyzerDef.put("type", "custom");
            var filterArray = analyzerDef.putArray("filter");
            filters.forEach(filterArray::add);
        });
        return body;
    }

    /** Helper: extracts the filter list for a named analyzer under settings.index.analysis */
    private List<String> getFilters(ObjectNode body, String analyzerName) {
        var filterNode = body.at("/settings/index/analysis/analyzer/" + analyzerName + "/filter");
        List<String> result = new ArrayList<>();
        filterNode.forEach(n -> result.add(n.asText()));
        return result;
    }

    @Test
    void removeAnalyzerFilters_removesFilterFromIndexAnalysis() {
        ObjectNode body = buildIndexBody(Map.of(
            "my_analyzer", List.of("lowercase", "word_delimiter_graph", "stop")
        ));

        ObjectNodeUtils.removeAnalyzerFilters(body, Set.of("word_delimiter_graph"));

        assertThat(getFilters(body, "my_analyzer"), equalTo(List.of("lowercase", "stop")));
    }

    @Test
    void removeAnalyzerFilters_multipleAnalyzers_onlyAffectsThoseWithFilter() {
        ObjectNode body = buildIndexBody(Map.of(
            "analyzer_a", List.of("lowercase", "stop", "word_delimiter_graph"),
            "analyzer_b", List.of("lowercase", "stemmer")
        ));

        ObjectNodeUtils.removeAnalyzerFilters(body, Set.of("word_delimiter_graph"));

        assertThat(getFilters(body, "analyzer_a"), equalTo(List.of("lowercase", "stop")));
        assertThat(getFilters(body, "analyzer_b"), equalTo(List.of("lowercase", "stemmer")));
    }

    @Test
    void removeAnalyzerFilters_noAnalysisSection_isNoOp() {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("settings").put("number_of_shards", 1);

        // Should not throw
        ObjectNodeUtils.removeAnalyzerFilters(body, Set.of("stop"));

        // settings unchanged
        assertThat(body.get("settings").get("number_of_shards").asInt(), equalTo(1));
    }

    @Test
    void removeAnalyzerFilters_filterNotPresent_isNoOp() {
        ObjectNode body = buildIndexBody(Map.of(
            "my_analyzer", List.of("lowercase", "stop")
        ));

        ObjectNodeUtils.removeAnalyzerFilters(body, Set.of("nonexistent_filter"));

        assertThat(getFilters(body, "my_analyzer"), equalTo(List.of("lowercase", "stop")));
    }

    @Test
    void removeAnalyzerFilters_templateBodyWithIndexPatterns() {
        // Simulates an index template body where settings live under "template.settings"
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("index_patterns").add("logs-*");
        ObjectNode template = body.putObject("template");
        ObjectNode settings = template.putObject("settings");
        ObjectNode analysis = settings.putObject("analysis");
        ObjectNode analyzers = analysis.putObject("analyzer");
        ObjectNode analyzerDef = analyzers.putObject("my_analyzer");
        analyzerDef.put("type", "custom");
        analyzerDef.putArray("filter").add("lowercase").add("word_delimiter_graph").add("stop");

        ObjectNodeUtils.removeAnalyzerFilters(body, Set.of("word_delimiter_graph"));

        var filterNode = body.at("/template/settings/analysis/analyzer/my_analyzer/filter");
        List<String> result = new ArrayList<>();
        filterNode.forEach(n -> result.add(n.asText()));
        assertThat(result, equalTo(List.of("lowercase", "stop")));
    }

    @Test
    void removeAnalyzerFilters_emptyFiltersToRemove_isNoOp() {
        ObjectNode body = buildIndexBody(Map.of(
            "my_analyzer", List.of("lowercase", "stop")
        ));

        ObjectNodeUtils.removeAnalyzerFilters(body, Set.of());

        assertThat(getFilters(body, "my_analyzer"), equalTo(List.of("lowercase", "stop")));
    }

    @Test
    void removeAnalyzerFilters_settingsAnalysisPathWithoutIndex() {
        // Tests the settings.analysis.analyzer path (no "index" intermediary)
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode settings = body.putObject("settings");
        ObjectNode analysis = settings.putObject("analysis");
        ObjectNode analyzers = analysis.putObject("analyzer");
        ObjectNode analyzerDef = analyzers.putObject("my_analyzer");
        analyzerDef.put("type", "custom");
        analyzerDef.putArray("filter").add("lowercase").add("stop").add("word_delimiter_graph");

        ObjectNodeUtils.removeAnalyzerFilters(body, Set.of("word_delimiter_graph"));

        var filterNode = body.at("/settings/analysis/analyzer/my_analyzer/filter");
        List<String> result = new ArrayList<>();
        filterNode.forEach(n -> result.add(n.asText()));
        assertThat(result, equalTo(List.of("lowercase", "stop")));
    }

    @Test
    void removeAnalyzerFilters_nullBody_isNoOp() {
        // Should not throw
        ObjectNodeUtils.removeAnalyzerFilters(null, Set.of("stop"));
    }

    @Test
    void removeAnalyzerFilters_nullFiltersToRemove_isNoOp() {
        ObjectNode body = buildIndexBody(Map.of(
            "my_analyzer", List.of("lowercase", "stop")
        ));

        // Should not throw
        ObjectNodeUtils.removeAnalyzerFilters(body, null);

        assertThat(getFilters(body, "my_analyzer"), equalTo(List.of("lowercase", "stop")));
    }

}
