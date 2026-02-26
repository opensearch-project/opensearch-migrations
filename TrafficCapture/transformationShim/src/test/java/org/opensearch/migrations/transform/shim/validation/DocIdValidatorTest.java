package org.opensearch.migrations.transform.shim.validation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocIdValidatorTest {

    private static TargetResponse resp(String name, Map<String, Object> body) {
        return new TargetResponse(name, 200, null, body, Duration.ofMillis(10), Duration.ZERO, Duration.ZERO, null);
    }

    private static Map<String, Object> docsBody(List<?> docs) {
        return Map.of("response", Map.of("docs", docs));
    }

    @Test
    void sameIdsUnordered_passes() {
        var docs = List.of(Map.<String, Object>of("id", "1"), Map.of("id", "2"));
        var v = new DocIdValidator("s", "o", false);
        assertTrue(v.validate(Map.of(
            "s", resp("s", docsBody(docs)),
            "o", resp("o", docsBody(List.of(Map.of("id", "2"), Map.of("id", "1")))))).passed());
    }

    @Test
    void sameIdsOrdered_passes() {
        var docs = List.of(Map.<String, Object>of("id", "1"), Map.of("id", "2"));
        var v = new DocIdValidator("s", "o", true);
        assertTrue(v.validate(Map.of(
            "s", resp("s", docsBody(docs)),
            "o", resp("o", docsBody(docs)))).passed());
    }

    @Test
    void sameIdsOrdered_failsWhenDifferentOrder() {
        var v = new DocIdValidator("s", "o", true);
        assertFalse(v.validate(Map.of(
            "s", resp("s", docsBody(List.of(Map.of("id", "1"), Map.of("id", "2")))),
            "o", resp("o", docsBody(List.of(Map.of("id", "2"), Map.of("id", "1")))))).passed());
    }

    @Test
    void differentIds_fails() {
        var v = new DocIdValidator("s", "o", false);
        assertFalse(v.validate(Map.of(
            "s", resp("s", docsBody(List.of(Map.of("id", "1")))),
            "o", resp("o", docsBody(List.of(Map.of("id", "2")))))).passed());
    }

    @Test
    void missingDocsPath_fails() {
        var v = new DocIdValidator("s", "o", false);
        assertFalse(v.validate(Map.of(
            "s", resp("s", Map.of("other", 1)),
            "o", resp("o", Map.of("other", 1)))).passed());
    }
}
