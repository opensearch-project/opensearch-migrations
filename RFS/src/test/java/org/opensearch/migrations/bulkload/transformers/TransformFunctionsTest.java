package org.opensearch.migrations.bulkload.transformers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class TransformFunctionsTest {

    @Test
    public void removeIntermediateMappingsLevels_AsExpected() throws Exception {
        // Extract from {"mappings":[{"_doc":{"properties":{"address":{"type":"text"}}}}])
        ObjectNode testNode1 = new ObjectMapper().createObjectNode();
        ArrayNode mappingsNode1 = new ObjectMapper().createArrayNode();
        ObjectNode docNode1 = new ObjectMapper().createObjectNode();
        ObjectNode propertiesNode1 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode1 = new ObjectMapper().createObjectNode();
        addressNode1.put("type", "text");
        propertiesNode1.set("address", addressNode1);
        docNode1.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode1);
        ObjectNode intermediateNode1 = new ObjectMapper().createObjectNode();
        intermediateNode1.set("_doc", docNode1);
        mappingsNode1.add(intermediateNode1);
        testNode1.set(TransformFunctions.MAPPINGS_KEY_STR, mappingsNode1);

        TransformFunctions.removeIntermediateMappingsLevels(testNode1);
        assertEquals(docNode1.toString(), testNode1.get(TransformFunctions.MAPPINGS_KEY_STR).toString());

        // Extract from {"mappings":[{"properties":{"address":{"type":"text"}}}])
        ObjectNode testNode2 = new ObjectMapper().createObjectNode();
        ArrayNode mappingsNode2 = new ObjectMapper().createArrayNode();
        ObjectNode propertiesNode2 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode2 = new ObjectMapper().createObjectNode();
        addressNode2.put("type", "text");
        propertiesNode2.set("address", addressNode2);
        ObjectNode intermediateNode2 = new ObjectMapper().createObjectNode();
        intermediateNode2.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode2);
        mappingsNode2.add(intermediateNode2);
        testNode2.set(TransformFunctions.MAPPINGS_KEY_STR, mappingsNode2);

        TransformFunctions.removeIntermediateMappingsLevels(testNode2);
        assertEquals(intermediateNode2.toString(), testNode2.get(TransformFunctions.MAPPINGS_KEY_STR).toString());

        // Extract from {"mappings":[])
        ObjectNode testNode3 = new ObjectMapper().createObjectNode();
        ArrayNode mappingsNode3 = new ObjectMapper().createArrayNode();
        testNode3.set(TransformFunctions.MAPPINGS_KEY_STR, mappingsNode3);

        TransformFunctions.removeIntermediateMappingsLevels(testNode3);
        assertEquals(new ObjectMapper().createObjectNode().toString(), testNode3.get(TransformFunctions.MAPPINGS_KEY_STR).toString());
    }

    @Test
    public void convertFlatSettingsToTree_WithConflictingKeys_OrderIndependent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        // Test case 1: knn=true processed before knn.space_type=l2
        ObjectNode flatSettings1 = mapper.createObjectNode();
        flatSettings1.put("knn", "true");
        flatSettings1.put("knn.space_type", "l2");
        flatSettings1.put("index.number_of_replicas", "1");
        
        ObjectNode result1 = TransformFunctions.convertFlatSettingsToTree(flatSettings1);
        
        // Test case 2: knn.space_type=l2 processed before knn=true (reverse order)
        ObjectNode flatSettings2 = mapper.createObjectNode();
        flatSettings2.put("knn.space_type", "l2");
        flatSettings2.put("knn", "true");
        flatSettings2.put("index.number_of_replicas", "1");
        
        ObjectNode result2 = TransformFunctions.convertFlatSettingsToTree(flatSettings2);
        
        // Both results should have the same structure and values regardless of input order
        // Verify the conflicting keys are kept flat in both results
        assertEquals("true", result1.get("knn").asText());
        assertEquals("l2", result1.get("knn.space_type").asText());
        assertEquals("1", result1.get("index").get("number_of_replicas").asText());
        
        assertEquals("true", result2.get("knn").asText());
        assertEquals("l2", result2.get("knn.space_type").asText());
        assertEquals("1", result2.get("index").get("number_of_replicas").asText());
        
        // Verify both have the same keys
        assertEquals(result1.size(), result2.size());
        result1.fieldNames().forEachRemaining(fieldName -> {
            assertTrue(result2.has(fieldName), "Result2 should have field: " + fieldName);
        });
    }
    
    @Test
    public void convertFlatSettingsToTree_ConflictResolutionIntricacies() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        ObjectNode flatSettings = mapper.createObjectNode();
        // Scenario 1: Direct conflict - "knn" exists as both a scalar and a prefix
        flatSettings.put("knn", "true");
        flatSettings.put("knn.space_type", "l2");
        flatSettings.put("knn.algo_param.ef_search", "100");
        
        // Scenario 2: Multi-level nesting without conflicts
        flatSettings.put("index.number_of_replicas", "1");
        flatSettings.put("index.number_of_shards", "5");
        flatSettings.put("index.codec", "best_compression");
        
        // Scenario 3: Conflict at deeper level - "analysis.analyzer" exists as both scalar and prefix
        flatSettings.put("analysis.analyzer", "standard");
        flatSettings.put("analysis.analyzer.custom.type", "custom");
        flatSettings.put("analysis.analyzer.custom.tokenizer", "standard");
        
        // Scenario 4: No conflict - deeply nested path
        flatSettings.put("index.routing.allocation.include.zone", "us-east-1a");
        flatSettings.put("index.routing.allocation.exclude.zone", "us-west-1b");
        
        ObjectNode result = TransformFunctions.convertFlatSettingsToTree(flatSettings);
        
        // Verify Scenario 1: All knn-related keys should be flat due to conflict
        assertEquals("true", result.get("knn").asText(), "knn scalar should be kept flat");
        assertEquals("l2", result.get("knn.space_type").asText(), "knn.space_type should be kept flat");
        assertEquals("100", result.get("knn.algo_param.ef_search").asText(), "knn.algo_param.ef_search should be kept flat");
        assertFalse(result.has("knn") && result.get("knn").isObject(), "knn should not be an object");
        
        // Verify Scenario 2: index settings should be properly nested (no conflicts)
        assertTrue(result.has("index"), "index should exist as nested object");
        assertTrue(result.get("index").isObject(), "index should be an object");
        assertEquals("1", result.get("index").get("number_of_replicas").asText());
        assertEquals("5", result.get("index").get("number_of_shards").asText());
        assertEquals("best_compression", result.get("index").get("codec").asText());
        
        // Verify Scenario 3: All analysis.analyzer keys should be flat due to conflict
        assertEquals("standard", result.get("analysis.analyzer").asText(), "analysis.analyzer scalar should be kept flat");
        assertEquals("custom", result.get("analysis.analyzer.custom.type").asText(), "analysis.analyzer.custom.type should be kept flat");
        assertEquals("standard", result.get("analysis.analyzer.custom.tokenizer").asText(), "analysis.analyzer.custom.tokenizer should be kept flat");
        
        // Verify Scenario 4: Deep nesting should work when no conflicts
        assertTrue(result.get("index").get("routing").isObject(), "routing should be nested");
        assertTrue(result.get("index").get("routing").get("allocation").isObject(), "allocation should be nested");
        assertEquals("us-east-1a", result.get("index").get("routing").get("allocation").get("include").get("zone").asText());
        assertEquals("us-west-1b", result.get("index").get("routing").get("allocation").get("exclude").get("zone").asText());
        
        // Verify that conflicting keys don't create partial nested structures
        assertFalse(result.has("analysis") && result.get("analysis").isObject(), "analysis should not be partially nested when analyzer has conflicts");
    }
    
    @Test
    public void convertFlatSettingsToTree_WithoutConflicts_CreatesNestedStructure() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        ObjectNode flatSettings = mapper.createObjectNode();
        flatSettings.put("index.number_of_replicas", "1");
        flatSettings.put("index.number_of_shards", "5");
        flatSettings.put("index.version.created", "6082499");
        
        ObjectNode result = TransformFunctions.convertFlatSettingsToTree(flatSettings);
        
        // Verify proper nesting without conflicts
        assertEquals("1", result.get("index").get("number_of_replicas").asText());
        assertEquals("5", result.get("index").get("number_of_shards").asText());
        assertEquals("6082499", result.get("index").get("version").get("created").asText());
        
        // Verify no flat keys remain
        assertEquals(false, result.has("index.number_of_replicas"));
        assertEquals(false, result.has("index.number_of_shards"));
        assertEquals(false, result.has("index.version.created"));
    }

    @Test
    public void getMappingsFromBeneathIntermediate_AsExpected() throws Exception {
        // Extract from {"_doc":{"properties":{"address":{"type":"text"}}}}
        ObjectNode testNode1 = new ObjectMapper().createObjectNode();
        ObjectNode docNode1 = new ObjectMapper().createObjectNode();
        ObjectNode propertiesNode1 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode1 = new ObjectMapper().createObjectNode();
        addressNode1.put("type", "text");
        propertiesNode1.set("address", addressNode1);
        docNode1.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode1);
        testNode1.set("_doc", docNode1);

        ObjectNode result1 = TransformFunctions.getMappingsFromBeneathIntermediate(testNode1);
        assertEquals(docNode1.toString(), result1.toString());

        // Extract from {"arbitrary_type":{"properties":{"address":{"type":"text"}}}}
        ObjectNode testNode2 = new ObjectMapper().createObjectNode();
        ObjectNode docNode2 = new ObjectMapper().createObjectNode();
        ObjectNode propertiesNode2 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode2 = new ObjectMapper().createObjectNode();
        addressNode2.put("type", "text");
        propertiesNode2.set("address", addressNode2);
        docNode2.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode2);
        testNode2.set("arbitrary_type", docNode2);

        ObjectNode result2 = TransformFunctions.getMappingsFromBeneathIntermediate(testNode2);
        assertEquals(docNode2.toString(), result2.toString());

        // Extract from {"properties":{"address":{"type":"text"}}
        ObjectNode testNode3 = new ObjectMapper().createObjectNode();
        ObjectNode propertiesNode3 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode3 = new ObjectMapper().createObjectNode();
        addressNode3.put("type", "text");
        propertiesNode3.set("address", addressNode3);
        testNode3.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode3);

        ObjectNode result3 = TransformFunctions.getMappingsFromBeneathIntermediate(testNode3);
        assertEquals(testNode3.toString(), result3.toString());
    }

}
