package org.opensearch.migrations.cli;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.bulkload.transformers.Transformer;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class TransformersTest {

    @Test
    public void testEmptyCliOutput() {
        var transformers = new Transformers(new ArrayList<>(), null);
        var output = transformers.asCliOutput();
        assertNotNull(output);
        assertTrue(output.contains("Transformations:"));
        assertTrue(output.contains("<None Found>"));
    }

    @Test
    public void testEmptyJsonOutput() {
        var transformers = new Transformers(new ArrayList<>(), null);
        JsonNode jsonNode = transformers.asJsonOutput();
        assertNotNull(jsonNode);
        assertTrue(jsonNode.has("transformers"));
        assertTrue(jsonNode.get("transformers").isEmpty());
    }

    @Test
    public void testSingleTransformerCliOutput() {
        List<Transformers.TransformerInfo> infos = new ArrayList<>();
        var descLines = new ArrayList<String>();
        descLines.add("Description Line 1");
        descLines.add("Description Line 2");
        
        var transformerInfo = Transformers.TransformerInfo.builder()
            .name("TestTransformer")
            .descriptionLines(descLines)
            .url("https://example.com")
            .build();
        
        infos.add(transformerInfo);
        var transformers = new Transformers(infos, mock(Transformer.class));
        
        var output = transformers.asCliOutput();
        assertNotNull(output);
        assertTrue(output.contains("TestTransformer"));
        assertTrue(output.contains("Description Line 1"));
        assertTrue(output.contains("Description Line 2"));
        assertTrue(output.contains("https://example.com"));
    }

    @Test
    public void testMultipleTransformersJsonOutput() {
        List<Transformers.TransformerInfo> infos = new ArrayList<>();
        
        var transformerInfo1 = Transformers.TransformerInfo.builder()
            .name("Transformer1")
            .descriptionLine("Description 1")
            .build();
        
        var transformerInfo2 = Transformers.TransformerInfo.builder()
            .name("Transformer2")
            .descriptionLine("Description 2")
            .url("https://example.com")
            .build();
        
        infos.add(transformerInfo1);
        infos.add(transformerInfo2);
        
        var transformers = new Transformers(infos, mock(Transformer.class));
        JsonNode jsonNode = transformers.asJsonOutput();
        
        assertNotNull(jsonNode);
        assertTrue(jsonNode.has("transformers"));
        assertEquals(2, jsonNode.get("transformers").size());
        
        JsonNode transformer1 = jsonNode.get("transformers").get(0);
        assertEquals("Transformer1", transformer1.get("name").asText());
        assertEquals(1, transformer1.get("description").size());
        assertEquals("Description 1", transformer1.get("description").get(0).asText());
        assertFalse(transformer1.has("url"));
        
        JsonNode transformer2 = jsonNode.get("transformers").get(1);
        assertEquals("Transformer2", transformer2.get("name").asText());
        assertEquals("Description 2", transformer2.get("description").get(0).asText());
        assertEquals("https://example.com", transformer2.get("url").asText());
    }
}
