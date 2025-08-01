package org.opensearch.migrations.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigureResultTest {

    @Test
    public void testAsCliOutput() {
        ConfigureResult result = new ConfigureResult(0, null);
        assertNotNull(result.asCliOutput());
        assertTrue(result.asCliOutput().contains("0"));
        
        result = new ConfigureResult(42, "Some error");
        assertNotNull(result.asCliOutput());
        assertTrue(result.asCliOutput().contains("42"));
        assertTrue(result.asCliOutput().contains("Some error"));
    }
    
    @Test
    public void testAsJsonOutput() {
        ConfigureResult result = new ConfigureResult(0, null);
        var jsonNode = result.asJsonOutput();
        
        assertEquals(0, jsonNode.get("exitCode").asInt());
        assertFalse(jsonNode.has("errorMessage"));
        
        result = new ConfigureResult(42, "Some error");
        jsonNode = result.asJsonOutput();
        
        assertEquals(42, jsonNode.get("exitCode").asInt());
        assertEquals("Some error", jsonNode.get("errorMessage").asText());
    }
    
    @Test
    public void testGetters() {
        ConfigureResult result = new ConfigureResult(42, "Some error");
        
        assertEquals(42, result.getExitCode());
        assertEquals("Some error", result.getErrorMessage());
    }
}
