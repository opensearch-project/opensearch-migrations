package org.opensearch.migrations.transform;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
public class JavascriptTransformerTest {

    private static final String INIT_SCRIPT = "((context) => (document) => ({docSize: Object.keys(document).length+2 }))";
    private static final String INIT_SCRIPT_2 =
        "function transformDoc(context, document) { " +
        "    return { docSize: Object.keys(document).length + 2 }; " +
        "} " +
        "function main(context) { " +
        "    return function(document) { " +
        "        return transformDoc(context, document); " +
        "    }; " +
        "}" +
        "main";

    @Test
    @SuppressWarnings("unchecked")
    public void testInlinedScriptPerformance() throws Exception {
        log.atInfo().setMessage("Starting initScript1 run").log();
        testScriptPerformance(INIT_SCRIPT);

        log.atInfo().setMessage("Starting initScript2 run").log();
        testScriptPerformance(INIT_SCRIPT_2);
    }

    private void testScriptPerformance(String script) throws Exception {
        try (var testTransformer = new JavascriptTransformer(script, Map.of("document", Map.of()))) {
            var testDoc = Map.of("hi", (Object) "world");
            for (int j = 0; j < 20; ++j) {
                var start = System.nanoTime();
                var count = 0;
                for (int i = 0; i < 1000; ++i) {
                    count += ((Map<String, Object>) testTransformer.transformJson(testDoc)).size();
                }
                log.atInfo().setMessage("Run {}: {}")
                    .addArgument(j)
                    .addArgument(Duration.ofNanos(System.nanoTime() - start)).log();
            }
        }
    }

    private static class CustomMapAlwaysContainsKey extends HashMap<String, Object> {
        @Override
        public boolean containsKey(Object key) {
            return true;
        }
    }

    @Test
    public void testCustomMap() throws Exception {
        var scriptCallsHas = "((ignored) => (map) => (map.has('missing')))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsHas, Map.of())) {
             var result = testTransformer.transformJson(new CustomMapAlwaysContainsKey());
             Assertions.assertEquals(true, result);
             Assertions.assertEquals(Boolean.class, result.getClass());
        }

        var scriptReturnsNewArray = "((ignored) => (map) => (['output']))";
        try (var testTransformer = new JavascriptTransformer(scriptReturnsNewArray, Map.of())) {
            var result = testTransformer.transformJson(new CustomMapAlwaysContainsKey());
            Assertions.assertEquals(List.of("output"), result);
            Assertions.assertEquals("com.oracle.truffle.polyglot.PolyglotList", result.getClass().getName());
        }

        var scriptReturnsInputMap = "((ignored) => (map) => (map))";
        try (var testTransformer = new JavascriptTransformer(scriptReturnsInputMap, Map.of())) {
            var input = new CustomMapAlwaysContainsKey();
            var result = testTransformer.transformJson(input);
            Assertions.assertEquals(input, result);
            Assertions.assertInstanceOf(CustomMapAlwaysContainsKey.class, result);
        }

    }

    @Test
    public void testPromiseHandling() throws Exception {
        // Script that returns a resolved Promise
        var successScript =
        "((ignored) => (map) => {" +
            "return new Promise((resolve, reject) => {" +
                "resolve(['output']);" +
            "});" +
        "});";

        // Test success case
        try (var testTransformer = new JavascriptTransformer(successScript, Map.of())) {
            var result = testTransformer.transformJson(null);
            Assertions.assertInstanceOf(List.class, result);
            Assertions.assertEquals(List.of("output"), result);
            Assertions.assertEquals("com.oracle.truffle.polyglot.PolyglotList", result.getClass().getName());
        }

        // Script that returns a rejected Promise
        var errorScript = "((ignored) => (map) => {" +
            "return new Promise((resolve, reject) => {" +
                "reject(new Error('Test Error'));" +
            "});" +
        "});";

        // Test error case
        try (var testTransformer = new JavascriptTransformer(errorScript, Map.of())) {
            var exception = Assertions.assertThrows(Exception.class, () -> {
                testTransformer.transformJson(null);
            });
            Assertions.assertTrue(exception.getMessage().contains("Test Error"), "Exception should contain the error message");
            Assertions.assertEquals(RuntimeException.class, exception.getCause().getClass());
        }
    }
}
