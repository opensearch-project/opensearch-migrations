package org.opensearch.migrations.commands;

import org.opensearch.migrations.utils.JsonUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SimpleResultTest {

    @Test
    public void testSuccessFactory() {
        SimpleResult result = SimpleResult.success();
        assertEquals(0, result.getExitCode());
        assertNull(result.getErrorMessage());
    }

    @Test
    public void testErrorFactory() {
        SimpleResult result = SimpleResult.error("Test error");
        assertEquals(1, result.getExitCode());
        assertEquals("Test error", result.getErrorMessage());
    }

    @Test
    public void testErrorWithCodeFactory() {
        SimpleResult result = SimpleResult.error(42, "Custom error code");
        assertEquals(42, result.getExitCode());
        assertEquals("Custom error code", result.getErrorMessage());
    }

    @Test
    public void testAsCliOutput() {
        SimpleResult successResult = SimpleResult.success();
        assertEquals("Success", successResult.asCliOutput());
        
        SimpleResult errorResult = SimpleResult.error("Something went wrong");
        assertEquals("Error (code 1): Something went wrong", errorResult.asCliOutput());
    }
    
    @Test
    public void testAsJsonOutput() throws Exception {
        SimpleResult result = new SimpleResult(42, "Error message");
        String json = result.asJsonOutput();
        
        ObjectMapper mapper = JsonUtils.getObjectMapper();
        JsonNode node = mapper.readTree(json);
        
        assertEquals(42, node.get("exitCode").asInt());
        assertEquals("Error message", node.get("errorMessage").asText());
    }
    
    @Test
    public void testAsJsonOutputWithNullErrorMessage() throws Exception {
        SimpleResult result = SimpleResult.success();
        String json = result.asJsonOutput();
        
        ObjectMapper mapper = JsonUtils.getObjectMapper();
        JsonNode node = mapper.readTree(json);
        
        assertEquals(0, node.get("exitCode").asInt());
        assertFalse(node.has("errorMessage"), "JSON should not contain errorMessage when it's null");
    }
    
    @Test
    public void testToString() {
        SimpleResult result = new SimpleResult(1, "Error");
        String stringRepresentation = result.toString();
        
        // Verify toString contains both fields
        assertTrue(stringRepresentation.contains("exitCode=1"));
        assertTrue(stringRepresentation.contains("errorMessage=Error"));
    }
}
