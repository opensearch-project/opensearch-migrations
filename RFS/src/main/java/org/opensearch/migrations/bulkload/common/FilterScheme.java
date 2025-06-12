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
            "ilm-history-",
            "logs-",
            "metrics-",
            "profiling-",
            "synthetics-",
            "traces-"
    );

    private static final List<String> EXCLUDED_SUFFIXES = Arrays.asList(
            "@settings",
            "@mappings",
            "@tsdb-settings"
        );

    private static final List<String> EXCLUDED_NAMES = Arrays.asList(
            "elastic-connectors",
            "ilm-history",
            "logs",
            "metrics",
            "profiling",
            "search-acl-filter",
            "synthetics"
    );

    public static Predicate<String> filterByAllowList(List<String> allowlist) {
        return item -> {
            if (allowlist == null || allowlist.isEmpty()) {
                return !isExcluded(item);
            } else {
                return allowlist.contains(item);
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
