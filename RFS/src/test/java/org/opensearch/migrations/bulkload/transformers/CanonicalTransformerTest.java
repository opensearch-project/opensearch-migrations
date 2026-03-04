package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.bulkload.version_os_2_11.IndexMetadataData_OS_2_11;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalTransformerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- transformIndexMetadata ---

    @Test
    void transformIndexMetadata_stripsTypeMappingWrapper() {
        var root = indexRoot(
            "{\"_doc\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}",
            "{\"number_of_shards\":\"1\",\"number_of_replicas\":\"0\"}"
        );
        var index = new IndexMetadataData_OS_2_11(root, "idx1", "test_index");
        var transformer = new CanonicalTransformer(1);

        var result = transformer.transformIndexMetadata(index);

        assertEquals(1, result.size());
        var mappings = result.get(0).getMappings();
        assertTrue(mappings.has("properties"), "Type wrapper should be stripped");
        assertFalse(mappings.has("_doc"), "_doc wrapper should be removed");
        assertEquals("text", mappings.path("properties").path("title").path("type").asText());
    }

    @Test
    void transformIndexMetadata_preservesMappingsWithoutTypeWrapper() {
        var root = indexRoot(
            "{\"properties\":{\"name\":{\"type\":\"keyword\"}}}",
            "{\"number_of_shards\":\"1\",\"number_of_replicas\":\"0\"}"
        );
        var index = new IndexMetadataData_OS_2_11(root, "idx1", "test_index");
        var transformer = new CanonicalTransformer(1);

        var result = transformer.transformIndexMetadata(index);

        var mappings = result.get(0).getMappings();
        assertTrue(mappings.has("properties"));
        assertEquals("keyword", mappings.path("properties").path("name").path("type").asText());
    }

    @Test
    void transformIndexMetadata_convertsFlatSettingsToTree() {
        var root = MAPPER.createObjectNode();
        root.set("mappings", MAPPER.createObjectNode());
        root.set("aliases", MAPPER.createObjectNode());
        var settings = MAPPER.createObjectNode();
        settings.put("index.number_of_shards", "1");
        settings.put("index.number_of_replicas", "0");
        settings.put("index.analysis.analyzer.my_analyzer.type", "custom");
        root.set("settings", settings);

        var index = new IndexMetadataData_OS_2_11(root, "idx1", "test_index");
        var transformer = new CanonicalTransformer(1);

        var result = transformer.transformIndexMetadata(index);

        var resultSettings = result.get(0).getSettings();
        // Flat settings should be converted to tree
        assertEquals("1", resultSettings.path("number_of_shards").asText());
        assertEquals("0", resultSettings.path("number_of_replicas").asText());
    }

    @Test
    void transformIndexMetadata_removesIntermediateIndexSettingsLevel() {
        var root = MAPPER.createObjectNode();
        root.set("mappings", MAPPER.createObjectNode());
        root.set("aliases", MAPPER.createObjectNode());
        var settings = MAPPER.createObjectNode();
        var indexSettings = MAPPER.createObjectNode();
        indexSettings.put("number_of_shards", "1");
        indexSettings.put("number_of_replicas", "0");
        settings.set("index", indexSettings);
        root.set("settings", settings);

        var index = new IndexMetadataData_OS_2_11(root, "idx1", "test_index");
        var transformer = new CanonicalTransformer(1);

        var result = transformer.transformIndexMetadata(index);

        var resultSettings = result.get(0).getSettings();
        assertFalse(resultSettings.has("index"), "Intermediate 'index' level should be removed");
        assertEquals("1", resultSettings.path("number_of_shards").asText());
    }

    @Test
    void transformIndexMetadata_fixesReplicasForDimensionality() {
        var root = indexRoot(
            "{}",
            "{\"number_of_shards\":\"1\",\"number_of_replicas\":\"1\"}"
        );
        var index = new IndexMetadataData_OS_2_11(root, "idx1", "test_index");
        // dimensionality=3 means total copies must be multiple of 3
        var transformer = new CanonicalTransformer(3);

        var result = transformer.transformIndexMetadata(index);

        var resultSettings = result.get(0).getSettings();
        // 1 replica + 1 primary = 2 copies, next multiple of 3 = 3, so replicas = 2
        assertEquals(2, resultSettings.path("number_of_replicas").asInt());
    }

    @Test
    void transformIndexMetadata_appliesTransformationRules() {
        var root = indexRoot(
            "{\"properties\":{\"field1\":{\"type\":\"text\"}}}",
            "{\"number_of_shards\":\"1\",\"number_of_replicas\":\"0\"}"
        );
        var index = new IndexMetadataData_OS_2_11(root, "idx1", "test_index");

        // Custom rule that adds a field to mappings
        var addFieldRule = new org.opensearch.migrations.transformation.TransformationRule<org.opensearch.migrations.transformation.entity.Index>() {
            @Override
            public org.opensearch.migrations.transformation.CanApplyResult canApply(org.opensearch.migrations.transformation.entity.Index entity) {
                return org.opensearch.migrations.transformation.CanApplyResult.YES;
            }

            @Override
            public boolean applyTransformation(org.opensearch.migrations.transformation.entity.Index entity) {
                var props = (ObjectNode) entity.getRawJson().path("mappings").path("properties");
                props.putObject("added_field").put("type", "keyword");
                return true;
            }
        };

        var transformer = new CanonicalTransformer(1, List.of(addFieldRule), List.of());
        var result = transformer.transformIndexMetadata(index);

        var mappings = result.get(0).getMappings();
        assertTrue(mappings.path("properties").has("added_field"));
        assertEquals("keyword", mappings.path("properties").path("added_field").path("type").asText());
    }

    @Test
    void transformIndexMetadata_handlesEmptyMappings() {
        var root = indexRoot("{}", "{\"number_of_shards\":\"1\",\"number_of_replicas\":\"0\"}");
        var index = new IndexMetadataData_OS_2_11(root, "idx1", "test_index");
        var transformer = new CanonicalTransformer(1);

        var result = transformer.transformIndexMetadata(index);

        assertEquals(1, result.size());
        assertNotNull(result.get(0).getMappings());
    }

    // --- transformGlobalMetadata ---

    @Test
    void transformGlobalMetadata_transformsLegacyTemplates() {
        var root = MAPPER.createObjectNode();
        var templates = MAPPER.createObjectNode();
        var template = MAPPER.createObjectNode();
        template.put("order", 0);
        var templateMappings = MAPPER.createObjectNode();
        templateMappings.putObject("properties").putObject("title").put("type", "text");
        template.set("mappings", templateMappings);
        var templateSettings = MAPPER.createObjectNode();
        templateSettings.put("number_of_shards", "1");
        templateSettings.put("number_of_replicas", "0");
        template.set("settings", templateSettings);
        template.set("aliases", MAPPER.createObjectNode());
        templates.set("my_template", template);
        root.set("templates", templates);

        var globalMetadata = new org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11(root);
        var transformer = new CanonicalTransformer(1);

        var result = transformer.transformGlobalMetadata(globalMetadata);

        var resultTemplates = result.getTemplates();
        assertNotNull(resultTemplates);
        assertTrue(resultTemplates.has("my_template"));
    }

    @Test
    void transformGlobalMetadata_handlesNullTemplates() {
        var root = MAPPER.createObjectNode();
        // No templates at all
        var globalMetadata = new org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11(root);
        var transformer = new CanonicalTransformer(1);

        // Should not throw
        var result = transformer.transformGlobalMetadata(globalMetadata);
        assertNotNull(result);
    }

    // --- Helpers ---

    private static ObjectNode indexRoot(String mappingsJson, String settingsJson) {
        try {
            var root = MAPPER.createObjectNode();
            root.set("mappings", MAPPER.readTree(mappingsJson));
            root.set("settings", MAPPER.readTree(settingsJson));
            root.set("aliases", MAPPER.createObjectNode());
            return root;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
