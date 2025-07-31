package org.opensearch.migrations.commands;

import org.opensearch.migrations.utils.JsonUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigureResultTest {

    @Test
    public void testAsJsonOutput() throws Exception {
        ConfigureResult result = new ConfigureResult(2, "Test error message");
        String json = result.asJsonOutput();
        
        ObjectMapper mapper = JsonUtils.getObjectMapper();
        JsonNode node = mapper.readTree(json);
        
        assertEquals(2, node.get("exitCode").asInt());
        assertEquals("Test error message", node.get("errorMessage").asText());
    }
    
    @Test
    public void testAsJsonOutputWithoutErrorMessage() throws Exception {
        ConfigureResult result = new ConfigureResult(0, null);
        String json = result.asJsonOutput();
        
        ObjectMapper mapper = JsonUtils.getObjectMapper();
        JsonNode node = mapper.readTree(json);
        
        assertEquals(0, node.get("exitCode").asInt());
        assertFalse(node.has("errorMessage"), "JSON should not contain errorMessage when it's null");
    }
    
    @Test
    public void testAsCliOutput() {
        ConfigureResult result = new ConfigureResult(1, "Error occurred");
        String output = result.asCliOutput();
        
        // Verify that the toString representation is used for CLI output
        assertTrue(output.contains("1"));
        assertTrue(output.contains("Error occurred"));
    }
}
