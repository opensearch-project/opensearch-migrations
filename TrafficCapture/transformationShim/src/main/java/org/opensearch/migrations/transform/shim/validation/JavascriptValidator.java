package org.opensearch.migrations.transform.shim.validation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.transform.JavascriptTransformer;

/**
 * Custom JavaScript validator using GraalVM (via {@link JavascriptTransformer}).
 * The script must be a function that receives a responses object and returns
 * {@code { passed: boolean, detail: string }}.
 *
 * <p>Example script:
 * <pre>{@code
 * (function(responses) {
 *     var a = responses.s;
 *     var b = responses.o;
 *     return { passed: a.statusCode === b.statusCode, detail: a.statusCode + " vs " + b.statusCode };
 * })
 * }</pre>
 *
 * <p>Not thread-safe — each thread should use its own instance.
 */
public class JavascriptValidator implements ResponseValidator, AutoCloseable {
    private final String name;
    private final JavascriptTransformer transformer;

    public JavascriptValidator(String name, String script) {
        this.name = name;
        this.transformer = new JavascriptTransformer(script, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ValidationResult validate(Map<String, TargetResponse> responses) {
        Map<String, Object> jsResponses = new LinkedHashMap<>();
        for (var entry : responses.entrySet()) {
            TargetResponse tr = entry.getValue();
            Map<String, Object> targetMap = new LinkedHashMap<>();
            targetMap.put("statusCode", tr.statusCode());
            targetMap.put("body", tr.parsedBody());
            targetMap.put("latency", tr.latency().toMillis());
            targetMap.put("error", tr.error() != null ? tr.error().getMessage() : null);
            jsResponses.put(entry.getKey(), targetMap);
        }

        try {
            var result = (Map<String, Object>) transformer.transformJson(jsResponses);
            boolean passed = Boolean.TRUE.equals(result.get("passed"));
            Object detail = result.get("detail");
            return new ValidationResult(name, passed, detail != null ? detail.toString() : null);
        } catch (Exception e) {
            return new ValidationResult(name, false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        transformer.close();
    }
}
