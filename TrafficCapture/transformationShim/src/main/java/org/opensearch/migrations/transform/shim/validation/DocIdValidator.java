package org.opensearch.migrations.transform.shim.validation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that two targets return the same document IDs.
 * Extracts IDs from a configurable JSON path (default: "response.docs" array, "id" field).
 */
public class DocIdValidator implements ResponseValidator {
    private final String targetA;
    private final String targetB;
    private final boolean orderMatters;
    private final String docsPath;
    private final String idField;

    public DocIdValidator(String targetA, String targetB, boolean orderMatters) {
        this(targetA, targetB, orderMatters, "response.docs", "id");
    }

    public DocIdValidator(String targetA, String targetB, boolean orderMatters,
                          String docsPath, String idField) {
        this.targetA = targetA;
        this.targetB = targetB;
        this.orderMatters = orderMatters;
        this.docsPath = docsPath;
        this.idField = idField;
    }

    @Override
    public ValidationResult validate(Map<String, TargetResponse> responses) {
        String name = "doc-ids(" + targetA + "," + targetB + ")";
        TargetResponse a = responses.get(targetA);
        TargetResponse b = responses.get(targetB);
        if (a == null || b == null || !a.isSuccess() || !b.isSuccess()) {
            return new ValidationResult(name, false, "one or both targets missing/errored");
        }
        if (a.parsedBody() == null || b.parsedBody() == null) {
            return new ValidationResult(name, false, "one or both responses not parseable as JSON");
        }

        Optional<List<String>> idsA = extractIds(a.parsedBody());
        Optional<List<String>> idsB = extractIds(b.parsedBody());
        if (idsA.isEmpty() || idsB.isEmpty()) {
            return new ValidationResult(name, false,
                "could not extract doc IDs at '" + docsPath + "." + idField + "'");
        }

        return compareIds(name, idsA.get(), idsB.get());
    }

    private ValidationResult compareIds(String name, List<String> idsA, List<String> idsB) {
        if (orderMatters) {
            boolean passed = idsA.equals(idsB);
            return new ValidationResult(name, passed,
                passed ? null : targetA + "=" + idsA + " vs " + targetB + "=" + idsB);
        }
        Set<String> setA = new LinkedHashSet<>(idsA);
        Set<String> setB = new LinkedHashSet<>(idsB);
        boolean passed = setA.equals(setB);
        if (passed) return new ValidationResult(name, true, null);

        Set<String> onlyA = new LinkedHashSet<>(setA);
        onlyA.removeAll(setB);
        Set<String> onlyB = new LinkedHashSet<>(setB);
        onlyB.removeAll(setA);
        return new ValidationResult(name, false,
            "only in " + targetA + "=" + onlyA + ", only in " + targetB + "=" + onlyB);
    }

    @SuppressWarnings("unchecked")
    private Optional<List<String>> extractIds(Map<String, Object> body) {
        String[] parts = docsPath.split("\\.");
        Object current = body;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return Optional.empty();
            }
        }
        if (!(current instanceof List)) return Optional.empty();
        List<String> ids = ((List<?>) current).stream()
            .filter(Map.class::isInstance)
            .map(doc -> {
                Object id = ((Map<String, Object>) doc).get(idField);
                return id != null ? id.toString() : null;
            })
            .collect(Collectors.toList());
        return Optional.of(ids);
    }
}
