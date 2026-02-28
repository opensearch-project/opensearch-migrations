package org.opensearch.migrations.transform.shim.validation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deep-compares two targets' parsed JSON responses, ignoring specified dot-delimited paths.
 * Paths like "responseHeader.QTime" skip that nested key during comparison.
 */
public class FieldIgnoringEquality implements ResponseValidator {
    private static final String RULE_PREFIX = "field-equality(";

    private final String targetA;
    private final String targetB;
    private final Set<String> ignoredPaths;

    public FieldIgnoringEquality(String targetA, String targetB, Set<String> ignoredPaths) {
        this.targetA = targetA;
        this.targetB = targetB;
        this.ignoredPaths = ignoredPaths;
    }

    @Override
    public ValidationResult validate(Map<String, TargetResponse> responses) {
        String name = RULE_PREFIX + targetA + "," + targetB + ")";
        TargetResponse a = responses.get(targetA);
        TargetResponse b = responses.get(targetB);
        if (a == null || b == null || !a.isSuccess() || !b.isSuccess()) {
            return new ValidationResult(name, false, "one or both targets missing/errored");
        }
        if (a.parsedBody() == null || b.parsedBody() == null) {
            return new ValidationResult(name, false, "one or both responses not parseable as JSON");
        }

        List<String> diffs = new java.util.ArrayList<>();
        deepCompare(a.parsedBody(), b.parsedBody(), "", diffs);
        return diffs.isEmpty()
            ? new ValidationResult(name, true, null)
            : new ValidationResult(name, false, String.join("; ", diffs));
    }

    @SuppressWarnings("unchecked")
    private void deepCompare(Object a, Object b, String path, List<String> diffs) {
        if (ignoredPaths.contains(path)) return;

        if (a instanceof Map && b instanceof Map) {
            compareMaps((Map<String, Object>) a, (Map<String, Object>) b, path, diffs);
        } else if (a instanceof List && b instanceof List) {
            compareLists((List<?>) a, (List<?>) b, path, diffs);
        } else if (!Objects.equals(a, b)) {
            diffs.add(path + ": " + a + " vs " + b);
        }
    }

    @SuppressWarnings("unchecked")
    private void compareMaps(Map<String, Object> mapA, Map<String, Object> mapB,
                             String path, List<String> diffs) {
        Set<String> allKeys = new java.util.LinkedHashSet<>(mapA.keySet());
        allKeys.addAll(mapB.keySet());
        for (String key : allKeys) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (ignoredPaths.contains(childPath)) continue;
            if (!mapA.containsKey(key)) {
                diffs.add(childPath + ": missing in " + targetA);
            } else if (!mapB.containsKey(key)) {
                diffs.add(childPath + ": missing in " + targetB);
            } else {
                deepCompare(mapA.get(key), mapB.get(key), childPath, diffs);
            }
        }
    }

    private void compareLists(List<?> listA, List<?> listB, String path, List<String> diffs) {
        if (listA.size() != listB.size()) {
            diffs.add(path + ": list size " + listA.size() + " vs " + listB.size());
            return;
        }
        for (int i = 0; i < listA.size(); i++) {
            deepCompare(listA.get(i), listB.get(i), path + "[" + i + "]", diffs);
        }
    }
}
