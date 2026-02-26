package org.opensearch.migrations.transform.shim.validation;

import java.util.List;
import java.util.Map;

/**
 * Validates the document count relationship between two targets' responses.
 * Extracts doc count from a configurable JSON path (default: "response.numFound").
 */
public class DocCountValidator implements ResponseValidator {
    private final String targetA;
    private final String targetB;
    private final Comparison comparison;
    private final String countPath;

    public enum Comparison { EQUAL, A_LESS_OR_EQUAL, A_GREATER_OR_EQUAL }

    public DocCountValidator(String targetA, String targetB, Comparison comparison) {
        this(targetA, targetB, comparison, "response.numFound");
    }

    public DocCountValidator(String targetA, String targetB, Comparison comparison, String countPath) {
        this.targetA = targetA;
        this.targetB = targetB;
        this.comparison = comparison;
        this.countPath = countPath;
    }

    @Override
    public ValidationResult validate(Map<String, TargetResponse> responses) {
        String name = "doc-count(" + targetA + "," + targetB + ")";
        TargetResponse a = responses.get(targetA);
        TargetResponse b = responses.get(targetB);
        if (a == null || b == null || !a.isSuccess() || !b.isSuccess()) {
            return new ValidationResult(name, false, "one or both targets missing/errored");
        }
        if (a.parsedBody() == null || b.parsedBody() == null) {
            return new ValidationResult(name, false, "one or both responses not parseable as JSON");
        }

        Long countA = extractCount(a.parsedBody());
        Long countB = extractCount(b.parsedBody());
        if (countA == null || countB == null) {
            return new ValidationResult(name, false,
                "could not extract count at '" + countPath + "' ("
                    + targetA + "=" + countA + ", " + targetB + "=" + countB + ")");
        }

        boolean passed = switch (comparison) {
            case EQUAL -> countA.equals(countB);
            case A_LESS_OR_EQUAL -> countA <= countB;
            case A_GREATER_OR_EQUAL -> countA >= countB;
        };

        return new ValidationResult(name, passed,
            targetA + "=" + countA + ", " + targetB + "=" + countB);
    }

    @SuppressWarnings("unchecked")
    private Long extractCount(Map<String, Object> body) {
        String[] parts = countPath.split("\\.");
        Object current = body;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current instanceof Number ? ((Number) current).longValue() : null;
    }
}
