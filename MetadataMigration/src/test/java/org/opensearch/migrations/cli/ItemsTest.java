package org.opensearch.migrations.cli;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.cli.Items.ItemsBuilder;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
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
        assertThat(stringOutput, containsString("ERROR - it1 already exists"));
        assertThat(stringOutput, containsString("Index Templates:"));
        assertThat(stringOutput, containsString("Component Templates:"));
        assertThat(stringOutput, containsString("Indexes:"));
        assertThat(stringOutput, containsString("Aliases:"));
        assertThat(stringOutput, containsStringCount(Items.NONE_FOUND_MARKER, 3));
        assertThat(stringOutput, hasLineCount(23));
        assertThat(stringOutput, containsString("Already Existing Items (1 item(s) already exist on the target cluster):"));
        assertThat(stringOutput, containsString("- it1"));
        assertThat(stringOutput, containsString("(a) Delete the conflicting items from the target cluster and re-run from scratch."));
        assertThat(stringOutput, containsString("(b) Use --index-allowlist on the metadata step to migrate only the missing items."));
        
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
        assertThat(cliOutput, containsString("ERROR - my-template already exists"));
        assertThat(cliOutput, containsString("ERROR - my-component already exists"));
        assertThat(cliOutput, containsString("ERROR - my-index already exists"));
        assertThat(cliOutput, containsString("ERROR - my-alias already exists"));
        assertThat(cliOutput, containsString("Already Existing Items (4 item(s) already exist on the target cluster):"));
        assertThat(cliOutput, containsString("- my-template"));
        assertThat(cliOutput, containsString("- my-component"));
        assertThat(cliOutput, containsString("- my-index"));
        assertThat(cliOutput, containsString("- my-alias"));
        assertThat(cliOutput, containsString("(a) Delete the conflicting items from the target cluster and re-run from scratch."));
        assertThat(cliOutput, containsString("(b) Use --index-allowlist on the metadata step to migrate only the missing items."));

        // JSON output assertions
        var json = items.asJsonOutput();
        assertThat(json.toPrettyString(), json.has("alreadyExistsCount"), is(true));
        assertThat(json.toPrettyString(), json.get("alreadyExistsCount").asInt(), equalTo(4));

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

    @Property(tries = 100)
    void propertyFullRunCompletionWithMixedResults(
            @ForAll @Size(min = 0, max = 5) List<@From("creationResults") CreationResult> indexTemplates,
            @ForAll @Size(min = 0, max = 5) List<@From("creationResults") CreationResult> componentTemplates,
            @ForAll @Size(min = 0, max = 5) List<@From("creationResults") CreationResult> indexes,
            @ForAll @Size(min = 0, max = 5) List<@From("creationResults") CreationResult> aliases) {

        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(indexTemplates)
            .componentTemplates(componentTemplates)
            .indexes(indexes)
            .aliases(aliases)
            .build();

        // Assert every submitted item appears in the result — no items silently dropped
        assertThat(items.getIndexTemplates().size(), equalTo(indexTemplates.size()));
        assertThat(items.getComponentTemplates().size(), equalTo(componentTemplates.size()));
        assertThat(items.getIndexes().size(), equalTo(indexes.size()));
        assertThat(items.getAliases().size(), equalTo(aliases.size()));

        // Assert getAlreadyExistsCount() matches actual ALREADY_EXISTS count across all four types
        long expectedCount = Stream.of(indexTemplates, componentTemplates, indexes, aliases)
            .flatMap(Collection::stream)
            .filter(r -> r.getFailureType() == CreationResult.CreationFailureType.ALREADY_EXISTS)
            .count();
        assertThat(items.getAlreadyExistsCount(), equalTo((int) expectedCount));
    }

    @Property(tries = 100)
    void propertyCliOutputContainsAlreadyExistsNamesAndRemediation(
            @ForAll @Size(min = 0, max = 4) List<@From("creationResults") CreationResult> indexTemplates,
            @ForAll @Size(min = 0, max = 4) List<@From("creationResults") CreationResult> componentTemplates,
            @ForAll @Size(min = 0, max = 4) List<@From("creationResults") CreationResult> indexes,
            @ForAll @Size(min = 0, max = 4) List<@From("creationResults") CreationResult> aliases,
            @ForAll @From("alreadyExistsResult") CreationResult extraAlreadyExists) {

        // Inject at least one ALREADY_EXISTS item into indexes to guarantee ≥1 ALREADY_EXISTS
        var indexesWithAtLeastOne = new java.util.ArrayList<>(indexes);
        indexesWithAtLeastOne.add(extraAlreadyExists);

        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(indexTemplates)
            .componentTemplates(componentTemplates)
            .indexes(indexesWithAtLeastOne)
            .aliases(aliases)
            .build();

        var output = items.asCliOutput();

        // Assert every ALREADY_EXISTS item name appears in the output
        Stream.of(indexTemplates, componentTemplates, indexesWithAtLeastOne, aliases)
            .flatMap(Collection::stream)
            .filter(r -> r.getFailureType() == CreationResult.CreationFailureType.ALREADY_EXISTS)
            .map(CreationResult::getName)
            .forEach(name -> assertThat(output, containsString(name)));

        // Assert remediation guidance is present — option (a) and option (b)
        assertThat(output, containsString("(a) Delete the conflicting items from the target cluster and re-run from scratch."));
        assertThat(output, containsString("(b) Use --index-allowlist on the metadata step to migrate only the missing items."));
    }

    @Property(tries = 100)
    void cliOutput_alreadyExistsItemsRenderAsErrorsNotWarnings(
            @ForAll @From("alreadyExistsResult") CreationResult alreadyExistsItem,
            @ForAll boolean putInIndexTemplates,
            @ForAll boolean putInComponentTemplates,
            @ForAll boolean putInIndexes,
            @ForAll boolean putInAliases) {

        // Place the ALREADY_EXISTS item in at least one list; default to indexes if none selected
        boolean useIndexes = putInIndexes || (!putInIndexTemplates && !putInComponentTemplates && !putInAliases);

        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(putInIndexTemplates ? List.of(alreadyExistsItem) : List.of())
            .componentTemplates(putInComponentTemplates ? List.of(alreadyExistsItem) : List.of())
            .indexes(useIndexes ? List.of(alreadyExistsItem) : List.of())
            .aliases(putInAliases ? List.of(alreadyExistsItem) : List.of())
            .build();

        var output = items.asCliOutput();

        // The ALREADY_EXISTS item must appear as ERROR, not WARN (action required)
        assertThat(output, containsString("ERROR - " + alreadyExistsItem.getName() + " already exists"));
        assertThat(output, not(containsString("WARN - " + alreadyExistsItem.getName())));
    }

    // Property 5: JSON output alreadyExistsCount field presence and correctness
    @Property(tries = 100)
    void propertyJsonAlreadyExistsCountFieldPresenceAndCorrectness(
            @ForAll @Size(min = 0, max = 4) List<@From("creationResults") CreationResult> indexTemplates,
            @ForAll @Size(min = 0, max = 4) List<@From("creationResults") CreationResult> componentTemplates,
            @ForAll @Size(min = 0, max = 4) List<@From("creationResults") CreationResult> indexes,
            @ForAll @Size(min = 0, max = 4) List<@From("creationResults") CreationResult> aliases) {

        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(indexTemplates)
            .componentTemplates(componentTemplates)
            .indexes(indexes)
            .aliases(aliases)
            .build();

        var json = items.asJsonOutput();
        int expectedCount = items.getAlreadyExistsCount();

        if (expectedCount > 0) {
            // Field must be present and equal to the count
            assertThat(json.toPrettyString(), json.has("alreadyExistsCount"), is(true));
            assertThat(json.toPrettyString(), json.get("alreadyExistsCount").asInt(), equalTo(expectedCount));
        } else {
            // Field must be absent when count is zero
            assertThat(json.toPrettyString(), json.has("alreadyExistsCount"), is(false));
        }

        // For each item type, verify per-item failure structure for ALREADY_EXISTS items
        for (var entry : List.of(
                new Object[]{"indexTemplates", indexTemplates},
                new Object[]{"componentTemplates", componentTemplates},
                new Object[]{"indexes", indexes},
                new Object[]{"aliases", aliases})) {
            String fieldName = (String) entry[0];
            @SuppressWarnings("unchecked")
            List<CreationResult> list = (List<CreationResult>) entry[1];
            var jsonArray = json.get(fieldName);
            for (int i = 0; i < list.size(); i++) {
                var result = list.get(i);
                var jsonItem = jsonArray.get(i);
                if (result.getFailureType() == CreationResult.CreationFailureType.ALREADY_EXISTS) {
                    assertThat(json.toPrettyString(), jsonItem.get("successful").asBoolean(), is(false));
                    var failure = jsonItem.get("failure");
                    assertThat(json.toPrettyString(), failure, is(notNullValue()));
                    assertThat(json.toPrettyString(), failure.get("type").asText(), equalTo("ALREADY_EXISTS"));
                    assertThat(json.toPrettyString(), failure.get("fatal").asBoolean(), is(false));
                }
            }
        }
    }

    @Provide
    Arbitrary<CreationResult> alreadyExistsResult() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .ofMinLength(1)
            .ofMaxLength(20)
            .map(name -> CreationResult.builder()
                .name("ae-" + name)
                .failureType(CreationResult.CreationFailureType.ALREADY_EXISTS)
                .build());
    }

    @Provide
    Arbitrary<CreationResult> creationResults() {
        return Arbitraries.of(
            CreationResult.builder().name("item-success").build(),
            CreationResult.builder().name("item-already-exists").failureType(CreationResult.CreationFailureType.ALREADY_EXISTS).build(),
            CreationResult.builder().name("item-fatal").failureType(CreationResult.CreationFailureType.TARGET_CLUSTER_FAILURE).build()
        );
    }
}
