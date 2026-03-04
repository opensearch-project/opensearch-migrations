package org.opensearch.migrations.transform.shim.validation;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocCountValidatorTest {

    private static TargetResponse resp(String name, Map<String, Object> body) {
        return new TargetResponse(name, 200, null, body, Duration.ofMillis(10), Duration.ZERO, Duration.ZERO, null);
    }

    @Test
    void equal_passes() {
        var body = Map.<String, Object>of("response", Map.of("numFound", 10));
        var v = new DocCountValidator("s", "o", DocCountValidator.Comparison.EQUAL);
        assertTrue(v.validate(Map.of("s", resp("s", body), "o", resp("o", body))).passed());
    }

    @Test
    void equal_fails() {
        var v = new DocCountValidator("s", "o", DocCountValidator.Comparison.EQUAL);
        assertFalse(v.validate(Map.of(
            "s", resp("s", Map.of("response", Map.of("numFound", 10))),
            "o", resp("o", Map.of("response", Map.of("numFound", 5))))).passed());
    }

    @Test
    void aLessOrEqual_passes() {
        var v = new DocCountValidator("s", "o", DocCountValidator.Comparison.A_LESS_OR_EQUAL);
        assertTrue(v.validate(Map.of(
            "s", resp("s", Map.of("response", Map.of("numFound", 5))),
            "o", resp("o", Map.of("response", Map.of("numFound", 10))))).passed());
    }

    @Test
    void aLessOrEqual_failsWhenGreater() {
        var v = new DocCountValidator("s", "o", DocCountValidator.Comparison.A_LESS_OR_EQUAL);
        assertFalse(v.validate(Map.of(
            "s", resp("s", Map.of("response", Map.of("numFound", 15))),
            "o", resp("o", Map.of("response", Map.of("numFound", 10))))).passed());
    }

    @Test
    void missingPath_fails() {
        var v = new DocCountValidator("s", "o", DocCountValidator.Comparison.EQUAL);
        assertFalse(v.validate(Map.of(
            "s", resp("s", Map.of("other", 1)),
            "o", resp("o", Map.of("other", 1)))).passed());
    }

    @Test
    void customPath_works() {
        var v = new DocCountValidator("s", "o", DocCountValidator.Comparison.EQUAL, "hits.total");
        assertTrue(v.validate(Map.of(
            "s", resp("s", Map.of("hits", Map.of("total", 42))),
            "o", resp("o", Map.of("hits", Map.of("total", 42))))).passed());
    }
}
