package org.opensearch.migrations.bulkload.common;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class FilterSchemeTest {
    @Test
    void testFilterByAllowList_null() {
        var filter = FilterScheme.filterByAllowList(null);

        assertThat(filter.test("test"), equalTo(true));
        assertThat(filter.test("test1"), equalTo(true));
        assertThat(filter.test(".test"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_empty() {
        var filter = FilterScheme.filterByAllowList(List.of());

        assertThat(filter.test("test"), equalTo(true));
        assertThat(filter.test("test1"), equalTo(true));
        assertThat(filter.test(".test"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_matches() {
        var filter = FilterScheme.filterByAllowList(List.of("test"));

        assertThat(filter.test("test"), equalTo(true));
        assertThat(filter.test("test1"), equalTo(false));
        assertThat(filter.test(".test"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_matches_with_period() {
        var filter = FilterScheme.filterByAllowList(List.of(".test"));

        assertThat(filter.test("test"), equalTo(false));
        assertThat(filter.test("test1"), equalTo(false));
        assertThat(filter.test(".test"), equalTo(true));
    }
}
