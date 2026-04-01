package org.opensearch.migrations.cli;

import java.util.List;

import org.opensearch.migrations.cli.Items.ItemsBuilder;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.text.StringContainsInOrder.stringContainsInOrder;
import static org.opensearch.migrations.matchers.ContainsStringCount.containsStringCount;
import static org.opensearch.migrations.matchers.HasLineCount.hasLineCount;
import static org.opensearch.migrations.metadata.CreationResult.CreationFailureType.TARGET_CLUSTER_FAILURE;

public class ItemsTest {
    /**
     * Tests both string and JSON output formats for each test case to make comparisons clearer
     * and assertions more uniform across formats.
     */

    @Test
    void testEmpty() throws Exception {
        var items = createEmptyItemsBuilder()
            .build();

        // Test String Output
        var stringOutput = items.asCliOutput();
        
        // Test JSON Output
        var jsonOutput = items.asJsonOutput();
        
        // String output assertions
        assertThat(stringOutput, containsString("Migrated Items:"));
        assertThat(stringOutput, containsString("Index Templates:"));
        assertThat(stringOutput, containsString("Component Templates:"));
        assertThat(stringOutput, containsString("Indexes:"));
        assertThat(stringOutput, containsString("Aliases:"));
        assertThat(stringOutput, containsStringCount(Items.NONE_FOUND_MARKER, 4));
        assertThat(stringOutput, hasLineCount(12));
        
        // JSON output assertions
        assertThat(jsonOutput.toPrettyString(), jsonOutput, is(notNullValue()));
        assertThat(jsonOutput.toPrettyString(), jsonOutput.has("dryRun"), is(true));
        assertThat(jsonOutput.toPrettyString(), jsonOutput.get("dryRun").asBoolean(), is(false));
        assertThat(jsonOutput.toPrettyString(), jsonOutput.has("indexTemplates"), is(true));
        assertThat(jsonOutput.toPrettyString(), jsonOutput.has("componentTemplates"), is(true));
        assertThat(jsonOutput.toPrettyString(), jsonOutput.has("indexes"), is(true));
        assertThat(jsonOutput.toPrettyString(), jsonOutput.has("aliases"), is(true));
        assertThat(jsonOutput.toPrettyString(), jsonOutput.has("errors"), is(true));
        assertThat(jsonOutput.toPrettyString(), jsonOutput.get("errors").size(), equalTo(0));
    }

