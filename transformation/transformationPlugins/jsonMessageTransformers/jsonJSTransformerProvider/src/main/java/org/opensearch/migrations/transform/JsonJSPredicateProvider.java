package org.opensearch.migrations.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * JS-based request filter predicate provider. Loads a JavaScript file that returns
 * a function evaluating to true (allow) or false (skip).
 *
 * <p>Supports the same config keys as {@link JsonJSTransformerProvider}:
 * {@code initializationScriptFile}, {@code initializationResourcePath}, or
 * {@code initializationScript}.
 *
 * <p>Config example:
 * <pre>{@code {"JsonJSPredicateProvider":{"initializationScriptFile":"/path/to/filter.js"}}}</pre>
 *
 * <p>The JS must follow the standard closure pattern and return a boolean:
 * <pre>{@code
 * (function(bindings) {
 *   return function(msg) {
 *     var uri = msg.get('URI');
 *     return uri.indexOf('/select') >= 0;
 *   };
 * })
 * }</pre>
 */
public class JsonJSPredicateProvider implements IJsonPredicateProvider {

    private static final String SCRIPT_FILE_KEY = "initializationScriptFile";
    private static final String RESOURCE_PATH_KEY = "initializationResourcePath";
    private static final String INLINE_SCRIPT_KEY = "initializationScript";

    @Override
    @SuppressWarnings("unchecked")
    public IJsonPredicate createPredicate(Object jsonConfig) {
        if (!(jsonConfig instanceof Map)) {
            throw new IllegalArgumentException(getConfigUsageStr());
        }
        var config = (Map<String, Object>) jsonConfig;
        try {
            var script = resolveScript(config);
            var transformer = new JavascriptTransformer(script, Map.of());
            return request -> {
                Object result = transformer.transformJson(request);
                if (result instanceof Boolean) return (Boolean) result;
                return "true".equals(String.valueOf(result));
            };
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to create JS predicate: " + e.getMessage(), e);
        }
    }

    private String resolveScript(Map<String, Object> config) throws IOException {
        var scriptFile = (String) config.get(SCRIPT_FILE_KEY);
        if (scriptFile != null) {
            return Files.readString(Path.of(scriptFile));
        }
        var resourcePath = (String) config.get(RESOURCE_PATH_KEY);
        if (resourcePath != null) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new IllegalArgumentException("Resource not found: " + resourcePath);
                }
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        var inline = (String) config.get(INLINE_SCRIPT_KEY);
        if (inline != null) {
            return inline;
        }
        throw new IllegalArgumentException(getConfigUsageStr());
    }

    private String getConfigUsageStr() {
        return "JsonJSPredicateProvider expects a config map with one of: "
            + SCRIPT_FILE_KEY + ", " + RESOURCE_PATH_KEY + ", or " + INLINE_SCRIPT_KEY;
    }
}
