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

    @Test
    void testExcludedXPackWatchIndices() {
        var filter = FilterScheme.filterByAllowList(null);

        // These should all be excluded as xpack creates them by default
        assertThat(filter.test("triggered_watches"), equalTo(false));
        assertThat(filter.test("watches"), equalTo(false));
        assertThat(filter.test("watch_history_3"), equalTo(false));

        // Similar index names to not exclude
        assertThat(filter.test("watch_custom"), equalTo(true));
    }

    @Test
    void testExcludedES717SystemIndices() {
        var filter = FilterScheme.filterByAllowList(null);

        // Endpoint logs should be excluded
        assertThat(filter.test("logs-endpoint.alerts"), equalTo(false));
        assertThat(filter.test("logs-endpoint.events.file"), equalTo(false));
        
        // System logs should be excluded
        assertThat(filter.test("logs-system.application"), equalTo(false));
        assertThat(filter.test("logs-system.auth"), equalTo(false));
        
        // System metrics should be excluded
        assertThat(filter.test("metrics-system.cpu"), equalTo(false));
        assertThat(filter.test("metrics-system.memory"), equalTo(false));
        
        // Metadata metrics should be excluded
        assertThat(filter.test("metrics-metadata-current"), equalTo(false));
        assertThat(filter.test("metrics-metadata-united"), equalTo(false));
        
        // Custom indices with similar names should not be excluded
        assertThat(filter.test("logs-myendpoint-custom"), equalTo(true));
        assertThat(filter.test("logs-mysystem-custom"), equalTo(true));
        assertThat(filter.test("metrics-mysystem-custom"), equalTo(true));
        assertThat(filter.test("metrics-mymetadata-custom"), equalTo(true));
    }

    @Test
    void testFilterByAllowList_regex_simple() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs-.*"));

        assertThat(filter.test("logs-app"), equalTo(true));
        assertThat(filter.test("logs-web"), equalTo(true));
        assertThat(filter.test("logs-2024"), equalTo(true));
        assertThat(filter.test("metrics-app"), equalTo(false));
        assertThat(filter.test("logs"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_withDigits() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:test-\\d+"));

        assertThat(filter.test("test-1"), equalTo(true));
        assertThat(filter.test("test-123"), equalTo(true));
        assertThat(filter.test("test-0"), equalTo(true));
        assertThat(filter.test("test-"), equalTo(false));
        assertThat(filter.test("test-abc"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_complex() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs-[a-z]+-\\d{4}"));

        assertThat(filter.test("logs-app-2024"), equalTo(true));
        assertThat(filter.test("logs-web-2024"), equalTo(true));
        assertThat(filter.test("logs-app-24"), equalTo(false));
        assertThat(filter.test("logs-APP-2024"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_multiplePatterns() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs-.*", "regex:metrics-.*"));

        assertThat(filter.test("logs-app"), equalTo(true));
        assertThat(filter.test("metrics-cpu"), equalTo(true));
        assertThat(filter.test("traces-app"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_mixed_literalAndRegex() {
        var filter = FilterScheme.filterByAllowList(List.of("exact-index", "regex:logs-.*-2024"));

        // Literal match
        assertThat(filter.test("exact-index"), equalTo(true));
        assertThat(filter.test("exact-index-2"), equalTo(false));
        
        // Regex match
        assertThat(filter.test("logs-app-2024"), equalTo(true));
        assertThat(filter.test("logs-web-2024"), equalTo(true));
        assertThat(filter.test("logs-app-2025"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_literal_withDotsAndHyphens() {
        var filter = FilterScheme.filterByAllowList(List.of("my.index-2024", "test-index.v1"));

        // Dots and hyphens are treated as literal characters
        assertThat(filter.test("my.index-2024"), equalTo(true));
        assertThat(filter.test("myXindex-2024"), equalTo(false));
        assertThat(filter.test("test-index.v1"), equalTo(true));
        assertThat(filter.test("test-indexXv1"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_matchesMultipleItems() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs-.*"));

        // Single pattern matches multiple different indices
        assertThat(filter.test("logs-app"), equalTo(true));
        assertThat(filter.test("logs-web"), equalTo(true));
        assertThat(filter.test("logs-database"), equalTo(true));
        assertThat(filter.test("logs-api-gateway"), equalTo(true));
    }

    @Test
    void testFilterByAllowList_regex_withEscapedDot() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs\\..*"));

        // Escaped dot matches only literal dot
        assertThat(filter.test("logs.app"), equalTo(true));
        assertThat(filter.test("logs.web.2024"), equalTo(true));
        assertThat(filter.test("logsXapp"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_alternation() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:(logs|metrics)-.*-2024"));

        assertThat(filter.test("logs-app-2024"), equalTo(true));
        assertThat(filter.test("metrics-cpu-2024"), equalTo(true));
        assertThat(filter.test("traces-app-2024"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_literal_lookingLikeRegex() {
        // A literal entry that contains regex-like characters but no prefix
        var filter = FilterScheme.filterByAllowList(List.of("test.*"));

        // Should match exactly, not as regex
        assertThat(filter.test("test.*"), equalTo(true));
        assertThat(filter.test("test123"), equalTo(false));
        assertThat(filter.test("testXYZ"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_emptyPattern() {
        // Empty pattern after prefix should throw exception
        try {
            FilterScheme.filterByAllowList(List.of("regex:"));
            assertThat("Should have thrown IllegalArgumentException", false);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage().contains("empty"), equalTo(true));
        }
    }

    @Test
    void testFilterByAllowList_regex_invalidPattern() {
        // Invalid regex pattern should throw exception
        try {
            FilterScheme.filterByAllowList(List.of("regex:[invalid"));
            assertThat("Should have thrown IllegalArgumentException", false);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage().contains("Invalid regex pattern"), equalTo(true));
        }
    }

    @Test
    void testFilterByAllowList_mixed_withInvalidRegex() {
        // If one entry is invalid, the whole allowlist should fail
        try {
            FilterScheme.filterByAllowList(List.of("valid-index", "regex:(unclosed"));
            assertThat("Should have thrown IllegalArgumentException", false);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage().contains("Invalid regex pattern"), equalTo(true));
        }
    }
}
