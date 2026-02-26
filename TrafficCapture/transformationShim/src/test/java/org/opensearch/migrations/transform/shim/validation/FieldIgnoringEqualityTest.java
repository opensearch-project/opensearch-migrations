package org.opensearch.migrations.transform.shim.validation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldIgnoringEqualityTest {

    private static TargetResponse resp(String name, Map<String, Object> body) {
        return new TargetResponse(name, 200, null, body, Duration.ofMillis(10), null);
    }

    @Test
    void identicalBodies_pass() {
        var body = Map.<String, Object>of("a", 1, "b", "hello");
        var validator = new FieldIgnoringEquality("s", "o", Set.of());
        var result = validator.validate(Map.of("s", resp("s", body), "o", resp("o", body)));
        assertTrue(result.passed());
    }

    @Test
    void differentBodies_fail() {
        var validator = new FieldIgnoringEquality("s", "o", Set.of());
        var result = validator.validate(Map.of(
            "s", resp("s", Map.of("a", 1)),
            "o", resp("o", Map.of("a", 2))));
        assertFalse(result.passed());
        assertTrue(result.detail().contains("a"));
    }

    @Test
    void ignoredPaths_skipped() {
        var validator = new FieldIgnoringEquality("s", "o", Set.of("header.QTime"));
        var bodyA = Map.<String, Object>of("header", Map.of("QTime", 10, "status", 0));
        var bodyB = Map.<String, Object>of("header", Map.of("QTime", 99, "status", 0));
        var result = validator.validate(Map.of("s", resp("s", bodyA), "o", resp("o", bodyB)));
        assertTrue(result.passed());
    }

    @Test
    void nestedDifference_reported() {
        var validator = new FieldIgnoringEquality("s", "o", Set.of());
        var bodyA = Map.<String, Object>of("response", Map.of("docs", List.of(1, 2)));
        var bodyB = Map.<String, Object>of("response", Map.of("docs", List.of(1, 3)));
        var result = validator.validate(Map.of("s", resp("s", bodyA), "o", resp("o", bodyB)));
        assertFalse(result.passed());
        assertTrue(result.detail().contains("response.docs"));
    }

    @Test
    void missingTarget_fails() {
        var validator = new FieldIgnoringEquality("s", "o", Set.of());
        var result = validator.validate(Map.of("s", resp("s", Map.of("a", 1))));
        assertFalse(result.passed());
        assertTrue(result.detail().contains("missing"));
    }

    @Test
    void erroredTarget_fails() {
        var validator = new FieldIgnoringEquality("s", "o", Set.of());
        var result = validator.validate(Map.of(
            "s", resp("s", Map.of("a", 1)),
            "o", TargetResponse.error("o", Duration.ZERO, new RuntimeException("fail"))));
        assertFalse(result.passed());
    }
}
