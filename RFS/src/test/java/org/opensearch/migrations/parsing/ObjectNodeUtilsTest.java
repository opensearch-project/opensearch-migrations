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

}
