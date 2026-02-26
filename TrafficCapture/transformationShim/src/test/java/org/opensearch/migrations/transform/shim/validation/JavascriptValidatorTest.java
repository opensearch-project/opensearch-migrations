package org.opensearch.migrations.transform.shim.validation;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavascriptValidatorTest {

    private JavascriptValidator validator;

    @AfterEach
    void tearDown() throws Exception {
        if (validator != null) validator.close();
    }

    private static TargetResponse resp(String name, int statusCode, Map<String, Object> body) {
        return new TargetResponse(name, statusCode, null, body, Duration.ofMillis(10), Duration.ZERO, Duration.ZERO, null);
    }

    @Test
    void statusCodeComparison_passes() {
        validator = new JavascriptValidator("status-check",
            "(function(responses) { return { passed: responses.s.statusCode === responses.o.statusCode }; })");
        var result = validator.validate(Map.of(
            "s", resp("s", 200, Map.of()),
            "o", resp("o", 200, Map.of())));
        assertTrue(result.passed());
    }

    @Test
    void statusCodeComparison_fails() {
        validator = new JavascriptValidator("status-check",
            "(function(responses) { return { passed: responses.s.statusCode === responses.o.statusCode, "
                + "detail: responses.s.statusCode + ' vs ' + responses.o.statusCode }; })");
        var result = validator.validate(Map.of(
            "s", resp("s", 200, Map.of()),
            "o", resp("o", 500, Map.of())));
        assertFalse(result.passed());
        assertTrue(result.detail().contains("200 vs 500"));
    }

    @Test
    void bodyAccess_works() {
        validator = new JavascriptValidator("body-check",
            "(function(responses) { return { passed: responses.s.body.count === responses.o.body.count }; })");
        var result = validator.validate(Map.of(
            "s", resp("s", 200, Map.of("count", 42)),
            "o", resp("o", 200, Map.of("count", 42))));
        assertTrue(result.passed());
    }

    @Test
    void scriptError_returnsError() {
        validator = new JavascriptValidator("bad-script",
            "(function(responses) { throw new Error('boom'); })");
        var result = validator.validate(Map.of(
            "s", resp("s", 200, Map.of())));
        assertFalse(result.passed());
        assertTrue(result.detail().startsWith("ERROR:"));
    }
}
