package org.opensearch.migrations.cli;

import java.util.List;

import org.opensearch.migrations.cli.Items.ItemsBuilder;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.text.StringContainsInOrder.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opensearch.migrations.matchers.ContainsStringCount.containsStringCount;
import static org.opensearch.migrations.matchers.HasLineCount.hasLineCount;
import static org.opensearch.migrations.metadata.CreationResult.CreationFailureType.TARGET_CLUSTER_FAILURE;

public class ItemsTest {
    /**
     * Tests both string and JSON output formats for each test case to make comparisons clearer
     * and assertions more uniform across formats.
     */

    @Test
    @DisplayName("Empty Items - Both Output Formats")
    void testEmpty() throws Exception {
        var items = createEmptyItemsBuilder()
            .build();

        // Test String Output
        var stringOutput = items.asCliOutput();
        
        // Test JSON Output
        var jsonNode = items.asJsonOutput();
        
        // String output assertions
        assertThat(stringOutput, containsString("Migrated Items:"));
        assertThat(stringOutput, containsString("Index Templates:"));
        assertThat(stringOutput, containsString("Component Templates:"));
        assertThat(stringOutput, containsString("Indexes:"));
        assertThat(stringOutput, containsString("Aliases:"));
        assertThat(stringOutput, containsStringCount(Items.NONE_FOUND_MARKER, 4));
        assertThat(stringOutput, hasLineCount(12));
        
        // JSON output assertions
        assertThat(jsonNode, is(notNullValue()));
        assertTrue(jsonNode.has("dryRun"), "JSON should contain dryRun field");
        assertFalse(jsonNode.get("dryRun").asBoolean(), "dryRun should be false by default");
        assertTrue(jsonNode.has("indexTemplates"), "JSON should contain indexTemplates field");
        assertTrue(jsonNode.has("componentTemplates"), "JSON should contain componentTemplates field");
        assertTrue(jsonNode.has("indexes"), "JSON should contain indexes field");
        assertTrue(jsonNode.has("aliases"), "JSON should contain aliases field");
        assertTrue(jsonNode.has("errors"), "JSON should contain errors field");
        assertEquals(0, jsonNode.get("errors").size(), "errors should be empty");
    }

    @Test
    @DisplayName("Items with Full Data - Both Output Formats")
    void testFull() throws Exception {
        var items = Items.builder()
            .dryRun(true)
            .indexTemplates(List.of(
                CreationResult.builder().name("it1").build(),
                CreationResult.builder().name("it2").build()
            ))
            .componentTemplates(List.of(
                CreationResult.builder().name("ct1").build(),
                CreationResult.builder().name("ct2").build()
            ))
            .indexes(List.of(
                CreationResult.builder().name("i1").build(),
                CreationResult.builder().name("i2").build()
            ))
            .aliases(List.of(
                CreationResult.builder().name("a1").build(),
                CreationResult.builder().name("a2").build()
            ))
            .build();

        // Test String Output
        var stringOutput = items.asCliOutput();
        
        // Test JSON Output
        var jsonNode = items.asJsonOutput();
        
        // String output assertions
        assertThat(stringOutput, containsString("Migration Candidates:"));
        assertThat(stringOutput, containsString("Index Templates:"));
        assertThat(stringOutput, stringContainsInOrder("it1", "it2"));
        assertThat(stringOutput, containsString("Component Templates:"));
        assertThat(stringOutput, stringContainsInOrder("ct1", "ct2"));
        assertThat(stringOutput, containsString("Indexes:"));
        assertThat(stringOutput, stringContainsInOrder("i1", "i2"));
        assertThat(stringOutput, containsString("Aliases:"));
        assertThat(stringOutput, stringContainsInOrder("a1", "a2"));
        assertThat(stringOutput, containsStringCount(Items.NONE_FOUND_MARKER, 0));
        assertThat(stringOutput, hasLineCount(16));
        
        // JSON output assertions
        assertTrue(jsonNode.get("dryRun").asBoolean(), "dryRun should be true");
        
        // Check indexTemplates
        JsonNode indexTemplates = jsonNode.get("indexTemplates");
        assertEquals(2, indexTemplates.size());
        assertEquals("it1", indexTemplates.get(0).get("name").asText());
        assertEquals("it2", indexTemplates.get(1).get("name").asText());
        assertTrue(indexTemplates.get(0).get("successful").asBoolean());
        
        // Check componentTemplates
        JsonNode componentTemplates = jsonNode.get("componentTemplates");
        assertEquals(2, componentTemplates.size());
        assertEquals("ct1", componentTemplates.get(0).get("name").asText());
        assertEquals("ct2", componentTemplates.get(1).get("name").asText());
        
        // Check indexes
        JsonNode indexes = jsonNode.get("indexes");
        assertEquals(2, indexes.size());
        assertEquals("i1", indexes.get(0).get("name").asText());
        assertEquals("i2", indexes.get(1).get("name").asText());
        
        // Check aliases
        JsonNode aliases = jsonNode.get("aliases");
        assertEquals(2, aliases.size());
        assertEquals("a1", aliases.get(0).get("name").asText());
        assertEquals("a2", aliases.get(1).get("name").asText());
    }

