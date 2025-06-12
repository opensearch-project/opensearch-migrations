package org.opensearch.migrations.bulkload.common;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class FilterSchemeTest {
    @Test
    void testExcludedByPrefix() {
        var filter = FilterScheme.filterByAllowList(null);
        
        // Should be excluded due to prefix
        assertThat(filter.test("apm-test"), equalTo(false));
        assertThat(filter.test(".hidden"), equalTo(false));
        
        // Should not be excluded (no matching prefix)
        assertThat(filter.test("myindex"), equalTo(true));
        assertThat(filter.test("logs-2024"), equalTo(true));
        assertThat(filter.test("metrics-2024"), equalTo(true));
    }
    
    @Test
    void testExcludedBySuffix() {
        var filter = FilterScheme.filterByAllowList(null);
        
        // Should be excluded due to suffix
        assertThat(filter.test("index@settings"), equalTo(false));
        assertThat(filter.test("test@mappings"), equalTo(false));
        
        // Should not be excluded (no matching suffix)
        assertThat(filter.test("settings"), equalTo(true));
    }
    
    @Test
    void testExcludedByExactName() {
        var filter = FilterScheme.filterByAllowList(null);
        
        // Should be excluded due to exact name match
        assertThat(filter.test("logs"), equalTo(false));
        assertThat(filter.test("metrics"), equalTo(false));
        
        // Should not be excluded (no exact name match)
        assertThat(filter.test("metrics_custom"), equalTo(true));
    }
    
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
