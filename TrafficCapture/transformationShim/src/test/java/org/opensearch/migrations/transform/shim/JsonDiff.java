/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deep recursive JSON comparison with path-based ignore support.
 * Produces human-readable diffs showing exactly what differs between two JSON structures.
 */
final class JsonDiff {

    record Difference(String path, Object expected, Object actual) {
        @Override
        public String toString() {
            return String.format("  %-40s expected: %s%n  %-40s actual:   %s", path, expected, "", actual);
        }
    }

    private JsonDiff() {}

    /**
     * Compare two parsed JSON objects and return all differences, ignoring specified paths.
     *
     * @param expected    the reference JSON (e.g. real Solr response)
     * @param actual      the JSON to check (e.g. proxy response)
     * @param ignorePaths dot-separated paths to skip (e.g. "responseHeader.QTime")
     * @return list of differences; empty if the structures match
     */
    static List<Difference> diff(Object expected, Object actual, Set<String> ignorePaths) {
        var diffs = new ArrayList<Difference>();
        compare(expected, actual, "", ignorePaths, diffs);
        return diffs;
    }

    /** Format diffs into a readable report string. */
    static String formatReport(List<Difference> diffs) {
        if (diffs.isEmpty()) return "No differences found.";
        var sb = new StringBuilder();
        sb.append(diffs.size()).append(" difference(s) found:\n\n");
        for (var d : diffs) {
            sb.append(d).append("\n\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void compare(
        Object expected, Object actual, String path, Set<String> ignorePaths, List<Difference> diffs
    ) {
        String currentPath = path.isEmpty() ? "$" : path;
        if (shouldIgnore(currentPath, ignorePaths)) return;

        if (expected == null && actual == null) return;
        if (expected == null || actual == null) {
            diffs.add(new Difference(currentPath, expected, actual));
            return;
        }

        if (expected instanceof Map && actual instanceof Map) {
            compareMaps((Map<String, Object>) expected, (Map<String, Object>) actual, currentPath, ignorePaths, diffs);
        } else if (expected instanceof List && actual instanceof List) {
            compareLists((List<Object>) expected, (List<Object>) actual, currentPath, ignorePaths, diffs);
        } else if (!normalizeNumber(expected).equals(normalizeNumber(actual))) {
            diffs.add(new Difference(currentPath, expected, actual));
        }
    }

    private static void compareMaps(
        Map<String, Object> expected, Map<String, Object> actual,
        String path, Set<String> ignorePaths, List<Difference> diffs
    ) {
        var allKeys = new LinkedHashSet<>(expected.keySet());
        allKeys.addAll(actual.keySet());
        for (var key : allKeys) {
            String childPath = path + "." + key;
            if (shouldIgnore(childPath, ignorePaths)) continue;
            if (!actual.containsKey(key)) {
                diffs.add(new Difference(childPath, expected.get(key), "<missing>"));
            } else if (!expected.containsKey(key)) {
                diffs.add(new Difference(childPath, "<missing>", actual.get(key)));
            } else {
                compare(expected.get(key), actual.get(key), childPath, ignorePaths, diffs);
            }
        }
    }

    private static void compareLists(
        List<Object> expected, List<Object> actual,
        String path, Set<String> ignorePaths, List<Difference> diffs
    ) {
        int maxLen = Math.max(expected.size(), actual.size());
        if (expected.size() != actual.size()) {
            diffs.add(new Difference(path + ".length", expected.size(), actual.size()));
        }
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            compare(expected.get(i), actual.get(i), path + "[" + i + "]", ignorePaths, diffs);
        }
    }

    /** Normalize numeric types so that 1 (int) equals 1.0 (double) equals 1 (long). */
    private static Object normalizeNumber(Object val) {
        if (val instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return (long) d;
            }
            return d;
        }
        return val;
    }

    /** Check if a path matches any ignore pattern. Supports [*] wildcards for array indices. */
    private static boolean shouldIgnore(String path, Set<String> ignorePaths) {
        if (ignorePaths.contains(path)) return true;
        for (var pattern : ignorePaths) {
            if (pattern.contains("[*]")) {
                // Pattern.quote wraps in \Q...\E; break out around [*] to insert \d+ regex
                var regex = java.util.regex.Pattern.quote(pattern).replace("[*]", "\\E\\[\\d+\\]\\Q");
                if (path.matches(regex)) return true;
            }
        }
        return false;
    }
}
