package org.opensearch.migrations.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.opensearch.migrations.matchers.ContainsStringCount.containsStringCount;
import static org.opensearch.migrations.matchers.HasLineCount.hasLineCount;

public class ItemsTest {
    @Test
    void testAsString_empty() {
        var items = Items.builder()
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of())
            .aliases(List.of())
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
            .indexTemplates(List.of("it1", "it2"))
            .componentTemplates(List.of("ct1", "ct2"))
            .indexes(List.of("i1", "i2"))
            .aliases(List.of("a1", "a2"))
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
    void testAsString_itemOrdering() {
        var items = Items.builder()
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of("i1", "i2", "i5", "i3", "i4"))
            .aliases(List.of())
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
}
