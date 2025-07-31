package org.opensearch.migrations.cli;


import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.cli.Transformers.TransformerInfo;
import org.opensearch.migrations.utils.JsonUtils;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.opensearch.migrations.matchers.HasLineCount.hasLineCount;

public class TransformersTest {
    /**
     * Tests both string and JSON output formats for each test case to make comparisons clearer
     * and assertions more uniform across formats.
     */
    
    @Test
    @DisplayName("Empty Transformers - Both Output Formats")
    void testEmpty() throws Exception {
        var transformers = Transformers.builder().build();

        // Test String Output
        var stringOutput = transformers.asCliOutput();
        
        // Test JSON Output
        String jsonOutput = transformers.asJsonOutput();
        JsonNode jsonNode = JsonUtils.getObjectMapper().readTree(jsonOutput);
        
        // String output assertions
        assertThat(stringOutput, containsString("Transformations:"));
        assertThat(stringOutput, containsString("<None Found>"));
        assertThat(stringOutput, hasLineCount(2));
        
        // JSON output assertions
        assertThat(jsonNode, is(notNullValue()));
        assertTrue(jsonNode.has("transformers"), "JSON should contain transformers field");
        assertEquals(0, jsonNode.get("transformers").size(), "transformers should be empty");
    }

    @Test
    @DisplayName("Transformers with Data - Both Output Formats")
    void testWithTransformers() throws Exception {
        var transformers = Transformers.builder()
            .transformerInfo(TransformerInfo.builder()
                .name("Transformer1")
                .descriptionLine("Description line 1")
                .descriptionLine("Description line 2")
                .url("https://example.com/transformer1")
                .build())
            .transformerInfo(TransformerInfo.builder()
                .name("Transformer2")
                .descriptionLine("Another description")
                .build())
            .transformer(mock(Transformer.class))  // Add mock transformer
            .build();

        // Test String Output
        var stringOutput = transformers.asCliOutput();
        
        // Test JSON Output
        String jsonOutput = transformers.asJsonOutput();
        JsonNode jsonNode = JsonUtils.getObjectMapper().readTree(jsonOutput);
        
        // String output assertions
        assertThat(stringOutput, containsString("Transformations:"));
        assertThat(stringOutput, containsString("Transformer1:"));
        assertThat(stringOutput, containsString("Description line 1"));
        assertThat(stringOutput, containsString("Description line 2"));
        assertThat(stringOutput, containsString("Learn more at https://example.com/transformer1"));
        assertThat(stringOutput, containsString("Transformer2:"));
        assertThat(stringOutput, containsString("Another description"));
        assertThat(stringOutput, not(containsString("<None Found>")));
        assertThat(stringOutput, hasLineCount(7));
        
        // JSON output assertions
        assertTrue(jsonNode.has("transformers"), "JSON should contain transformers field");
        JsonNode transformersList = jsonNode.get("transformers");
        assertEquals(2, transformersList.size());
        
        // Verify first transformer
        JsonNode transformer1 = transformersList.get(0);
        assertEquals("Transformer1", transformer1.get("name").asText());
        JsonNode description1 = transformer1.get("description");
        assertTrue(description1.isArray());
        assertEquals(2, description1.size());
        assertEquals("Description line 1", description1.get(0).asText());
        assertEquals("Description line 2", description1.get(1).asText());
        assertEquals("https://example.com/transformer1", transformer1.get("url").asText());
        
        // Verify second transformer
        JsonNode transformer2 = transformersList.get(1);
        assertEquals("Transformer2", transformer2.get("name").asText());
        JsonNode description2 = transformer2.get("description");
        assertTrue(description2.isArray());
        assertEquals(1, description2.size());
        assertEquals("Another description", description2.get(0).asText());
        assertTrue(!transformer2.has("url"), "Second transformer should not have URL");
    }
}