    @Test
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
        var jsonOutput = items.asJsonOutput();
        assertThat(jsonOutput.toPrettyString(), jsonOutput.get("dryRun").asBoolean(), is(true));
        var indexTemplates = jsonOutput.get("indexTemplates");
        assertThat(jsonOutput.toPrettyString(), indexTemplates.size(), equalTo(2));
        assertThat(jsonOutput.toPrettyString(), indexTemplates.get(0).get("name").asText(), equalTo("it1"));
        assertThat(jsonOutput.toPrettyString(), indexTemplates.get(1).get("name").asText(), equalTo("it2"));
        assertThat(jsonOutput.toPrettyString(), indexTemplates.get(0).get("successful").asBoolean(), is(true));
        var componentTemplates = jsonOutput.get("componentTemplates");
        assertThat(jsonOutput.toPrettyString(), componentTemplates.size(), equalTo(2));
        assertThat(jsonOutput.toPrettyString(), componentTemplates.get(0).get("name").asText(), equalTo("ct1"));
        assertThat(jsonOutput.toPrettyString(), componentTemplates.get(1).get("name").asText(), equalTo("ct2"));
        var indexes = jsonOutput.get("indexes");
        assertThat(jsonOutput.toPrettyString(), indexes.size(), equalTo(2));
        assertThat(jsonOutput.toPrettyString(), indexes.get(0).get("name").asText(), equalTo("i1"));
        assertThat(jsonOutput.toPrettyString(), indexes.get(1).get("name").asText(), equalTo("i2"));
        var aliases = jsonOutput.get("aliases");
        assertThat(jsonOutput.toPrettyString(), aliases.size(), equalTo(2));
        assertThat(jsonOutput.toPrettyString(), aliases.get(0).get("name").asText(), equalTo("a1"));
        assertThat(jsonOutput.toPrettyString(), aliases.get(1).get("name").asText(), equalTo("a2"));

    }

    @Test
    void testIndexTemplatesFailures() throws Exception {
        var items = createEmptyItemsBuilder()
            .indexTemplates(List.of(
                CreationResult.builder().name("it1").failureType(CreationFailureType.ALREADY_EXISTS).build(),
                CreationResult.builder().name("it2").failureType(CreationFailureType.TARGET_CLUSTER_FAILURE).exception(new RuntimeException("403 Forbidden")).build()
            ))
            .build();

        // Test String Output
        var stringOutput = items.asCliOutput();
        
        assertThat(stringOutput, containsString("Migrated Items:"));
        assertThat(stringOutput, containsString("ERROR - it2 failed on target cluster: 403 Forbidden"));
        assertThat(stringOutput, containsString("WARN - it1 already exists"));
        assertThat(stringOutput, containsString("Index Templates:"));
        assertThat(stringOutput, containsString("Component Templates:"));
        assertThat(stringOutput, containsString("Indexes:"));
        assertThat(stringOutput, containsString("Aliases:"));
        assertThat(stringOutput, containsStringCount(Items.NONE_FOUND_MARKER, 3));
        assertThat(stringOutput, hasLineCount(13));
        
        // Test JSON Output
        var jsonOutput = items.asJsonOutput();
        var indexTemplates = jsonOutput.get("indexTemplates");
        assertThat(jsonOutput.toPrettyString(), indexTemplates.size(), equalTo(2));
        var it1 = indexTemplates.get(0);
        assertThat(jsonOutput.toPrettyString(), it1.get("name").asText(), equalTo("it1"));
        assertThat(jsonOutput.toPrettyString(), it1.get("successful").asBoolean(), is(false));
        var failure1 = it1.get("failure");
        assertThat(jsonOutput.toPrettyString(), failure1.get("type").asText(), equalTo("ALREADY_EXISTS"));
        assertThat(jsonOutput.toPrettyString(), failure1.get("fatal").asBoolean(), is(false));
        var it2 = indexTemplates.get(1);
        assertThat(jsonOutput.toPrettyString(), it2.get("name").asText(), equalTo("it2"));
        assertThat(jsonOutput.toPrettyString(), it2.get("successful").asBoolean(), is(false));
        var failure2 = it2.get("failure");
        assertThat(jsonOutput.toPrettyString(), failure2.get("type").asText(), equalTo("TARGET_CLUSTER_FAILURE"));
        assertThat(jsonOutput.toPrettyString(), failure2.get("fatal").asBoolean(), is(true));
        assertThat(jsonOutput.toPrettyString(), failure2.get("exception").asText(), equalTo("403 Forbidden"));

    }

    @Test
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
        assertThat(stringOutput, containsString("Migrated Items:"));
        assertThat(stringOutput, containsString("Index Templates:"));
        assertThat(stringOutput, stringContainsInOrder("i1", "i2", "i3", "i4","i5"));
        assertThat(stringOutput, containsString("Component Templates:"));
        assertThat(stringOutput, containsString("Indexes:"));
        assertThat(stringOutput, containsString("Aliases:"));
        assertThat(stringOutput, containsStringCount(Items.NONE_FOUND_MARKER, 3));
        assertThat(stringOutput, hasLineCount(16));
        
        // Test JSON Output
        var jsonOutput = items.asJsonOutput();
        var indexes = jsonOutput.get("indexes");
        assertThat(jsonOutput.toPrettyString(), indexes.size(), equalTo(5));
        assertThat(jsonOutput.toPrettyString(), indexes.get(0).get("name").asText(), equalTo("i1"));
        assertThat(jsonOutput.toPrettyString(), indexes.get(1).get("name").asText(), equalTo("i2"));
        assertThat(jsonOutput.toPrettyString(), indexes.get(2).get("name").asText(), equalTo("i5"));
        assertThat(jsonOutput.toPrettyString(), indexes.get(3).get("name").asText(), equalTo("i3"));
        assertThat(jsonOutput.toPrettyString(), indexes.get(4).get("name").asText(), equalTo("i4"));
    }

    @Test
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
        assertThat(stringOutput, stringContainsInOrder("i1", "i2", "i3"));
        assertThat("Results with no errors do not print exception info", stringOutput, not(containsString("exception-without-failure-type")));
        assertThat(stringOutput, stringContainsInOrder("i4 failed on target cluster", "i5 failed on target cluster: re1"));
        assertThat("Expect an exception's toString() if there was no message in the exception", stringOutput, containsString("i6 failed on target cluster: java.lang.RuntimeException"));
        assertThat(stringOutput, hasLineCount(17));
        
        // Test JSON Output
        var jsonOutput = items.asJsonOutput();
        var indexes = jsonOutput.get("indexes");
        assertThat(jsonOutput.toPrettyString(), indexes.size(), equalTo(6));
        assertThat(jsonOutput.toPrettyString(), indexes.get(0).get("successful").asBoolean(), is(true));
        var i5 = indexes.get(4);
        assertThat(jsonOutput.toPrettyString(), i5.get("successful").asBoolean(), is(false));
        var failure5 = i5.get("failure");
        assertThat(jsonOutput.toPrettyString(), failure5.get("type").asText(), equalTo("TARGET_CLUSTER_FAILURE"));
        assertThat(jsonOutput.toPrettyString(), failure5.get("fatal").asBoolean(), is(true));
        assertThat(jsonOutput.toPrettyString(), failure5.get("exception").asText(), equalTo("re1"));

    }

    @Test
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
        assertThat(stringOutput, containsString("Migrated Items:"));
        // Note: The failure message is not actually displayed in the CLI output, only in the JSON
        
        // Test JSON Output  
        var jsonNode = items.asJsonOutput();
        assertThat(jsonNode.toPrettyString(), jsonNode.get("failureMessage").asText(), equalTo("Overall failure message"));
        var errors = jsonNode.get("errors");
        assertThat(jsonNode.toPrettyString(), errors.isArray(), is(true));
        assertThat(jsonNode.toPrettyString(), errors.size(), equalTo(2));
        assertThat(jsonNode.toPrettyString(), errors.get(0).asText(), equalTo("Overall failure message"));

    }

    @Test
    void alreadyExistsItems_appearInBothCliAndJsonOutput() throws Exception {
        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(List.of(
                CreationResult.builder().name("my-template").failureType(CreationFailureType.ALREADY_EXISTS).build()
            ))
            .componentTemplates(List.of(
                CreationResult.builder().name("my-component").failureType(CreationFailureType.ALREADY_EXISTS).build()
            ))
            .indexes(List.of(
                CreationResult.builder().name("my-index").failureType(CreationFailureType.ALREADY_EXISTS).build()
            ))
            .aliases(List.of(
                CreationResult.builder().name("my-alias").failureType(CreationFailureType.ALREADY_EXISTS).build()
            ))
            .build();

        // CLI output assertions
        var cliOutput = items.asCliOutput();
        assertThat(cliOutput, containsString("WARN - my-template already exists"));
        assertThat(cliOutput, containsString("WARN - my-component already exists"));
        assertThat(cliOutput, containsString("ERROR - my-index already exists"));
        assertThat(cliOutput, containsString("WARN - my-alias already exists"));
        assertThat(cliOutput, containsString("- my-template"));
        assertThat(cliOutput, containsString("- my-component"));
        assertThat(cliOutput, containsString("- my-index"));
        assertThat(cliOutput, containsString("- my-alias"));

        // JSON output assertions
        var json = items.asJsonOutput();
        assertThat(json.toPrettyString(), json.has("alreadyExistsCount"), is(true));
        // Only indexes count toward alreadyExistsCount
        assertThat(json.toPrettyString(), json.get("alreadyExistsCount").asInt(), equalTo(1));

        for (var entry : List.of(
                new String[]{"indexTemplates", "my-template"},
                new String[]{"componentTemplates", "my-component"},
                new String[]{"indexes", "my-index"},
                new String[]{"aliases", "my-alias"})) {
            var fieldName = entry[0];
            var expectedName = entry[1];
            var array = json.get(fieldName);
            assertThat(json.toPrettyString(), array.size(), equalTo(1));
            var item = array.get(0);
            assertThat(json.toPrettyString(), item.get("name").asText(), equalTo(expectedName));
            assertThat(json.toPrettyString(), item.get("successful").asBoolean(), is(false));
            var failure = item.get("failure");
            assertThat(json.toPrettyString(), failure.get("type").asText(), equalTo("ALREADY_EXISTS"));
            assertThat(json.toPrettyString(), failure.get("fatal").asBoolean(), is(false));
        }
    }

    private ItemsBuilder createEmptyItemsBuilder() {
        return Items.builder()
            .dryRun(false)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of())
            .aliases(List.of());
    }


    // Feature: metadata-already-exists-detection, Property 1: Full run completion with mixed item results
    @Test
    void testFullRunCompletionWithMixedResults() {
        var successResult = CreationResult.builder().name("item-success").build();
        var alreadyExistsResult = CreationResult.builder().name("item-already-exists")
            .failureType(CreationResult.CreationFailureType.ALREADY_EXISTS).build();
        var fatalResult = CreationResult.builder().name("item-fatal")
            .failureType(CreationResult.CreationFailureType.TARGET_CLUSTER_FAILURE).build();

        var indexTemplates = List.of(successResult, alreadyExistsResult);
        var componentTemplates = List.of(fatalResult);
        var indexes = List.of(alreadyExistsResult, successResult);
        var aliases = List.of(successResult);

        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(indexTemplates)
            .componentTemplates(componentTemplates)
            .indexes(indexes)
            .aliases(aliases)
            .build();

        assertThat(items.getIndexTemplates().size(), equalTo(2));
        assertThat(items.getComponentTemplates().size(), equalTo(1));
        assertThat(items.getIndexes().size(), equalTo(2));
        assertThat(items.getAliases().size(), equalTo(1));
        // Only indexes count — the ALREADY_EXISTS in indexTemplates stays WARN
        assertThat(items.getAlreadyExistsCount(), equalTo(1));
    }

    @Test
    void testAlreadyExistsCountAcrossAllItemTypes() {
        var ae = CreationResult.builder().name("ae").failureType(CreationResult.CreationFailureType.ALREADY_EXISTS).build();
        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(List.of(ae))
            .componentTemplates(List.of(ae))
            .indexes(List.of(ae))
            .aliases(List.of(ae))
            .build();
        // Only indexes count toward alreadyExistsCount — templates/aliases stay WARN
        assertThat(items.getAlreadyExistsCount(), equalTo(1));
    }

    @Test
    void testAlreadyExistsCountZeroWhenNone() {
        var items = createEmptyItemsBuilder()
            .indexes(List.of(CreationResult.builder().name("i1").build()))
            .build();
        assertThat(items.getAlreadyExistsCount(), equalTo(0));
    }

    // Feature: metadata-already-exists-detection, Property 3: CLI output contains all ALREADY_EXISTS item names and remediation guidance
    @Test
    void testCliOutputContainsAlreadyExistsNamesAndRemediation() {
        var items = createEmptyItemsBuilder()
            .indexes(List.of(
                CreationResult.builder().name("ae-index-1").failureType(CreationResult.CreationFailureType.ALREADY_EXISTS).build(),
                CreationResult.builder().name("ae-index-2").failureType(CreationResult.CreationFailureType.ALREADY_EXISTS).build()
            ))
            .indexTemplates(List.of(
                CreationResult.builder().name("ae-template-1").failureType(CreationResult.CreationFailureType.ALREADY_EXISTS).build()
            ))
            .build();

        var output = items.asCliOutput();

        assertThat(output, containsString("ae-index-1"));
        assertThat(output, containsString("ae-index-2"));
        assertThat(output, containsString("ae-template-1"));
    }

    // Feature: metadata-already-exists-detection, Property 4: ALREADY_EXISTS items render as warnings, not errors
    @Test
    void testAlreadyExistsItemsRenderAsWarningsNotErrors() {
        var alreadyExistsItem = CreationResult.builder()
            .name("ae-item")
            .failureType(CreationResult.CreationFailureType.ALREADY_EXISTS)
            .build();

        var items = createEmptyItemsBuilder()
            .indexes(List.of(alreadyExistsItem))
            .build();

        var output = items.asCliOutput();

        // Without allowExistingIndices, ALREADY_EXISTS renders as ERROR
        assertThat(output, containsString("ERROR - ae-item already exists"));
        assertThat(output, not(containsString("WARN - ae-item")));
    }



    // Feature: metadata-already-exists-detection, Property 5: JSON alreadyExistsCount field presence and correctness
    @Test
    void testJsonAlreadyExistsCountPresentWhenNonZero() {
        var ae = CreationResult.builder().name("ae").failureType(CreationResult.CreationFailureType.ALREADY_EXISTS).build();
        var items = createEmptyItemsBuilder().indexes(List.of(ae)).build();
        var json = items.asJsonOutput();

        assertThat(json.toPrettyString(), json.has("alreadyExistsCount"), is(true));
        assertThat(json.toPrettyString(), json.get("alreadyExistsCount").asInt(), equalTo(1));
        var failure = json.get("indexes").get(0).get("failure");
        assertThat(json.toPrettyString(), failure.get("type").asText(), equalTo("ALREADY_EXISTS"));
        assertThat(json.toPrettyString(), failure.get("fatal").asBoolean(), is(false));
    }

    @Test
    void testJsonAlreadyExistsCountAbsentWhenZero() {
        var items = createEmptyItemsBuilder()
            .indexes(List.of(CreationResult.builder().name("i1").build()))
            .build();
        var json = items.asJsonOutput();
        assertThat(json.toPrettyString(), json.has("alreadyExistsCount"), is(false));
    }
}