    @Test
    @DisplayName("Items with Index Template Failures - Both Output Formats")
    void testIndexTemplatesFailures() throws Exception {
        var items = createEmptyItemsBuilder()
            .indexTemplates(List.of(
                CreationResult.builder().name("it1").failureType(CreationFailureType.ALREADY_EXISTS).build(),
                CreationResult.builder().name("it2").failureType(CreationFailureType.TARGET_CLUSTER_FAILURE).exception(new RuntimeException("403 Forbidden")).build()
            ))
            .build();

        // Test String Output
        var stringOutput = items.asCliOutput();
        
        // Test JSON Output
        var jsonNode = items.asJsonOutput();
        
        // String output assertions
        assertThat(stringOutput, containsString("Migrated Items:"));
        assertThat(stringOutput, containsString("ERROR - it2 failed on target cluster: 403 Forbidden"));
        assertThat(stringOutput, containsString("WARN - it1 already exists"));
        assertThat(stringOutput, containsString("Index Templates:"));
        assertThat(stringOutput, containsString("Component Templates:"));
        assertThat(stringOutput, containsString("Indexes:"));
        assertThat(stringOutput, containsString("Aliases:"));
        assertThat(stringOutput, containsStringCount(Items.NONE_FOUND_MARKER, 3));
        assertThat(stringOutput, hasLineCount(13));
        
        // JSON output assertions
        // Check indexTemplates with failures
        JsonNode indexTemplates = jsonNode.get("indexTemplates");
        assertEquals(2, indexTemplates.size());
        
        // Check first item with non-fatal failure
        JsonNode it1 = indexTemplates.get(0);
        assertEquals("it1", it1.get("name").asText());
        assertFalse(it1.get("successful").asBoolean());
        JsonNode failure1 = it1.get("failure");
        assertEquals("ALREADY_EXISTS", failure1.get("type").asText());
        assertFalse(failure1.get("fatal").asBoolean());
        
        // Check second item with fatal failure
        JsonNode it2 = indexTemplates.get(1);
        assertEquals("it2", it2.get("name").asText());
        assertFalse(it2.get("successful").asBoolean());
        JsonNode failure2 = it2.get("failure");
        assertEquals("TARGET_CLUSTER_FAILURE", failure2.get("type").asText());
        assertTrue(failure2.get("fatal").asBoolean());
        assertEquals("403 Forbidden", failure2.get("exception").asText());
    }

    @Test
    @DisplayName("Items with Ordering - Both Output Formats")
    void testItemOrdering() throws Exception {
        var items = createEmptyItemsBuilder()
            .indexes(List.of(
                CreationResult.builder().name("i1").build(),
                CreationResult.builder().name("i2").build(),
                CreationResult.builder().name("i5").build(),
                CreationResult.builder().name("i3").build(),
                CreationResult.builder().name("i4").build()
            ))
            .build();

        // Test String Output
        var stringOutput = items.asCliOutput();
        
        // Test JSON Output
        var jsonNode = items.asJsonOutput();
        
        // String output assertions
        assertThat(stringOutput, containsString("Migrated Items:"));
        assertThat(stringOutput, containsString("Index Templates:"));
        assertThat(stringOutput, stringContainsInOrder("i1", "i2", "i3", "i4","i5"));
        assertThat(stringOutput, containsString("Component Templates:"));
        assertThat(stringOutput, containsString("Indexes:"));
        assertThat(stringOutput, containsString("Aliases:"));
        assertThat(stringOutput, containsStringCount(Items.NONE_FOUND_MARKER, 3));
        assertThat(stringOutput, hasLineCount(16));
        
        // JSON output assertions
        JsonNode indexes = jsonNode.get("indexes");
        assertEquals(5, indexes.size());
        // Verify ordering (should maintain original order in JSON)
        assertEquals("i1", indexes.get(0).get("name").asText());
        assertEquals("i2", indexes.get(1).get("name").asText());
        assertEquals("i5", indexes.get(2).get("name").asText());
        assertEquals("i3", indexes.get(3).get("name").asText());
        assertEquals("i4", indexes.get(4).get("name").asText());
    }

