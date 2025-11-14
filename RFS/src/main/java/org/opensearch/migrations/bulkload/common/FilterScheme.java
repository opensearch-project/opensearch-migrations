package org.opensearch.migrations.bulkload.common;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public class FilterScheme {
    private FilterScheme() {}

    private static final List<String> EXCLUDED_PREFIXES = Arrays.asList(
            ".",
            "apm-",
            "apm@",
            "behavioral_analytics-",
            "data-streams-",
            "data-streams@",
            "ecs@",
            "elastic-connectors-",
            "elastic_agent.",
            "ilm-history-",
            "logs-elastic_agent",
            "logs-endpoint.",
            "logs-index_pattern",
            "logs-system.",
            "metricbeat-",
            "metrics-elastic_agent",
            "metrics-endpoint.",
            "metrics-index_pattern",
            "metrics-metadata-",
            "metrics-system.",
            "profiling-",
            "synthetics-"
    );

    private static final List<String> EXCLUDED_SUFFIXES = Arrays.asList(
            "@ilm",
            "@lifecycle",
            "@mappings",
            "@package",
            "@settings",
            "@template",
            "@tsdb-settings"
        );

    private static final List<String> EXCLUDED_NAMES = Arrays.asList(
            "agentless",
            "elastic-connectors",
            "ilm-history",
            "logs",
            "logs-mappings",
            "logs-settings",
            "logs-tsdb-settings",
            "metrics",
            "metrics-mappings",
            "metrics-settings",
            "metrics-tsdb-settings",
            "profiling",
            "search-acl-filter",
            "synthetics",
            "tenant_template",
            "traces",
            "traces-mappings",
            "traces-settings",
            "traces-tsdb-settings",
            "triggered_watches",
            "watches",
            "watch_history_3"
    );

    public static Predicate<String> filterByAllowList(List<String> allowlist) {
        // Validate and pre-compile all allowlist entries to fail fast on invalid patterns
        final List<AllowlistEntry> compiledEntries;
        if (allowlist != null && !allowlist.isEmpty()) {
            compiledEntries = allowlist.stream()
                .map(AllowlistEntry::new)
                .toList();
        } else {
            compiledEntries = null;
        }
        
        return item -> {
            if (compiledEntries == null) {
                return !isExcluded(item);
            } else {
                return compiledEntries.stream()
                    .anyMatch(entry -> entry.matches(item));
            }
        };
    }

    private static boolean isExcluded(String item) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (item.startsWith(prefix)) {
                return true;
            }
        }
        for (String suffix : EXCLUDED_SUFFIXES) {
            if (item.endsWith(suffix)) {
                return true;
            }
        }
        return EXCLUDED_NAMES.contains(item);
    }
}
