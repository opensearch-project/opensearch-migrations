/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.solr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.solr.TestCaseDefinition.AssertionRule;

/**
 * Deep recursive JSON comparison with per-path assertion rule support.
 * Produces human-readable diffs showing exactly what differs between two JSON structures.
 */
final class JsonDiff {

    record Difference(String path, Object expected, Object actual, AssertionRule matchedRule) {
        @Override
        public String toString() {
            var ruleInfo = matchedRule != null
                ? String.format(" [%s: %s]", matchedRule.rule(),
                    matchedRule.reason() != null ? matchedRule.reason() : "no reason")
                : "";
            return String.format("  %-40s expected: %s%n  %-40s actual:   %s%s",
                path, expected, "", actual, ruleInfo);
        }
    }

    private JsonDiff() {}

    /**
     * Compare two parsed JSON objects and return all differences, applying assertion rules.
     * <p>
     * Rules with type 'ignore' cause paths to be skipped entirely.
     * All other diffs are returned tagged with their matching rule (if any).
     */
    static List<Difference> diff(Object expected, Object actual, List<AssertionRule> rules) {
        var diffs = new ArrayList<Difference>();
        var ruleList = rules != null ? rules : List.<AssertionRule>of();
        compare(expected, actual, "", ruleList, diffs);
        return diffs;
    }

    /** Format diffs into a readable report string. */
    static String formatReport(List<Difference> diffs) {
        if (diffs.isEmpty()) return "No differences found.";
        var sb = new StringBuilder();
        var failures = diffs.stream().filter(d -> d.matchedRule() == null).toList();
        var expected = diffs.stream().filter(d -> d.matchedRule() != null).toList();

        if (!failures.isEmpty()) {
            sb.append(failures.size()).append(" unexpected difference(s):\n\n");
            for (var d : failures) {
                sb.append(d).append("\n\n");
            }
        }
        if (!expected.isEmpty()) {
            sb.append(expected.size()).append(" expected difference(s) (covered by rules):\n\n");
            for (var d : expected) {
                sb.append(d).append("\n\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void compare(
        Object expected, Object actual, String path, List<AssertionRule> rules, List<Difference> diffs
    ) {
        String currentPath = path.isEmpty() ? "$" : path;
        var rule = findMatchingRule(currentPath, rules);

        if (rule != null && "ignore".equals(rule.rule())) return;

        if (expected == null && actual == null) return;
        if (expected == null || actual == null) {
            diffs.add(new Difference(currentPath, expected, actual, rule));
            return;
        }

        if (rule != null && "regex".equals(rule.rule())) {
            if (rule.expected() != null && !String.valueOf(actual).matches(rule.expected())) {
                diffs.add(new Difference(currentPath, "regex:" + rule.expected(), actual, rule));
            }
            return;
        }

        if (expected instanceof Map && actual instanceof Map) {
            compareMaps((Map<String, Object>) expected, (Map<String, Object>) actual, currentPath, rules, diffs, rule);
        } else if (expected instanceof List && actual instanceof List) {
            compareLists((List<Object>) expected, (List<Object>) actual, currentPath, rules, diffs, rule);
        } else if (!normalizeNumber(expected).equals(normalizeNumber(actual))) {
            if (rule != null && "loose-type".equals(rule.rule())) {
                // loose-type: compare as strings
                if (!String.valueOf(expected).equals(String.valueOf(actual))) {
                    diffs.add(new Difference(currentPath, expected, actual, rule));
                }
            } else {
                diffs.add(new Difference(currentPath, expected, actual, rule));
            }
        }
    }

    private static void compareMaps(
        Map<String, Object> expected, Map<String, Object> actual,
        String path, List<AssertionRule> rules, List<Difference> diffs, AssertionRule parentRule
    ) {
        var allKeys = new LinkedHashSet<>(expected.keySet());
        allKeys.addAll(actual.keySet());
        for (var key : allKeys) {
            String childPath = path + "." + key;
            var childRule = findMatchingRule(childPath, rules);
            if (childRule != null && "ignore".equals(childRule.rule())) continue;
            if (!actual.containsKey(key)) {
                diffs.add(new Difference(childPath, expected.get(key), "<missing>",
                    childRule != null ? childRule : parentRule));
            } else if (!expected.containsKey(key)) {
                diffs.add(new Difference(childPath, "<missing>", actual.get(key),
                    childRule != null ? childRule : parentRule));
            } else {
                compare(expected.get(key), actual.get(key), childPath, rules, diffs);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void compareLists(
        List<Object> expected, List<Object> actual,
        String path, List<AssertionRule> rules, List<Difference> diffs, AssertionRule parentRule
    ) {
        var rule = parentRule != null ? parentRule : findMatchingRule(path, rules);

        List<Object> exp = expected;
        List<Object> act = actual;
        if (rule != null && "loose-order".equals(rule.rule())) {
            exp = sortForComparison(expected);
            act = sortForComparison(actual);
        }

        if (exp.size() != act.size()) {
            diffs.add(new Difference(path + ".length", exp.size(), act.size(), rule));
        }
        for (int i = 0; i < Math.min(exp.size(), act.size()); i++) {
            compare(exp.get(i), act.get(i), path + "[" + i + "]", rules, diffs);
        }
    }

    /** Sort a list for loose-order comparison. Objects sorted by JSON string representation. */
    private static List<Object> sortForComparison(List<Object> list) {
        var sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
        return sorted;
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

    /** Find the first assertion rule matching the given path. Supports [*] wildcards. */
    private static AssertionRule findMatchingRule(String path, List<AssertionRule> rules) {
        for (var rule : rules) {
            if (rule.path().equals(path)) return rule;
            if (rule.path().contains("[*]")) {
                var regex = java.util.regex.Pattern.quote(rule.path()).replace("[*]", "\\E\\[\\d+\\]\\Q");
                if (path.matches(regex)) return rule;
            }
        }
        return null;
    }
}