    @Test
    @DisplayName("Items with Failure Types - Both Output Formats")
    void testFailureTypes() throws Exception {
        var items = createEmptyItemsBuilder()
            .indexes(List.of(
                CreationResult.builder().name("i1").build(),
                CreationResult.builder().name("i2").failureType(null).build(),
                CreationResult.builder().name("i3").exception(new RuntimeException("exception-without-failure-type")).build(),
                CreationResult.builder().name("i4").failureType(TARGET_CLUSTER_FAILURE).build(),
                CreationResult.builder().name("i5").failureType(TARGET_CLUSTER_FAILURE).exception(new RuntimeException("re1")).build(),
                CreationResult.builder().name("i6").failureType(TARGET_CLUSTER_FAILURE).exception(new RuntimeException()).build()
            ))
            .build();

        // Test String Output
        var stringOutput = items.asCliOutput();
        
        // Test JSON Output
        var jsonNode = items.asJsonOutput();
        
        // String output assertions
        assertThat(stringOutput, stringContainsInOrder("i1", "i2", "i3"));
        assertThat("Results with no errors do not print exception info", stringOutput, not(containsString("exception-without-failure-type")));
        assertThat(stringOutput, stringContainsInOrder("i4 failed on target cluster", "i5 failed on target cluster: re1"));
        assertThat("Expect an exception's toString() if there was no message in the exception", stringOutput, containsString("i6 failed on target cluster: java.lang.RuntimeException"));
        assertThat(stringOutput, hasLineCount(17));
        
        // JSON output assertions
        JsonNode indexes = jsonNode.get("indexes");
        assertEquals(6, indexes.size());
        
        // Verify successful items
        assertTrue(indexes.get(0).get("successful").asBoolean(), "i1 should be successful");
        
        // Verify failure items
        JsonNode i5 = indexes.get(4);
        assertFalse(i5.get("successful").asBoolean(), "i5 should not be successful");
        JsonNode failure5 = i5.get("failure");
        assertEquals("TARGET_CLUSTER_FAILURE", failure5.get("type").asText());
        assertTrue(failure5.get("fatal").asBoolean());
        assertEquals("re1", failure5.get("exception").asText());
    }

    @Test
    @DisplayName("Items with Overall Failure Message - Both Output Formats")
    void testWithFailures() throws Exception {
        var items = createEmptyItemsBuilder()
            .failureMessage("Overall failure message")
            .indexTemplates(List.of(
                CreationResult.builder().name("it1").failureType(CreationFailureType.ALREADY_EXISTS).build(),
                CreationResult.builder().name("it2").failureType(CreationFailureType.TARGET_CLUSTER_FAILURE).exception(new RuntimeException("403 Forbidden")).build()
            ))
            .build();

        // Test String Output
        var stringOutput = items.asCliOutput();
        
        // Test JSON Output  
        var jsonNode = items.asJsonOutput();
        
        // String output assertions
        assertThat(stringOutput, containsString("Migrated Items:"));
        // Note: The failure message is not actually displayed in the CLI output, only in the JSON
        
        // JSON output assertions
        // Check failure message
        assertEquals("Overall failure message", jsonNode.get("failureMessage").asText());
        
        // Check errors array
        JsonNode errors = jsonNode.get("errors");
        assertTrue(errors.isArray());
        // Errors array contains both the failureMessage and the fatal error from it2
        assertEquals(2, errors.size());  
        assertEquals("Overall failure message", errors.get(0).asText());
    }

    private ItemsBuilder createEmptyItemsBuilder() {
        return Items.builder()
            .dryRun(false)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of())
            .aliases(List.of());
    }
}
