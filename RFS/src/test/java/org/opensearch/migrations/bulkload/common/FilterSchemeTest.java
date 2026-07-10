package org.opensearch.migrations.bulkload.common;

import java.util.List;

import org.opensearch.migrations.bulkload.common.FilterScheme.FilterContext;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class FilterSchemeTest {
    @Test
    void testExcludedByPrefix() {
        var filter = FilterScheme.filterByAllowList(null, FilterContext.INDEX);

        // Should be excluded due to prefix
        assertThat(filter.test("apm-test"), equalTo(false));
        assertThat(filter.test(".hidden"), equalTo(false));
        assertThat(filter.test("searchguard"), equalTo(false));
        assertThat(filter.test("searchguard_config"), equalTo(false));
        assertThat(filter.test("sg7-auditlog-2026.04.10"), equalTo(false));

        // Should not be excluded (no matching prefix)
        assertThat(filter.test("myindex"), equalTo(true));
        assertThat(filter.test("logs-2024"), equalTo(true));
        assertThat(filter.test("metrics-2024"), equalTo(true));
    }

    @Test
    void testExcludedBySuffix_templateContext() {
        // Suffixes like @settings / @mappings are template naming conventions;
        // they only apply to template contexts. Index names can't contain '@'
        // anyway, so this is a documentation-of-intent change.
        var filter = FilterScheme.filterByAllowList(null, FilterContext.INDEX_TEMPLATE);

        assertThat(filter.test("index@settings"), equalTo(false));
        assertThat(filter.test("test@mappings"), equalTo(false));

        // Should not be excluded (no matching suffix)
        assertThat(filter.test("settings"), equalTo(true));
    }

    @Test
    void testExcludedByExactName_templateContext() {
        var filter = FilterScheme.filterByAllowList(null, FilterContext.INDEX_TEMPLATE);

        // Should be excluded due to exact name match
        assertThat(filter.test("logs"), equalTo(false));
        assertThat(filter.test("metrics"), equalTo(false));

        // Should not be excluded (no exact name match)
        assertThat(filter.test("metrics_custom"), equalTo(true));
    }

    @Test
    void testLogsIndex_isMigrated() {
        // Key behavior of this change: a user index literally named "logs"
        // (or "metrics", "traces", etc.) must be migrated as data. The name
        // only excludes the template of the same name.
        var filter = FilterScheme.filterByAllowList(null, FilterContext.INDEX);

        assertThat(filter.test("logs"), equalTo(true));
        assertThat(filter.test("metrics"), equalTo(true));
        assertThat(filter.test("traces"), equalTo(true));
        assertThat(filter.test("profiling"), equalTo(true));
        assertThat(filter.test("synthetics"), equalTo(true));
        assertThat(filter.test("agentless"), equalTo(true));
        assertThat(filter.test("elastic-connectors"), equalTo(true));
        assertThat(filter.test("ilm-history"), equalTo(true));
        assertThat(filter.test("tenant_template"), equalTo(true));
        assertThat(filter.test("search-acl-filter"), equalTo(true));
        assertThat(filter.test("logs-mappings"), equalTo(true));
        assertThat(filter.test("logs-settings"), equalTo(true));
        assertThat(filter.test("logs-tsdb-settings"), equalTo(true));
        assertThat(filter.test("metrics-mappings"), equalTo(true));
        assertThat(filter.test("metrics-settings"), equalTo(true));
        assertThat(filter.test("metrics-tsdb-settings"), equalTo(true));
        assertThat(filter.test("traces-mappings"), equalTo(true));
        assertThat(filter.test("traces-settings"), equalTo(true));
        assertThat(filter.test("traces-tsdb-settings"), equalTo(true));
    }

    @Test
    void testLogsTemplate_isExcluded_inAllTemplateContexts() {
        // The flip side: a template named "logs" is stack-managed and must
        // NOT be created on the target in any template context.
        for (var ctx : FilterContext.TEMPLATE_CONTEXTS) {
            var filter = FilterScheme.filterByAllowList(null, ctx);
            assertThat("logs template should be excluded in " + ctx,
                filter.test("logs"), equalTo(false));
            assertThat("metrics template should be excluded in " + ctx,
                filter.test("metrics"), equalTo(false));
            assertThat("traces template should be excluded in " + ctx,
                filter.test("traces"), equalTo(false));
            assertThat("tenant_template should be excluded in " + ctx,
                filter.test("tenant_template"), equalTo(false));
        }
    }

    @Test
    void testWatcherIndices_excludedInAllContexts() {
        // X-Pack watcher state lives in real indices on the source, not
        // templates. They should be excluded in every context.
        for (var ctx : FilterContext.values()) {
            var filter = FilterScheme.filterByAllowList(null, ctx);
            assertThat("triggered_watches should be excluded in " + ctx,
                filter.test("triggered_watches"), equalTo(false));
            assertThat("watches should be excluded in " + ctx,
                filter.test("watches"), equalTo(false));
            assertThat("watch_history_3 should be excluded in " + ctx,
                filter.test("watch_history_3"), equalTo(false));
        }

        // Similar names that shouldn't be excluded
        var indexFilter = FilterScheme.filterByAllowList(null, FilterContext.INDEX);
        assertThat(indexFilter.test("watch_custom"), equalTo(true));
    }

    @Test
    void testFilterByAllowList_null() {
        var filter = FilterScheme.filterByAllowList(null, FilterContext.INDEX);

        assertThat(filter.test("test"), equalTo(true));
        assertThat(filter.test("test1"), equalTo(true));
        assertThat(filter.test(".test"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_empty() {
        var filter = FilterScheme.filterByAllowList(List.of(), FilterContext.INDEX);

        assertThat(filter.test("test"), equalTo(true));
        assertThat(filter.test("test1"), equalTo(true));
        assertThat(filter.test(".test"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_matches() {
        var filter = FilterScheme.filterByAllowList(List.of("test"), FilterContext.INDEX);

        assertThat(filter.test("test"), equalTo(true));
        assertThat(filter.test("test1"), equalTo(false));
        assertThat(filter.test(".test"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_matches_with_period() {
        var filter = FilterScheme.filterByAllowList(List.of(".test"), FilterContext.INDEX);

        assertThat(filter.test("test"), equalTo(false));
        assertThat(filter.test("test1"), equalTo(false));
        assertThat(filter.test(".test"), equalTo(true));
    }

    @Test
    void testAllowlistBypassesBuiltinExclusions() {
        // When an allowlist is supplied, built-in context-scoped exclusions
        // are bypassed entirely — the allowlist is the sole filter. This
        // preserves the prior contract: users can opt-in to stack-managed
        // templates by naming them explicitly.
        var filter = FilterScheme.filterByAllowList(List.of("logs"), FilterContext.INDEX_TEMPLATE);

        assertThat(filter.test("logs"), equalTo(true));
    }

    @Test
    void testExcludedXPackWatchIndices() {
        var filter = FilterScheme.filterByAllowList(null, FilterContext.INDEX);

        // These should all be excluded as xpack creates them by default
        assertThat(filter.test("triggered_watches"), equalTo(false));
        assertThat(filter.test("watches"), equalTo(false));
        assertThat(filter.test("watch_history_3"), equalTo(false));

        // Similar index names to not exclude
        assertThat(filter.test("watch_custom"), equalTo(true));
    }

    @Test
    void testExcludedKibanaIndexTemplates() {
        var filter = FilterScheme.filterByAllowList(null, FilterContext.INDEX);

        // Kibana index templates and indices should be excluded
        assertThat(filter.test("kibana_index_template:.kibana"), equalTo(false));
        assertThat(filter.test("kibana_index_template:.kibana_*"), equalTo(false));
        assertThat(filter.test("kibana_logs"), equalTo(false));
        assertThat(filter.test("kibana"), equalTo(false));
        assertThat(filter.test(".kibana"), equalTo(false));

        // User indices with kibana elsewhere in the name should not be excluded
        assertThat(filter.test("my_kibana_data"), equalTo(true));
        assertThat(filter.test("my_kibana_logs"), equalTo(true));
    }

    @Test
    void testExcludedES717SystemIndices() {
        var filter = FilterScheme.filterByAllowList(null, FilterContext.INDEX);

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
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs-.*"), FilterContext.INDEX);

        assertThat(filter.test("logs-app"), equalTo(true));
        assertThat(filter.test("logs-web"), equalTo(true));
        assertThat(filter.test("logs-2024"), equalTo(true));
        assertThat(filter.test("metrics-app"), equalTo(false));
        assertThat(filter.test("logs"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_withDigits() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:test-\\d+"), FilterContext.INDEX);

        assertThat(filter.test("test-1"), equalTo(true));
        assertThat(filter.test("test-123"), equalTo(true));
        assertThat(filter.test("test-0"), equalTo(true));
        assertThat(filter.test("test-"), equalTo(false));
        assertThat(filter.test("test-abc"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_complex() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs-[a-z]+-\\d{4}"), FilterContext.INDEX);

        assertThat(filter.test("logs-app-2024"), equalTo(true));
        assertThat(filter.test("logs-web-2024"), equalTo(true));
        assertThat(filter.test("logs-app-24"), equalTo(false));
        assertThat(filter.test("logs-APP-2024"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_multiplePatterns() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs-.*", "regex:metrics-.*"), FilterContext.INDEX);

        assertThat(filter.test("logs-app"), equalTo(true));
        assertThat(filter.test("metrics-cpu"), equalTo(true));
        assertThat(filter.test("traces-app"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_mixed_literalAndRegex() {
        var filter = FilterScheme.filterByAllowList(List.of("exact-index", "regex:logs-.*-2024"), FilterContext.INDEX);

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
        var filter = FilterScheme.filterByAllowList(List.of("my.index-2024", "test-index.v1"), FilterContext.INDEX);

        // Dots and hyphens are treated as literal characters
        assertThat(filter.test("my.index-2024"), equalTo(true));
        assertThat(filter.test("myXindex-2024"), equalTo(false));
        assertThat(filter.test("test-index.v1"), equalTo(true));
        assertThat(filter.test("test-indexXv1"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_matchesMultipleItems() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs-.*"), FilterContext.INDEX);

        // Single pattern matches multiple different indices
        assertThat(filter.test("logs-app"), equalTo(true));
        assertThat(filter.test("logs-web"), equalTo(true));
        assertThat(filter.test("logs-database"), equalTo(true));
        assertThat(filter.test("logs-api-gateway"), equalTo(true));
    }

    @Test
    void testFilterByAllowList_regex_withEscapedDot() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:logs\\..*"), FilterContext.INDEX);

        // Escaped dot matches only literal dot
        assertThat(filter.test("logs.app"), equalTo(true));
        assertThat(filter.test("logs.web.2024"), equalTo(true));
        assertThat(filter.test("logsXapp"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_alternation() {
        var filter = FilterScheme.filterByAllowList(List.of("regex:(logs|metrics)-.*-2024"), FilterContext.INDEX);

        assertThat(filter.test("logs-app-2024"), equalTo(true));
        assertThat(filter.test("metrics-cpu-2024"), equalTo(true));
        assertThat(filter.test("traces-app-2024"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_literal_lookingLikeRegex() {
        // A literal entry that contains regex-like characters but no prefix
        var filter = FilterScheme.filterByAllowList(List.of("test.*"), FilterContext.INDEX);

        // Should match exactly, not as regex
        assertThat(filter.test("test.*"), equalTo(true));
        assertThat(filter.test("test123"), equalTo(false));
        assertThat(filter.test("testXYZ"), equalTo(false));
    }

    @Test
    void testFilterByAllowList_regex_emptyPattern() {
        // Empty pattern after prefix should throw exception
        try {
            FilterScheme.filterByAllowList(List.of("regex:"), FilterContext.INDEX);
            assertThat("Should have thrown IllegalArgumentException", false);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage().contains("empty"), equalTo(true));
        }
    }

    @Test
    void testFilterByAllowList_regex_invalidPattern() {
        // Invalid regex pattern should throw exception
        try {
            FilterScheme.filterByAllowList(List.of("regex:[invalid"), FilterContext.INDEX);
            assertThat("Should have thrown IllegalArgumentException", false);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage().contains("Invalid regex pattern"), equalTo(true));
        }
    }

    @Test
    void testFilterByAllowList_mixed_withInvalidRegex() {
        // If one entry is invalid, the whole allowlist should fail
        try {
            FilterScheme.filterByAllowList(List.of("valid-index", "regex:(unclosed"), FilterContext.INDEX);
            assertThat("Should have thrown IllegalArgumentException", false);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage().contains("Invalid regex pattern"), equalTo(true));
        }
    }
}
