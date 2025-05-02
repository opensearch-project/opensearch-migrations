package org.opensearch.migrations.cli;

import java.util.List;

import org.opensearch.migrations.cli.Items.ItemsBuilder;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.opensearch.migrations.matchers.ContainsStringCount.containsStringCount;
import static org.opensearch.migrations.matchers.HasLineCount.hasLineCount;
import static org.opensearch.migrations.metadata.CreationResult.CreationFailureType.TARGET_CLUSTER_FAILURE;

public class ItemsTest {
    @Test
    void testAsString_empty() {
        var items = createEmptyItemsBuilder()
            .build();

        var result = items.asCliOutput();

        assertThat(result, containsString("Migrated Items:"));
        assertThat(result, containsString("Index Templates:"));
        assertThat(result, containsString("Component Templates:"));
        assertThat(result, containsString("Indexes:"));
        assertThat(result, containsString("Aliases:"));
        assertThat(result, containsStringCount(Items.NONE_FOUND_MARKER, 4));
        assertThat(result, hasLineCount(12));
    }

    @Test
    void testAsString_full() {
        var items = Items.builder()
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

        var result = items.asCliOutput();

        assertThat(result, containsString("Migrated Items:"));
        assertThat(result, containsString("Index Templates:"));
        assertThat(result, containsString("it1, it2"));
        assertThat(result, containsString("Component Templates:"));
        assertThat(result, containsString("ct1, ct2"));
        assertThat(result, containsString("Indexes:"));
        assertThat(result, containsString("i1, i2"));
        assertThat(result, containsString("Aliases:"));
        assertThat(result, containsString("a1, a2"));
        assertThat(result, containsStringCount(Items.NONE_FOUND_MARKER, 0));
        assertThat(result, hasLineCount(12));
    }

    @Test
    void testAsString_indexTemplates_failures() {
        var items = createEmptyItemsBuilder()
            .indexTemplates(List.of(
                CreationResult.builder().name("it1").failureType(CreationFailureType.ALREADY_EXISTS).build(),
                CreationResult.builder().name("it2").failureType(CreationFailureType.TARGET_CLUSTER_FAILURE).exception(new RuntimeException("403 Forbidden")).build()
            ))
            .build();

        var result = items.asCliOutput();

        assertThat(result, containsString("Migrated Items:"));
        assertThat(result, containsString("ERROR - it2 failed on target cluster: 403 Forbidden"));
        assertThat(result, containsString("WARN - it1 already exists"));
        assertThat(result, containsString("Index Templates:"));
        assertThat(result, containsString("Component Templates:"));
        assertThat(result, containsString("Indexes:"));
        assertThat(result, containsString("Aliases:"));
        assertThat(result, containsStringCount(Items.NONE_FOUND_MARKER, 3));
        assertThat(result, hasLineCount(13));
    }

    @Test
    void testAsString_itemOrdering() {
        var items = createEmptyItemsBuilder()
            .indexes(List.of(
                CreationResult.builder().name("i1").build(),
                CreationResult.builder().name("i2").build(),
                CreationResult.builder().name("i5").build(),
                CreationResult.builder().name("i3").build(),
                CreationResult.builder().name("i4").build()
            ))
            .build();

        var result = items.asCliOutput();

        assertThat(result, containsString("Migrated Items:"));
        assertThat(result, containsString("Index Templates:"));
        assertThat(result, containsString("i1, i2, i3, i4, i5"));
        assertThat(result, containsString("Component Templates:"));
        assertThat(result, containsString("Indexes:"));
        assertThat(result, containsString("Aliases:"));
        assertThat(result, containsStringCount(Items.NONE_FOUND_MARKER, 3));
        assertThat(result, hasLineCount(12));
    }

    @Test
    void testFailureTypes() {
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

        var result = items.asCliOutput();
        assertThat(result, containsString("i1"));
        assertThat(result, containsString("i2"));
        assertThat(result, containsString("i3"));
        assertThat("Results with no errors do not print exception info", result, not(containsString("exception-without-failure-type")));
        assertThat(result, containsString("i4 failed on target cluster"));
        assertThat(result, containsString("i5 failed on target cluster: re1"));        
        assertThat("Expect an exception's toString() if there was no message in the exception", result, containsString("i6 failed on target cluster: java.lang.RuntimeException"));
        assertThat(result, hasLineCount(15));
    }

    private ItemsBuilder createEmptyItemsBuilder() {
        return Items.builder()
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of())
            .aliases(List.of());
    }
}
