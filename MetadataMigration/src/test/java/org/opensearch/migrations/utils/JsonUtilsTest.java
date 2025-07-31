package org.opensearch.migrations.utils;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JsonUtilsTest {

    private static class TestClass {
        @JsonProperty
        private final String name;
        
        @JsonProperty
        private final int value;
        
        public TestClass(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

    @Test
    public void testObjectSerialization() throws Exception {
        // Test simple object serialization
        TestClass testObject = new TestClass("test", 42);
        String json = JsonUtils.toJson(testObject, "TestClass");
        
        // Verify serialization worked correctly
        JsonNode node = JsonUtils.getObjectMapper().readTree(json);
        assertEquals("test", node.get("name").asText());
        assertEquals(42, node.get("value").asInt());
    }
    
    @Test
    public void testMapSerialization() throws Exception {
        // Test Map serialization
        Map<String, Object> map = new HashMap<>();
        map.put("stringKey", "stringValue");
        map.put("intKey", 123);
        map.put("booleanKey", true);
        
        String json = JsonUtils.toJson(map, "Map");
        
        // Verify serialization worked correctly
        JsonNode node = JsonUtils.getObjectMapper().readTree(json);
        assertEquals("stringValue", node.get("stringKey").asText());
        assertEquals(123, node.get("intKey").asInt());
        assertTrue(node.get("booleanKey").asBoolean());
    }
    
    @Test
    public void testErrorHandling() {
        // Create a circular reference that will definitely cause serialization to fail
        class CircularReference {
            @JsonProperty
            private CircularReference self;
            
            public CircularReference() {
                this.self = this; // Create circular reference
            }
        }
        
        // This should not throw an exception, but return an error JSON
        String json = JsonUtils.toJson(new CircularReference(), "ProblematicObject");
        System.out.println("Error JSON: " + json); // Print for debugging
        assertTrue(json.contains("error"), "JSON should contain error field");
        assertTrue(json.contains("ProblematicObject"), "JSON should contain the error context");
    }
    
    @Test
    public void testObjectMapperSingleton() {
        // Verify that we always get the same ObjectMapper instance
        assertSame(JsonUtils.getObjectMapper(), JsonUtils.getObjectMapper());
    }
}
