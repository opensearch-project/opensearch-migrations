package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.function.Predicate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FilterScheme {
    private FilterScheme() {}

    public static Predicate<String> filterByAllowList(List<String> allowlist) {
        return item -> {
            boolean accepted;
            // By default allow all items except 'system' items that start with a period
            if (allowlist == null || allowlist.isEmpty()) {
                accepted = !item.startsWith(".");
            } else {
                accepted = allowlist.contains(item);
            }
            return accepted;
        };
    }
}
